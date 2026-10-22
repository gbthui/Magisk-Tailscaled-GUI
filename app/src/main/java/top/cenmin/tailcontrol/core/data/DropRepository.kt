package top.cenmin.tailcontrol.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cenmin.tailcontrol.core.model.DropConfig
import top.cenmin.tailcontrol.core.model.LegacyReceiver
import top.cenmin.tailcontrol.core.model.ReceiverPhase
import top.cenmin.tailcontrol.core.model.ReceiverStatus
import top.cenmin.tailcontrol.core.shell.CommandResult
import top.cenmin.tailcontrol.core.shell.RootShell
import top.cenmin.tailcontrol.core.shell.ShellCommand
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DropRepository @Inject constructor(
    private val shell: RootShell,
    private val prefs: PreferencesRepository,
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Outside tailscale/run AND the module's install/backup tree. Never replace lock inodes.
        const val OWNER_ROOT = "/data/adb/tailcontrol"
    }

    private val lifecycle = Mutex()
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _status = MutableStateFlow(ReceiverStatus(ReceiverPhase.UNKNOWN))
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()
    private var installedScript: String? = null

    fun appendLog(line: String) { _output.update { (if (it.isBlank()) line else "$it\n$line").takeLast(64 * 1024) } }
    fun resetLog() { _output.value = "" }

    /** Calls are serialized here and again by the root helper across application lifetimes. */
    private suspend fun invoke(action: String, vararg arguments: String): CommandResult {
        try {
            val script = installedScript ?: installHelper().also { installedScript = it }
            val binary = shell.resolveExecutable("tailscale") ?: "/data/adb/tailscale/bin/tailscale"
            return shell.exec("TC_SH=/system/bin/sh " + ShellCommand.argv("/system/bin/sh", script, action, OWNER_ROOT, binary, *arguments))
        } catch (e: CancellationException) {
            // The helper may already have started. Recovery consults root state, never a lost $!.
            throw e
        } catch (e: Exception) {
            return CommandResult.failure(e.message ?: "Root owner unavailable")
        }
    }

    private suspend fun installHelper(): String {
        val script = context.assets.open("taildrop-owner.sh").bufferedReader().use { it.readText() }
        val digest = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val path = "$OWNER_ROOT/owner-$digest.sh"
        val qRoot = ShellCommand.quote(OWNER_ROOT)
        val qPath = ShellCommand.quote(path)
        val result = shell.exec("""
            [ ! -L $qRoot ] && mkdir -p $qRoot && chmod 700 $qRoot || exit 1
            [ "${'$'}(stat -c %u $qRoot)" = 0 ] || exit 1
            if [ ! -f $qPath ]; then
                tmp=${'$'}(mktemp ${ShellCommand.quote("$OWNER_ROOT/install.XXXXXX")}) || exit 1
                trap 'rm -f "${'$'}tmp"' EXIT
                printf '%s' ${ShellCommand.quote(script)} > "${'$'}tmp" && chmod 700 "${'$'}tmp" && mv -f "${'$'}tmp" $qPath || exit 1
            fi
            [ ! -L $qPath ] && [ "${'$'}(sha256sum $qPath | cut -d ' ' -f 1)" = '$digest' ]
        """.trimIndent())
        check(result.ok) { result.text.ifBlank { "Unable to install root owner helper" } }
        return path
    }

    private fun decode(result: CommandResult): ReceiverStatus {
        val decoded = ReceiverStatus.fromCommand(result)
        val before = _status.value
        _status.value = decoded
        if (decoded != before) appendLog("Receiver ${decoded.phase}: ${decoded.reason}; owner=${decoded.generation ?: "?"}; pid=${decoded.pid ?: "?"}")
        return decoded
    }

    suspend fun inspect(): ReceiverStatus = lifecycle.withLock { decode(invoke("status")) }

    /** Idempotent start also renews the current matching owner's lease; no separate PID heuristic. */
    suspend fun ensureRunning(config: DropConfig): ReceiverStatus = lifecycle.withLock {
        val observed = decode(invoke("start", UUID.randomUUID().toString(), config.path, config.conflict.cliValue))
        if (observed.phase == ReceiverPhase.RUNNING) {
            // UI/cache only; this value is never used as authority to launch or signal a process.
            prefs.rememberDropRuntime(config.path, config.conflict, observed.pid)
        }
        if (observed.phase == ReceiverPhase.PAUSED) appendDiagnostics()
        observed
    }

    /** The generation is captured BEFORE enqueueing cleanup; an old stop cannot stop a new session. */
    suspend fun requestStop(generation: String?): ReceiverStatus = lifecycle.withLock {
        if (generation == null) decode(invoke("status")) else decode(invoke("stop", generation))
    }

    suspend fun stopFileGet(): ReceiverStatus = lifecycle.withLock {
        val current = decode(invoke("status"))
        val result = current.generation?.let { decode(invoke("stop", it)) } ?: current
        if (result.quiescent) prefs.clearDropPid()
        result
    }

    suspend fun acknowledgePause(generation: String): Boolean = lifecycle.withLock {
        decode(invoke("acknowledge", generation)).phase == ReceiverPhase.STOPPED
    }

    suspend fun legacyReceivers(): List<LegacyReceiver> = lifecycle.withLock {
        val result = invoke("legacy-list")
        check(result.ok) { result.text.ifBlank { "Unable to inspect legacy receiver processes" } }
        result.out.mapNotNull(LegacyReceiver::parse)
    }

    suspend fun stopLegacyReceivers(snapshot: List<LegacyReceiver>): Boolean = lifecycle.withLock {
        var accepted = true
        for (process in snapshot) {
            val result = invoke("legacy-stop", *process.arguments())
            accepted = accepted && result.ok
            appendLog("Legacy receiver ${process.pid}: ${result.text.ifBlank { "identity changed or stop failed" }}")
        }
        // Sending TERM is not confirmation of exit. Preserve/report survivors.
        delay(500)
        val remaining = invoke("legacy-list")
        accepted && remaining.ok && remaining.out.mapNotNull(LegacyReceiver::parse)
            .none { candidate -> snapshot.any { it == candidate } }
    }

    private suspend fun appendDiagnostics() {
        val r = shell.exec("tail -n 30 ${ShellCommand.quote("$OWNER_ROOT/receiver.log")}")
        if (r.ok && r.text.isNotBlank()) appendLog(r.text)
    }
}
