package top.cenmin.tailcontrol.core.shell

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import top.cenmin.tailcontrol.share.TransferProgress
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** Host JVM tests: real /bin/sh and child pipes, with a fixture executable instead of root/Tailscale. */
class ShellRegressionTest {
    private fun shell(command: String, path: String = "/usr/bin:/bin"): Process =
        ProcessBuilder("/bin/sh", "-c", command).apply { environment()["PATH"] = path }.start()

    private fun output(command: String, path: String = "/usr/bin:/bin"): Pair<Int, String> {
        val p = shell(command, path)
        val text = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        return p.waitFor() to (text + err)
    }

    private fun fixture(body: (File) -> Unit) {
        val dir = Files.createTempDirectory("tailcontrol-cli-").toFile()
        try {
            File(dir, "tailscale").apply {
                writeText("#!/bin/sh\nprintf '%s\\000' \"\$@\"\n")
                check(setExecutable(true))
            }
            body(dir)
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingPathIsRepairedForOrdinaryAndStreamingCommands() = fixture { dir ->
        check(output("tailscale ping peer", "/nonexistent").first != 0)
        for (cmd in listOf(ShellCommand.ping("peer"), "tailscale netcheck", "tailscale status --json")) {
            val result = output(ShellCommand.isolated(cmd, dir.path), "/nonexistent")
            check(result.first == 0) { result.second }
        }
    }

    @Test fun fileTransferGetsEnvironmentInsideTheCommandShell() = fixture { dir ->
        val args = ShellCommand.fileSend("a b.txt", "100.101.102.103")
        val script = ShellCommand.script("exec ${ShellCommand.argv(*args)}", dir.path)
        val r = output(script, "/nonexistent")
        check(r.first == 0)
        check(r.second.split('\u0000').dropLast(1) == args.drop(1))
        check(ShellCommand.process(*args).startsWith("export PATH="))
    }

    @Test fun fileNamesAndTargetsStayLiteral() = fixture { dir ->
        val marker = File(dir, "must-not-exist")
        val name = "quote'\" dollar\$ `text` \$(touch ${marker.path})\n中文.txt"
        val args = ShellCommand.fileSend(name, "peer; touch ${marker.path}")
        val r = output(ShellCommand.isolated(ShellCommand.argv(*args), dir.path))
        check(r.first == 0)
        check(r.second.split('\u0000').dropLast(1) == args.drop(1))
        check(!marker.exists())
    }

    @Test fun pingInputCannotAddShellCommands() = fixture { dir ->
        val marker = File(dir, "must-not-exist")
        val target = "peer; touch ${marker.path}; \$(touch ${marker.path})"
        val r = output(ShellCommand.isolated(ShellCommand.ping(target), dir.path))
        check(r.first == 0)
        val args = r.second.split('\u0000').dropLast(1)
        check(args.takeLast(2) == listOf("--", target))
        check(!marker.exists())
    }

    @Test fun shellQuotePreservesEmptyAndMultilineValues() {
        for (value in listOf("", "a'b", "a\nb", "\$HOME", "x\\y", "中文")) {
            val r = output(ShellCommand.argv("printf", "%s", value))
            check(r.first == 0 && r.second == value)
        }
        check(runCatching { ShellCommand.quote("a\u0000b") }.isFailure)
    }

    @Test fun environmentAppliesToEveryCommandWithoutPollutingParent() = fixture { dir ->
        val script = ShellCommand.isolated("tailscale one; tailscale two", dir.path) +
            "\nprintf '\\n%s' \"\$PATH\""
        val r = output(script, "/nonexistent")
        check(r.first == 0)
        check(r.second.contains("one\u0000two\u0000"))
        check(r.second.endsWith("\n/nonexistent"))
    }

    @Test fun scriptsAreResolvedIndependentlyOfTheCli() = fixture { dir ->
        check(output(ShellCommand.isolated("command -v tailscale", dir.path)).first == 0)
        check(output(ShellCommand.isolated("command -v tailscaled.tun", dir.path)).first != 0)
        File(dir, "tailscaled.tun").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        check(output(ShellCommand.isolated("tailscaled.tun start", dir.path)).first == 0)
        File(dir, "tailscaled.tun").delete()
        check(output(ShellCommand.isolated("command -v tailscaled.tun", dir.path)).first != 0)
    }

    @Test fun receiverDirectoryAndLogsAreQuoted() = fixture { dir ->
        val directory = File(dir, "files ' \" dollar$ ; literal").path
        val log = File(dir, "receive ' log")
        val r = output(ShellCommand.isolated(ShellCommand.fileReceive(
            File(dir, "tailscale").path, directory, "rename", log.path), dir.path))
        check(r.first == 0 && r.second.trim().toIntOrNull() != null)
        repeat(100) { if (!log.exists() || log.length() == 0L) Thread.sleep(10) }
        val args = log.readText().split('\u0000').dropLast(1)
        check(args == listOf("file", "get", "--conflict=rename", "--loop", "--", directory)) { args.toString() }
    }

    @Test fun receiverIdentityDoesNotMatchOtherTailscaleClients() {
        check(ShellCommand.isFileReceiver("/data/adb/tailscale/bin/tailscale\u0000file\u0000get\u0000--loop\u0000"))
        for (line in listOf("tailscale\u0000file\u0000cp\u0000", "tailscale\u0000ping\u0000", "tailscaled\u0000file\u0000get\u0000", "")) {
            check(!ShellCommand.isFileReceiver(line))
        }
    }

    @Test fun capabilityDetectionFailsClosed() {
        val help = listOf("Send or receive files", "SUBCOMMANDS", "  cp  Copy file(s) to a host", "  get Receive files")
        check(ShellCommand.supportsFile(CommandResult(true, 0, help, emptyList())))
        for (code in listOf(1, 126, 127, -1)) {
            check(!ShellCommand.supportsFile(CommandResult(false, code, help, listOf("inaccessible or not found"))))
        }
        check(!ShellCommand.supportsFile(CommandResult(true, 0, emptyList(), emptyList())))
        check(!ShellCommand.supportsFile(CommandResult(true, 0, help, listOf("unknown subcommand: file"))))
    }

    @Test fun progressHandlesUnknownZeroAndOverflowWithoutClaimingCompletion() {
        check(TransferProgress.percent(10, -1) == -1)
        check(TransferProgress.percent(0, 0) == 0)
        check(TransferProgress.percent(50, 100) == 50)
        check(TransferProgress.percent(100, 100) == 99)
        check(TransferProgress.percent(Long.MAX_VALUE, 1) == 99)
        check(TransferProgress.percent(-10, 100) == 0)
    }

    @Test fun zeroByteFileClosesStdinAndCompletes() = runBlocking {
        withTimeout(5000) {
            val result = ProcessIo.transfer(shell("cat >/dev/null"), ByteArrayInputStream(byteArrayOf()), 0)
            check(result.ok) { result.text }
        }
    }

    @Test fun binaryStreamWithUnknownSizeIsCopiedExactly() = runBlocking {
        val destination = Files.createTempFile("tailcontrol-bytes-", ".bin").toFile()
        try {
            val data = ByteArray(256 * 1024) { (it % 251).toByte() }
            var transferred = 0L
            val r = withTimeout(5000) {
                ProcessIo.transfer(shell("cat > ${ShellCommand.quote(destination.path)}"), ByteArrayInputStream(data)) {
                    transferred = it
                }
            }
            check(r.ok) { r.text }
            check(transferred == data.size.toLong())
            check(destination.readBytes().contentEquals(data))
        } finally { destination.delete() }
    }

    @Test fun finalStderrIsCollectedAfterProcessExit() = runBlocking {
        val r = withTimeout(5000) {
            ProcessIo.transfer(shell("cat >/dev/null; sleep 0.05; echo final-transfer-error >&2; exit 23"),
                ByteArrayInputStream("content".toByteArray()))
        }
        check(!r.ok && r.code == 23)
        check(r.err.any { it.contains("final-transfer-error") }) { r.text }
    }

    @Test fun simultaneousLargeOutputDoesNotDeadlockOrGrowUnbounded() = runBlocking {
        val command = "i=0; while [ \$i -lt 5000 ]; do echo abcdefghijklmnopqrstuvwxyz; echo abcdefghijklmnopqrstuvwxyz >&2; i=\$((i+1)); done; cat >/dev/null; echo last-error >&2"
        val r = withTimeout(10000) {
            ProcessIo.transfer(shell(command), ByteArrayInputStream(ByteArray(512 * 1024)))
        }
        check(r.ok) { r.text }
        check(r.err.last() == "last-error")
        check(r.out.joinToString("\n").length <= 65536)
        check(r.err.joinToString("\n").length <= 65536)
    }

    @Test fun sourceFailureIsNotReportedAsSuccessfulTruncatedTransfer() = runBlocking {
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("source-disconnected")
        }
        val r = withTimeout(5000) { ProcessIo.transfer(shell("cat >/dev/null"), source) }
        check(!r.ok)
        check(r.text.contains("source-disconnected"))
    }

    @Test fun shortSourceDoesNotProduceFalseSuccess() = runBlocking {
        val r = withTimeout(5000) {
            ProcessIo.transfer(shell("cat >/dev/null"), ByteArrayInputStream(byteArrayOf(1)), 10)
        }
        check(!r.ok && r.text.contains("Source size changed"))
    }

    @Test fun cancellationUnblocksAWriterAndTerminatesOnlyItsProcess() = runBlocking {
        val process = shell("exec sleep 60")
        val unrelated = shell("exec sleep 60")
        try {
            withTimeout(5000) {
                val job = launch { ProcessIo.transfer(process, ByteArrayInputStream(ByteArray(4 * 1024 * 1024))) }
                delay(200)
                job.cancelAndJoin()
                check(!process.isAlive)
                check(unrelated.isAlive)
            }
        } finally { process.destroyForcibly(); unrelated.destroyForcibly() }
    }
}
