package top.cenmin.tailcontrol.core.shell

/** Optional network cleanup is independent of a daemon accepting its down command. */
object DisconnectCommands {
    suspend fun run(cleanupEnabled: Boolean, down: suspend () -> CommandResult,
                    cleanup: suspend () -> CommandResult): CommandResult {
        val disconnected = down()
        if (!cleanupEnabled) return disconnected
        val cleaned = cleanup() // Also attempted when down returned a nonzero exit code.
        val errors = mutableListOf<String>()
        if (!disconnected.ok) errors += "tailscale down failed (exit ${disconnected.code}): ${disconnected.text.ifBlank { "no diagnostics" }}"
        if (!cleaned.ok) errors += "TUN cleanup failed (exit ${cleaned.code}): ${cleaned.text.ifBlank { "no diagnostics" }}"
        return CommandResult(disconnected.ok && cleaned.ok,
            if (!disconnected.ok) disconnected.code else cleaned.code,
            disconnected.out + cleaned.out, errors)
    }
}
