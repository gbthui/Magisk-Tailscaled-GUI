package top.cenmin.tailcontrol.core.model

import top.cenmin.tailcontrol.core.shell.CommandResult

/** UNKNOWN is not STOPPED: a failed root/proc probe must never trigger another consumer. */
enum class ReceiverPhase { STARTING, RUNNING, STOPPING, STOPPED, PAUSED, BLOCKED, UNKNOWN }

data class ReceiverStatus(
    val phase: ReceiverPhase,
    val generation: String? = null,
    val pid: Int? = null,
    val reason: String = "",
) {
    val quiescent: Boolean get() = phase == ReceiverPhase.STOPPED || phase == ReceiverPhase.PAUSED

    companion object {
        /** Structured diagnostics can accompany a nonzero helper exit (for example exit 69).
         * Keep the useful reason, but never turn a failed command into a healthy receiver.
         * Raw TC1 records are protocol, not user-facing error messages.
         */
        fun fromCommand(result: CommandResult): ReceiverStatus {
            val parsed = parse(result.out)
            if (result.ok) return parsed
            val diagnostics = (result.out + result.err)
                .filterNot { it.startsWith("TC1\t") }
                .joinToString("\n").trim().takeLast(2048)
            val summary = "${parsed.reason.ifBlank { "Root owner command failed" }} (exit ${result.code})"
            return parsed.copy(phase = ReceiverPhase.UNKNOWN,
                reason = if (diagnostics.isBlank()) summary else "$summary: $diagnostics")
        }

        fun parse(lines: List<String>): ReceiverStatus {
            val fields = lines.firstOrNull { it.startsWith("TC1\t") }?.split('\t')
                ?: return ReceiverStatus(ReceiverPhase.UNKNOWN, reason = "Missing root owner response")
            if (fields.size != 5) return ReceiverStatus(ReceiverPhase.UNKNOWN, reason = "Malformed root owner response")
            val phase = runCatching { ReceiverPhase.valueOf(fields[1]) }.getOrDefault(ReceiverPhase.UNKNOWN)
            val token = fields[2].takeIf { it != "-" && it.matches(Regex("[A-Za-z0-9-]{1,80}")) }
            if (phase in setOf(ReceiverPhase.STARTING, ReceiverPhase.RUNNING, ReceiverPhase.STOPPING, ReceiverPhase.PAUSED) && token == null) {
                return ReceiverStatus(ReceiverPhase.UNKNOWN, reason = "Missing owner generation")
            }
            return ReceiverStatus(phase, token, fields[3].toIntOrNull()?.takeIf { it > 0 }, fields[4])
        }
    }
}

data class DropRequest(val config: DropConfig, val revision: Long)

/** An immutable snapshot shown before the user confirms targeted legacy-loop cleanup. */
data class LegacyReceiver(val boot: String, val pid: Int, val startTicks: String, val uid: String, val argvHash: String) {
    fun arguments(): Array<String> = arrayOf(boot, pid.toString(), startTicks, uid, argvHash)

    companion object {
        fun parse(line: String): LegacyReceiver? {
            val f = line.split('\t')
            if (f.size != 6 || f[0] != "LEGACY") return null
            val pid = f[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            if (!f[1].matches(Regex("[a-fA-F0-9-]+")) || !f[3].all(Char::isDigit) ||
                !f[4].all(Char::isDigit) || !f[5].matches(Regex("[a-fA-F0-9]{64}"))) return null
            return LegacyReceiver(f[1], pid, f[3], f[4], f[5])
        }
    }
}
