package top.cenmin.tailcontrol.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import top.cenmin.tailcontrol.core.model.AccountItem
import top.cenmin.tailcontrol.core.model.BackendState
import top.cenmin.tailcontrol.core.model.DnsStatus
import top.cenmin.tailcontrol.core.model.TailscaleDevice
import top.cenmin.tailcontrol.core.model.TailscaleJson
import top.cenmin.tailcontrol.core.model.TailscaleStatus
import top.cenmin.tailcontrol.core.model.TailscaleStatusJson
import top.cenmin.tailcontrol.core.model.WhoisInfo
import top.cenmin.tailcontrol.core.model.toDevice
import top.cenmin.tailcontrol.core.shell.CommandResult
import top.cenmin.tailcontrol.core.shell.RootShell
import top.cenmin.tailcontrol.core.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TailscaleRepository @Inject constructor(
    private val shell: RootShell,
    private val prefs: PreferencesRepository,
) {

    suspend fun fetchStatus(): TailscaleStatus {
        val result = shell.exec("tailscale status --json")
        if (!result.ok) {
            return TailscaleStatus(backendState = BackendState.DaemonOffline, rawJson = result.text)
        }
        // stderr diagnostics are not part of the JSON document.
        return decodeStatus(result.out.joinToString("\n"))
    }

    fun decodeStatus(raw: String): TailscaleStatus {
        if (raw.isBlank()) return TailscaleStatus(backendState = BackendState.DaemonOffline, rawJson = raw)
        if (raw.contains("failed to connect", ignoreCase = true)) {
            return TailscaleStatus(backendState = BackendState.DaemonOffline, rawJson = raw)
        }
        return runCatching {
            val parsed = TailscaleJson.decodeFromString(TailscaleStatusJson.serializer(), raw)
            val state = BackendState.fromCli(parsed.backendState)
            val self = parsed.self?.toDevice(isSelf = true)
            val peers = parsed.peer?.values
                ?.map { it.toDevice(isSelf = false) }
                ?.sortedWith(
                    compareByDescending<TailscaleDevice> { it.online }
                        .thenByDescending { it.rawLastSeen ?: "" }
                        .thenBy { it.name.lowercase() }
                ).orEmpty()
            val users = parsed.user?.entries?.associate { (k, v) -> (k.toLongOrNull() ?: -1L) to v }.orEmpty()
            val healthCheck = parsed.health ?: emptyList()
            TailscaleStatus(
                backendState = state,
                self = self,
                peers = peers,
                users = users,
                healthCheck = healthCheck,
                rawJson = raw,
            )
        }.getOrElse {
            TailscaleStatus(backendState = BackendState.Unknown, rawJson = raw)
        }
    }

    /** Connect after successfully starting the optional AltRepo TUN integration. */
    suspend fun up(args: String = ""): CommandResult {
        if (prefs.altRepoOptimizationEnabled.first()) {
            val tun = runTun("start")
            if (!tun.ok) return tun
        }
        return shell.exec("tailscale up ${args.trim()}".trim())
    }

    /** Disconnect even if an optional TUN script has disappeared; report failed cleanup. */
    suspend fun down(): CommandResult {
        val result = shell.exec("tailscale down")
        if (!result.ok) return result
        if (prefs.altRepoOptimizationEnabled.first()) {
            val tun = runTun("stop")
            if (!tun.ok) return CommandResult.failure("Tailscale disconnected, but TUN cleanup failed: ${tun.text}", tun.code)
        }
        return result
    }

    suspend fun hasTunScript(): Boolean = shell.resolveExecutable("tailscaled.tun") != null

    suspend fun runTun(action: String): CommandResult {
        require(action in setOf("start", "stop", "restart")) { "Invalid TUN action" }
        val path = shell.resolveExecutable("tailscaled.tun")
            ?: return CommandResult.failure("tailscaled.tun is unavailable. Disable AltRepo optimization or install a compatible module.", 127)
        return shell.exec(ShellCommand.argv(path, action))
    }

    suspend fun set(args: String): CommandResult = shell.exec("tailscale set ${args.trim()}".trim())

    suspend fun daemonStart(): CommandResult = shell.exec("tailscaled.service start")
    suspend fun daemonStop(): CommandResult = shell.exec("tailscaled.service stop")
    suspend fun daemonRestart(): CommandResult = shell.exec("tailscaled.service restart")

    suspend fun listAccounts(): List<AccountItem> {
        val result = shell.exec("tailscale switch --list")
        if (!result.ok) return emptyList()
        return result.out.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) return@mapNotNull null
            val raw3 = parts[2]
            AccountItem(id = parts[0], account = raw3.removeSuffix("*"), isCurrent = raw3.endsWith("*"))
        }
    }

    suspend fun switchAccount(id: String): CommandResult =
        shell.exec(ShellCommand.argv("tailscale", "switch", "--", id))

    suspend fun logout(): CommandResult = shell.exec("tailscale logout")

    /** Explicit advanced CLI arguments remain shell syntax; ordinary values use ShellCommand.argv. */
    fun login(args: String): Flow<String> = shell.stream("tailscale login ${args.trim()}".trim())

    fun ping(target: String): Flow<String> = shell.stream(ShellCommand.ping(target))
    fun netcheck(): Flow<String> = shell.stream("tailscale netcheck")

    suspend fun pidofTailscaled(): Int? =
        shell.execText("pidof tailscaled").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /** Explicit user-requested emergency action only; never used for transfer cancellation. */
    suspend fun killAllTailscale(): CommandResult = shell.exec("pkill -9 -x tailscale")

    suspend fun pgrepTailscaleCount(): Int {
        val result = shell.exec("pgrep -x tailscale")
        return result.out.count { it.trim().toIntOrNull() != null }
    }

    /** Probe the installed build's capabilities, not its version number. */
    suspend fun isFileCommandSupported(): Boolean =
        ShellCommand.supportsFile(shell.exec("tailscale file --help"))

    suspend fun exitNodeSuggest(): String? {
        val text = shell.execText("tailscale exit-node suggest")
        val m = Regex("Suggested exit node:\\s*([^.\\s]+(?:\\.[^.\\s]+)*?)\\.?\\s*$", RegexOption.MULTILINE).find(text)
        return m?.groupValues?.get(1)?.trim()
    }

    suspend fun whois(ip: String): WhoisInfo? {
        if (ip.isBlank()) return null
        val result = shell.exec(ShellCommand.argv("tailscale", "whois", "--", ip))
        if (!result.ok) return null
        val text = result.out.joinToString("\n")
        if (text.isBlank() || text.contains("not found", ignoreCase = true)) return null
        val mName = Regex("Machine:\\s*\\n\\s*Name:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        val mId = Regex("Machine:[\\s\\S]*?ID:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        val mAddr = Regex("Addresses:\\s*\\[([^\\]]+)\\]").find(text)?.groupValues?.get(1)
        val uName = Regex("User:\\s*\\n\\s*Name:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        val uId = Regex("User:[\\s\\S]*?ID:\\s*(\\S+)").find(text)?.groupValues?.get(1)
        if (mName == null && uName == null) return null
        return WhoisInfo(mName, mId, mAddr, uName, uId)
    }

    suspend fun dnsStatus(): DnsStatus {
        val text = shell.execText("tailscale dns status")
        if (text.isBlank()) return DnsStatus()
        val tsDnsEnabled = text.contains("Tailscale DNS: enabled", ignoreCase = true)
        val magicDnsEnabled = Regex("MagicDNS:\\s*enabled", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val magicSuffix = Regex("suffix\\s*=\\s*([^)\\s]+)").find(text)?.groupValues?.get(1)
        val deviceName = Regex("can reach this device at\\s+(\\S+?)\\.?\\s*$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        return DnsStatus(tailscaleDnsEnabled = tsDnsEnabled, magicDnsEnabled = magicDnsEnabled,
            magicDnsSuffix = magicSuffix, deviceDnsName = deviceName)
    }

    suspend fun setSshServer(enabled: Boolean): CommandResult =
        shell.exec("tailscale set --ssh=$enabled")

    suspend fun resolveBinaries(): BinaryPaths = BinaryPaths(
        tailscale = shell.resolveExecutable("tailscale"),
        tailscaled = shell.resolveExecutable("tailscaled"),
        service = shell.resolveExecutable("tailscaled.service"),
    )
}

data class BinaryPaths(
    val tailscale: String?,
    val tailscaled: String?,
    val service: String?,
)
