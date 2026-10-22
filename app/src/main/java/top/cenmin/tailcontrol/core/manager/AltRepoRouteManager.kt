package top.cenmin.tailcontrol.core.manager

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.shell.CommandResult
import top.cenmin.tailcontrol.core.shell.RootShell
import top.cenmin.tailcontrol.core.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton

/** Manages marked route blocks in the optional AltRepo TUN scripts. */
@Singleton
class AltRepoRouteManager internal constructor(
    private val exec: suspend (String) -> CommandResult,
    private val available: suspend () -> Boolean,
    private val tun: suspend (String) -> CommandResult,
    private val primaryRoutes: suspend () -> List<String>,
    scriptsDir: String = "/data/adb/tailscale/scripts",
) {
    @Inject constructor(shell: RootShell, repo: TailscaleRepository) : this(
        shell::exec, repo::hasTunScript, repo::runTun,
        { val status = repo.fetchStatus(); status.peers.flatMap { it.primaryRoutes } + (status.self?.primaryRoutes ?: emptyList()) },
    )

    private val lifecycle = Mutex()
    private val tunUp = "$scriptsDir/tailscaled.tun.up"
    private val tunDown = "$scriptsDir/tailscaled.tun.down"
    private val tunUpBackup = "$tunUp.bak"
    private val tunDownBackup = "$tunDown.bak"
    companion object {
        private const val BLOCK_BEGIN = "# --- TailControl managed routes begin ---"
        private const val BLOCK_END = "# --- TailControl managed routes end ---"
    }

    suspend fun isAvailable(): Boolean = available()
    private fun unavailable() = SyncResult(false,
        "tailscaled.tun is unavailable. This feature requires an AltRepo-compatible module; no scripts were changed.")

    suspend fun syncRoutes(): SyncResult = lifecycle.withLock {
        if (!isAvailable()) return@withLock unavailable()
        val routes = primaryRoutes().filter { it.isNotBlank() }.distinct().sorted()
        if (routes.isEmpty()) return@withLock SyncResult(false, "No primary routes found in tailscale status.")
        if (!ensureFileExists(tunUp) || !ensureFileExists(tunDown) ||
            !backupIfAbsent(tunUp, tunUpBackup) || !backupIfAbsent(tunDown, tunDownBackup)) {
            return@withLock SyncResult(false, "Unable to prepare or back up TUN scripts; routes were not changed.")
        }
        val stopped = tun("stop")
        if (!stopped.ok) return@withLock SyncResult(false, "TUN stop failed; scripts were not edited: ${stopped.text}")
        val up = rewriteBlock(tunUp, buildBlock(routes, add = true))
        val down = rewriteBlock(tunDown, buildBlock(routes, add = false))
        finishEdits(up, down, stopped, routes)
    }

    suspend fun restoreBackup(): SyncResult = lifecycle.withLock {
        if (!isAvailable()) return@withLock unavailable()
        // A single backup cannot restore a consistent up/down pair.
        if (!fileExists(tunUpBackup) || !fileExists(tunDownBackup)) {
            return@withLock SyncResult(false, "Both TUN backup files are required. Nothing was restored.")
        }
        val stopped = tun("stop")
        if (!stopped.ok) return@withLock SyncResult(false, "TUN stop failed; backup was not restored: ${stopped.text}")
        val up = exec(ShellCommand.argv("cp", "-f", tunUpBackup, tunUp))
        val down = exec(ShellCommand.argv("cp", "-f", tunDownBackup, tunDown))
        finishEdits(up, down, stopped)
    }

    suspend fun removeRoutes(): SyncResult = lifecycle.withLock {
        // On-disk cleanup is independent of executable discovery. In particular, a removed
        // optional module must not leave our injected blocks waiting to run when it returns.
        val stopped = if (isAvailable()) tun("stop") else CommandResult.failure(
            "tailscaled.tun is unavailable; active routes/firewall cleanup cannot be confirmed", 127)
        val up = rewriteBlock(tunUp, null)
        val down = rewriteBlock(tunDown, null)
        finishEdits(up, down, stopped)
    }

    private suspend fun finishEdits(up: CommandResult, down: CommandResult, stopped: CommandResult,
                                   routes: List<String> = emptyList()): SyncResult {
        val messages = mutableListOf(
            "tun stop: ${if (stopped.ok) "OK" else stopped.text}",
            "tun.up: ${if (up.ok) "OK" else up.text}",
            "tun.down: ${if (down.ok) "OK" else down.text}",
        )
        // Never restart an inconsistent pair, including a partial restore or block removal.
        if (!up.ok || !down.ok || !stopped.ok) {
            messages += if (stopped.ok) "TUN remains stopped. Repair/restore both scripts before reconnecting."
                else "TUN was not restarted. Owned blocks were cleaned where possible, but active network rules may remain."
            return SyncResult(false, messages.joinToString("\n"), routes, scriptsUpdated = up.ok && down.ok)
        }
        val started = tun("start")
        messages += "tun start: ${if (started.ok) "OK" else started.text}"
        return SyncResult(started.ok, messages.joinToString("\n"), routes, scriptsUpdated = true)
    }

    suspend fun hasBackup(): Boolean = fileExists(tunUpBackup) || fileExists(tunDownBackup)
    private suspend fun fileExists(path: String): Boolean = exec("[ -f ${ShellCommand.quote(path)} ]").ok
    private suspend fun ensureFileExists(path: String): Boolean = fileExists(path) ||
        exec("printf '%s' ${ShellCommand.quote("#!/system/bin/sh\n")} > ${ShellCommand.quote(path)} && chmod 755 ${ShellCommand.quote(path)}").ok
    private suspend fun backupIfAbsent(src: String, bak: String): Boolean = fileExists(bak) || exec(ShellCommand.argv("cp", src, bak)).ok

    /** Root-owned, same-directory temporary files allow atomic replacement of each script. */
    private suspend fun rewriteBlock(path: String, block: String?): CommandResult {
        if (block == null) {
            // Test absence separately from read failure. Do not turn denied/unreadable files into success.
            val absent = exec("[ ! -e ${ShellCommand.quote(path)} ] && [ ! -L ${ShellCommand.quote(path)} ]")
            if (absent.ok) return CommandResult(true, 0, emptyList(), emptyList())
        }
        val awk = "/^# --- TailControl managed routes begin ---/{skip=1;next} /^# --- TailControl managed routes end ---/{skip=0;next} !skip{print}"
        val append = if (block == null) "" else "printf '%s\\n' ${ShellCommand.quote(block)} >> \"\$tmp\" || exit 1"
        val command = """
            tmp=${'$'}(mktemp ${ShellCommand.quote("$path.XXXXXX")}) || exit 1
            trap 'rm -f "${'$'}tmp"' EXIT
            awk ${ShellCommand.quote(awk)} ${ShellCommand.quote(path)} > "${'$'}tmp" || exit 1
            $append
            chmod 755 "${'$'}tmp" && mv -f "${'$'}tmp" ${ShellCommand.quote(path)}
        """.trimIndent()
        return exec(command)
    }

    private fun buildBlock(routes: List<String>, add: Boolean): String = buildString {
        appendLine(BLOCK_BEGIN)
        for (route in routes) {
            val target = ShellCommand.quote(route)
            val ip = if (':' in route) "ip -6" else "ip"
            val firewall = if (':' in route) "ip6tables" else "iptables"
            if (add) {
                appendLine("$ip route add $target dev tailscale0 metric 1 2>/dev/null || true")
                appendLine("$firewall -t mangle -A OUTPUT -d $target -j MARK --set-mark 1099 2>/dev/null || true")
            } else {
                appendLine("$ip route del $target dev tailscale0 2>/dev/null || true")
                appendLine("$firewall -t mangle -D OUTPUT -d $target -j MARK --set-mark 1099 2>/dev/null || true")
            }
        }
        append(BLOCK_END)
    }

    data class SyncResult(val ok: Boolean, val message: String = "", val routes: List<String> = emptyList(),
                          val scriptsUpdated: Boolean = false)
}
