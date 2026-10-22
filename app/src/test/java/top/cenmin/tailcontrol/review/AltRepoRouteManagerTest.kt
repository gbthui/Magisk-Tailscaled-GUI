package top.cenmin.tailcontrol.review

import kotlinx.coroutines.runBlocking
import org.junit.Test
import top.cenmin.tailcontrol.core.manager.AltRepoRouteManager
import top.cenmin.tailcontrol.core.shell.CommandResult
import java.io.File
import java.nio.file.Files

class AltRepoRouteManagerTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("tailcontrol-tun-").toFile()
        val up = File(dir, "tailscaled.tun.up")
        val down = File(dir, "tailscaled.tun.down")
        val actions = mutableListOf<String>()
        var available = true
        var stopFails = false
        var failedCommand: (String) -> Boolean = { false }
        init {
            up.writeText("#!/system/bin/sh\necho user-up\n")
            down.writeText("#!/system/bin/sh\necho user-down\n")
        }
        val manager = AltRepoRouteManager(
            exec = { command ->
                if (failedCommand(command)) CommandResult.failure("injected edit failure", 73)
                else {
                    val p = ProcessBuilder("/bin/sh", "-c", command).start()
                    val out = p.inputStream.bufferedReader().readLines()
                    val err = p.errorStream.bufferedReader().readLines()
                    val code = p.waitFor()
                    CommandResult(code == 0, code, out, err)
                }
            },
            available = { available },
            tun = { action ->
                actions += action
                if (action == "stop" && stopFails) CommandResult.failure("stop failed", 1)
                else CommandResult(true, 0, emptyList(), emptyList())
            },
            primaryRoutes = { listOf("10.0.0.0/24") }, scriptsDir = dir.path,
        )
        fun markFiles() {
            val block = "# --- TailControl managed routes begin ---\necho managed\n# --- TailControl managed routes end ---\n"
            up.appendText(block); down.appendText(block)
        }
        fun backups() {
            up.copyTo(File(dir, "tailscaled.tun.up.bak"))
            down.copyTo(File(dir, "tailscaled.tun.down.bak"))
        }
        override fun close() { dir.deleteRecursively() }
    }

    @Test fun partialSyncNeverRestartsTun() = runBlocking {
        Fixture().use { f ->
            f.failedCommand = { it.trimStart().startsWith("tmp=") && it.contains("tailscaled.tun.down.XXXXXX") }
            val result = f.manager.syncRoutes()
            check(!result.ok && !result.scriptsUpdated && f.actions == listOf("stop"))
            check(f.up.readText().contains("managed routes begin")) // First write really happened.
            check(!f.down.readText().contains("managed routes begin"))
        }
    }
    @Test fun partialRestoreNeverRestartsTun() = runBlocking {
        Fixture().use { f ->
            f.backups(); f.markFiles()
            f.failedCommand = { it.startsWith("'cp' '-f'") && it.contains("tailscaled.tun.down.bak") }
            check(!f.manager.restoreBackup().ok && f.actions == listOf("stop"))
        }
    }
    @Test fun partialRemovalNeverRestartsTun() = runBlocking {
        Fixture().use { f ->
            f.markFiles()
            f.failedCommand = { it.trimStart().startsWith("tmp=") && it.contains("tailscaled.tun.down.XXXXXX") }
            val result = f.manager.removeRoutes()
            check(!result.ok && !result.scriptsUpdated && f.actions == listOf("stop"))
        }
    }
    @Test fun missingTunStillRemovesOwnedBlocksAndPreservesUserContent() = runBlocking {
        Fixture().use { f ->
            f.markFiles(); f.available = false
            val result = f.manager.removeRoutes()
            check(result.scriptsUpdated && !result.ok && f.actions.isEmpty())
            check(!f.up.readText().contains("managed") && f.up.readText().contains("user-up"))
            check(!f.down.readText().contains("managed") && f.down.readText().contains("user-down"))
            check(result.message.contains("active network rules may remain"))
        }
    }
    @Test fun stopFailureDoesNotPreventDiskCleanupOrCauseRestart() = runBlocking {
        Fixture().use { f ->
            f.markFiles(); f.stopFails = true
            val result = f.manager.removeRoutes()
            check(result.scriptsUpdated && !result.ok && f.actions == listOf("stop"))
            check(!f.up.readText().contains("managed") && !f.down.readText().contains("managed"))
        }
    }
    @Test fun oneBackupIsNotAConsistentPair() = runBlocking {
        Fixture().use { f ->
            f.up.copyTo(File(f.dir, "tailscaled.tun.up.bak"))
            check(!f.manager.restoreBackup().ok && f.actions.isEmpty())
        }
    }
    @Test fun completeSyncRestartsOnlyAfterBothWrites() = runBlocking {
        Fixture().use { f ->
            check(f.manager.syncRoutes().ok && f.actions == listOf("stop", "start"))
            check(f.up.readText().contains("route add") && f.down.readText().contains("route del"))
        }
    }
    @Test fun unavailableTunCannotInjectNewRoutes() = runBlocking {
        Fixture().use { f ->
            f.available = false
            check(!f.manager.syncRoutes().ok && f.actions.isEmpty())
            check(!f.up.readText().contains("managed"))
        }
    }
}
