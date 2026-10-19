package top.cenmin.tailcontrol.core.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.cenmin.tailcontrol.core.model.ConflictBehavior
import top.cenmin.tailcontrol.core.shell.RootShell
import top.cenmin.tailcontrol.core.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DropRepository @Inject constructor(
    private val shell: RootShell,
    private val prefs: PreferencesRepository,
) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val lifecycle = Mutex()
    private val logPath = "/data/adb/tailscale/run/tailcontrol-file-get.log"

    fun appendLog(line: String) {
        _output.update { (if (it.isBlank()) line else "$it\n$line").takeLast(64 * 1024) }
    }

    fun resetLog() { _output.value = "" }

    /** A recycled PID or another tailscale operation is not our receive loop. */
    suspend fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        val r = shell.exec("kill -0 $pid 2>/dev/null && cat /proc/$pid/cmdline")
        if (!r.ok) return false
        return ShellCommand.isFileReceiver(r.out.joinToString("\n"))
    }

    suspend fun startFileGet(path: String, behavior: ConflictBehavior): Int? = lifecycle.withLock {
        var startedPid: Int? = null
        var remembered = false
        try {
            require(path.startsWith('/')) { "Receive directory must be an absolute path" }
            val binary = shell.resolveExecutable("tailscale")
            if (binary == null) {
                appendLog("Cannot execute tailscale; check the module installation and root access")
                return@withLock null
            }
            val help = shell.exec(ShellCommand.argv(binary, "file", "get", "--help"))
            if (!help.ok) {
                appendLog(help.text.ifBlank { "This CLI cannot run file get" })
                return@withLock null
            }
            // Use the same root context that will receive the files, not the app's storage sandbox.
            val dir = ShellCommand.quote(path)
            val prepared = shell.exec("mkdir -p $dir /data/adb/tailscale/run && [ -d $dir ] && [ -w $dir ]")
            if (!prepared.ok) {
                appendLog("Cannot use receive directory: ${prepared.text}")
                return@withLock null
            }
            val launched = shell.exec(ShellCommand.fileReceive(binary, path, behavior.cliValue, logPath))
            startedPid = launched.out.lastOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            if (!launched.ok || startedPid == null) {
                appendLog(launched.text.ifBlank { "No receiver PID returned" })
                return@withLock null
            }
            val pid = startedPid
            delay(250)
            if (!isProcessAlive(pid)) {
                val error = shell.exec("tail -n 30 ${ShellCommand.quote(logPath)}").text
                appendLog("Receiver exited during startup: ${error.ifBlank { "PID $pid is not running" }}")
                return@withLock null
            }
            // nohup execs the command: persist this verified PID, never an optional ps-derived child.
            prefs.rememberDropRuntime(path, behavior, pid)
            remembered = true
            pid
        } finally {
            if (!remembered) startedPid?.let { pid ->
                withContext(NonCancellable) {
                    if (isProcessAlive(pid)) shell.exec("kill $pid")
                }
            }
        }
    }

    suspend fun stopFileGet() = lifecycle.withLock {
        val (_, _, pid) = prefs.loadDropRuntime()
        if (pid != null && isProcessAlive(pid)) {
            shell.exec("kill $pid")
            delay(200)
            if (isProcessAlive(pid)) shell.exec("kill -9 $pid")
        }
        prefs.clearDropPid()
    }
}
