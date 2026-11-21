package top.cenmin.tailcontrol.core.shell

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class TransferShellTest {
    private fun shell(script: String): Process = ProcessBuilder("sh", "-c", script).start()

    @Test
    fun quoteAndArgvEscapeSpecialCharactersWithoutExpansion() {
        val raw = "a 'quoted' \$(touch /tmp/pwn) `id` ; name.pdf"
        val out = ProcessBuilder("sh", "-c", "printf '%s' ${RootShell.quote(raw)}")
            .start()
            .inputStream.bufferedReader().readText()
        check(out == raw) { "Expected <$raw>, got <$out>" }
    }

    @Test
    fun processScriptInjectsFallbackPathAndExecsTarget() {
        val dir = Files.createTempDirectory("tc-bin-").toFile()
        try {
            val fake = dir.resolve("tailscale").apply {
                writeText("#!/bin/sh\nprintf '%s\\0' \"\$@\"\n")
                setExecutable(true)
            }
            check(fake.canExecute())
            val script = RootShell.processScript(
                "tailscale", "file", "cp", "--name", "my 'file' \$1.txt", "--", "-", "100.64.0.1:",
                fallbackPath = dir.absolutePath,
            )
            val proc = shell(script)
            val args = proc.inputStream.bufferedReader().readText().split('\u0000').dropLast(1)
            check(proc.waitFor() == 0)
            check(args == listOf("file", "cp", "--name", "my 'file' \$1.txt", "--", "-", "100.64.0.1:")) {
                "Unexpected args: $args"
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun transferCopiesBinaryStreamAndDrainsSimultaneousOutput() = runBlocking {
        val dst = Files.createTempFile("tc-out-", ".bin").toFile()
        try {
            val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
            var reported = 0L
            val cmd = "i=0; while [ \$i -lt 2000 ]; do echo out-\$i; echo err-\$i >&2; i=\$((i+1)); done; cat > ${RootShell.quote(dst.path)}"
            val r = withTimeout(10_000) {
                ProcessIo.transfer(shell(cmd), ByteArrayInputStream(payload), payload.size.toLong()) {
                    reported = it
                }
            }
            check(r.ok) { r.text }
            check(reported == payload.size.toLong())
            check(dst.readBytes().contentEquals(payload))
        } finally {
            dst.delete()
        }
    }

    @Test
    fun sourceReadFailureFailsClosed() = runBlocking {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("stream-broken")
        }
        val r = withTimeout(5_000) { ProcessIo.transfer(shell("cat >/dev/null"), broken) }
        check(!r.ok && r.text.contains("stream-broken"))
    }

    @Test
    fun cancellationStopsOnlyCurrentTransferProcess() = runBlocking {
        val transfer = shell("exec sleep 60")
        val bystander = shell("exec sleep 60")
        try {
            withTimeout(5_000) {
                val job = launch {
                    ProcessIo.transfer(transfer, ByteArrayInputStream(ByteArray(4 * 1024 * 1024)))
                }
                delay(150)
                job.cancelAndJoin()
                check(!transfer.isAlive)
                check(bystander.isAlive)
            }
        } finally {
            transfer.destroyForcibly()
            bystander.destroyForcibly()
        }
    }
}
