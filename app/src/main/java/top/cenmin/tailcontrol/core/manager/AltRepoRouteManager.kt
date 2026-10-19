package top.cenmin.tailcontrol.core.manager

import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.shell.RootShell
import top.cenmin.tailcontrol.core.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton

/** Manages marked route blocks in the optional AltRepo TUN scripts. */
@Singleton
class AltRepoRouteManager @Inject constructor(
    private val shell: RootShell,
    private val tailscaleRepository: TailscaleRepository,
) {
    companion object {
        private const val SCRIPTS_DIR = "/data/adb/tailscale/scripts"
        private const val TUN_UP = "$SCRIPTS_DIR/tailscaled.tun.up"
        private const val TUN_DOWN = "$SCRIPTS_DIR/tailscaled.tun.down"
        private const val TUN_UP_BAK = "$TUN_UP.bak"
        private const val TUN_DOWN_BAK = "$TUN_DOWN.bak"
        private const val BLOCK_BEGIN = "# --- TailControl managed routes begin ---"
        private const val BLOCK_END = "# --- TailControl managed routes end ---"
    }

    suspend fun isAvailable(): Boolean = tailscaleRepository.hasTunScript()

    private fun unavailable() = SyncResult(false,
        "tailscaled.tun is unavailable. This feature requires an AltRepo-compatible module; no scripts were changed.")

    suspend fun syncRoutes(): SyncResult {
        // Check BEFORE creating, backing up, or editing any scripts.
        if (!isAvailable()) return unavailable()
        val status = tailscaleRepository.fetchStatus()
        val routes = (status.peers.flatMap { it.primaryRoutes } + (status.self?.primaryRoutes ?: emptyList()))
            .filter { it.isNotBlank() }.distinct().sorted()
        if (routes.isEmpty()) return SyncResult(false, "No primary routes found in tailscale status.")

        if (!ensureFileExists(TUN_UP) || !ensureFileExists(TUN_DOWN) ||
            !backupIfAbsent(TUN_UP, TUN_UP_BAK) || !backupIfAbsent(TUN_DOWN, TUN_DOWN_BAK)) {
            return SyncResult(false, "Unable to prepare or back up TUN scripts; routes were not changed.")
        }
        val stopped = tailscaleRepository.runTun("stop")
        if (!stopped.ok) return SyncResult(false, "TUN stop failed; scripts were not edited: ${stopped.text}")
        val up = injectBlock(TUN_UP, buildUpBlock(routes))
        val down = injectBlock(TUN_DOWN, buildDownBlock(routes))
        val started = tailscaleRepository.runTun("start")
        return SyncResult(up.ok && down.ok && started.ok,
            "Routes: ${routes.joinToString(", ")}\ntun.up: ${up.message.ifBlank { "OK" }}\n" +
                "tun.down: ${down.message.ifBlank { "OK" }}\ntun start: ${if (started.ok) "OK" else started.text}", routes)
    }

    suspend fun restoreBackup(): SyncResult {
        if (!isAvailable()) return unavailable()
        val upExists = fileExists(TUN_UP_BAK)
        val downExists = fileExists(TUN_DOWN_BAK)
        if (!upExists && !downExists) return SyncResult(false, "No backup files found. Nothing to restore.")
        val stopped = tailscaleRepository.runTun("stop")
        if (!stopped.ok) return SyncResult(false, "TUN stop failed; backup was not restored: ${stopped.text}")
        val messages = mutableListOf<String>()
        var ok = true
        for ((exists, backup, target) in listOf(Triple(upExists, TUN_UP_BAK, TUN_UP), Triple(downExists, TUN_DOWN_BAK, TUN_DOWN))) {
            if (exists) {
                val restored = shell.exec(ShellCommand.argv("cp", "-f", backup, target))
                ok = ok && restored.ok
                messages += "$target: ${if (restored.ok) "restored" else restored.text}"
            }
        }
        val started = tailscaleRepository.runTun("start")
        messages += "tun start: ${if (started.ok) "OK" else started.text}"
        return SyncResult(ok && started.ok, messages.joinToString("\n"))
    }

    suspend fun removeRoutes(): SyncResult {
        if (!isAvailable()) return unavailable()
        val stopped = tailscaleRepository.runTun("stop")
        if (!stopped.ok) return SyncResult(false, "TUN stop failed; scripts were not edited: ${stopped.text}")
        val up = removeBlock(TUN_UP)
        val down = removeBlock(TUN_DOWN)
        val started = tailscaleRepository.runTun("start")
        return SyncResult(up.ok && down.ok && started.ok,
            "tun.up: ${up.message.ifBlank { "cleaned" }}\ntun.down: ${down.message.ifBlank { "cleaned" }}\n" +
                "tun start: ${if (started.ok) "OK" else started.text}")
    }

    suspend fun hasBackup(): Boolean = fileExists(TUN_UP_BAK) || fileExists(TUN_DOWN_BAK)

    private suspend fun fileExists(path: String): Boolean = shell.exec("[ -f ${ShellCommand.quote(path)} ]").ok

    private suspend fun ensureFileExists(path: String): Boolean = fileExists(path) ||
        shell.exec("printf '%s' ${ShellCommand.quote("#!/system/bin/sh\n")} > ${ShellCommand.quote(path)} && chmod 755 ${ShellCommand.quote(path)}").ok

    private suspend fun backupIfAbsent(src: String, bak: String): Boolean = fileExists(bak) ||
        shell.exec(ShellCommand.argv("cp", src, bak)).ok

    private suspend fun removeBlock(path: String): InjectResult = rewriteBlock(path, null)
    private suspend fun injectBlock(path: String, block: String): InjectResult = rewriteBlock(path, block)

    /** Root-owned, same-directory temporary files avoid shared /data/local/tmp names and allow atomic mv. */
    private suspend fun rewriteBlock(path: String, block: String?): InjectResult {
        if (block == null && !fileExists(path)) return InjectResult(true)
        val awk = "/^# --- TailControl managed routes begin ---/{skip=1;next} /^# --- TailControl managed routes end ---/{skip=0;next} !skip{print}"
        val append = if (block == null) "" else "printf '%s\\n' ${ShellCommand.quote(block)} >> \"\$tmp\" || exit 1"
        val command = """
            tmp=${'$'}(mktemp ${ShellCommand.quote("$path.XXXXXX")}) || exit 1
            trap 'rm -f "${'$'}tmp"' EXIT
            awk ${ShellCommand.quote(awk)} ${ShellCommand.quote(path)} > "${'$'}tmp" || exit 1
            $append
            chmod 755 "${'$'}tmp" && mv -f "${'$'}tmp" ${ShellCommand.quote(path)}
        """.trimIndent()
        val r = shell.exec(command)
        return InjectResult(r.ok, if (r.ok) "" else r.text.ifBlank { "Script update failed (${r.code})" })
    }

    private fun buildUpBlock(routes: List<String>): String = buildString {
        appendLine(BLOCK_BEGIN)
        for (route in routes) {
            val target = ShellCommand.quote(route)
            val ip = if (':' in route) "ip -6" else "ip"
            val firewall = if (':' in route) "ip6tables" else "iptables"
            appendLine("$ip route add $target dev tailscale0 metric 1 2>/dev/null || true")
            appendLine("$firewall -t mangle -A OUTPUT -d $target -j MARK --set-mark 1099 2>/dev/null || true")
        }
        append(BLOCK_END)
    }

    private fun buildDownBlock(routes: List<String>): String = buildString {
        appendLine(BLOCK_BEGIN)
        for (route in routes) {
            val target = ShellCommand.quote(route)
            val ip = if (':' in route) "ip -6" else "ip"
            val firewall = if (':' in route) "ip6tables" else "iptables"
            appendLine("$ip route del $target dev tailscale0 2>/dev/null || true")
            appendLine("$firewall -t mangle -D OUTPUT -d $target -j MARK --set-mark 1099 2>/dev/null || true")
        }
        append(BLOCK_END)
    }

    data class SyncResult(val ok: Boolean, val message: String = "", val routes: List<String> = emptyList())
    private data class InjectResult(val ok: Boolean, val message: String = "")
}
