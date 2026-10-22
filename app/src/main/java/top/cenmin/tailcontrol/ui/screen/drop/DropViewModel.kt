package top.cenmin.tailcontrol.ui.screen.drop

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.DropRepository
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.model.BackendState
import top.cenmin.tailcontrol.core.model.ConflictBehavior
import top.cenmin.tailcontrol.core.model.DropConfig
import top.cenmin.tailcontrol.core.model.LegacyReceiver
import top.cenmin.tailcontrol.core.model.ReceiverPhase
import top.cenmin.tailcontrol.service.ReceiverSessionGuard
import top.cenmin.tailcontrol.service.DropProtectService
import javax.inject.Inject

data class DropUiState(
    val config: DropConfig = DropConfig(),
    val pingAddress: String = "",
    val tailscaleState: BackendState = BackendState.Unknown,
    val daemonOutput: String = "",
    val pingDialogOpen: Boolean = false,
    val pingOutput: String = "",
    val isPinging: Boolean = false,
    val killDialogOpen: Boolean = false,
    val processCount: Int = 0,
    val errorToast: String? = null,
    val fileCommandSupported: Boolean = true,
    val pausedGeneration: String? = null,
    val legacyPids: String = "",
)

@HiltViewModel
class DropViewModel @Inject constructor(
    private val app: Application,
    private val prefs: PreferencesRepository,
    private val tailRepo: TailscaleRepository,
    private val dropRepo: DropRepository,
    private val sessionGuard: ReceiverSessionGuard,
) : ViewModel() {
    private val _ui = MutableStateFlow(DropUiState())
    val ui: StateFlow<DropUiState> = _ui.asStateFlow()
    val receiverStatus = dropRepo.status
    private var pingJob: Job? = null
    private var legacySnapshot = emptyList<LegacyReceiver>()
    val daemonOutput: StateFlow<String> = dropRepo.output.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch {
            combine(prefs.dropConfig, prefs.pingAddress) { c, p -> c to p }
                .collect { (config, ping) -> _ui.update { it.copy(config = config, pingAddress = ping) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val supported = tailRepo.isFileCommandSupported()
            _ui.update { it.copy(fileCommandSupported = supported) }
            dropRepo.inspect()
        }
        refreshTailscaleState()
    }

    fun refreshTailscaleState() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = tailRepo.fetchStatus().backendState
            _ui.update { it.copy(tailscaleState = state) }
        }
    }

    fun setEnabled(target: Boolean) {
        viewModelScope.launch {
            try {
                if (target) {
                    val current = dropRepo.inspect()
                    if (current.phase == ReceiverPhase.PAUSED && current.generation != null) {
                        _ui.update { it.copy(pausedGeneration = current.generation) }
                        return@launch
                    }
                    val supported = tailRepo.isFileCommandSupported()
                    _ui.update { it.copy(fileCommandSupported = supported) }
                    if (!supported) {
                        _ui.update { it.copy(errorToast = app.getString(R.string.taildrop_cli_unavailable)) }
                        return@launch
                    }
                    if (tailRepo.fetchStatus().backendState !is BackendState.Running) {
                        _ui.update { it.copy(errorToast = "Tailscale not running") }
                        return@launch
                    }
                }
                if (target) sessionGuard.allowExplicitStart()
                prefs.setDropEnabled(target)
                val request = prefs.dropRequest.first()
                val intent = Intent(app, DropProtectService::class.java).apply {
                    action = if (target) DropProtectService.ACTION_START else DropProtectService.ACTION_STOP
                }
                try {
                    if (target) app.startForegroundService(intent) else app.startService(intent)
                } catch (e: Exception) {
                    if (target) prefs.disableDropIfRevision(request.revision)
                    _ui.update { it.copy(errorToast = e.message ?: "Unable to request receiver service") }
                    if (!target) dropRepo.stopFileGet()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(errorToast = e.message) } }
        }
    }

    fun dismissResume() { _ui.update { it.copy(pausedGeneration = null) } }
    fun confirmResume() {
        val token = _ui.value.pausedGeneration ?: return
        dismissResume()
        viewModelScope.launch {
            if (dropRepo.acknowledgePause(token)) setEnabled(true)
            else _ui.update { it.copy(errorToast = app.getString(R.string.receiver_resume_failed)) }
        }
    }

    // The service observes dropRequest revisions; running path/policy changes trigger reconciliation.
    fun setPath(path: String) { viewModelScope.launch { prefs.setDropPath(path) } }
    fun setConflict(behavior: ConflictBehavior) { viewModelScope.launch { prefs.setConflict(behavior) } }
    fun setPingAddress(addr: String) {
        _ui.update { it.copy(pingAddress = addr) }
        viewModelScope.launch { prefs.setPingAddress(addr) }
    }
    fun startPing() {
        val addr = _ui.value.pingAddress
        if (addr.isBlank()) return
        pingJob?.cancel()
        _ui.update { it.copy(pingDialogOpen = true, pingOutput = "", isPinging = true) }
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                tailRepo.ping(addr).collect { line -> _ui.update { it.copy(pingOutput = it.pingOutput + line + "\n") } }
            } finally { _ui.update { it.copy(isPinging = false) } }
        }
    }
    fun stopPing() { pingJob?.cancel(); pingJob = null; _ui.update { it.copy(isPinging = false) } }
    fun closePingDialog() { stopPing(); _ui.update { it.copy(pingDialogOpen = false) } }

    fun openKillDialog() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                legacySnapshot = dropRepo.legacyReceivers()
                _ui.update { it.copy(killDialogOpen = true, processCount = legacySnapshot.size,
                    legacyPids = legacySnapshot.joinToString(", ") { receiver -> receiver.pid.toString() }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.update { it.copy(errorToast = e.message) } }
        }
    }
    fun confirmKillProcesses() {
        val snapshot = legacySnapshot.toList()
        _ui.update { it.copy(killDialogOpen = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val stopped = dropRepo.stopLegacyReceivers(snapshot)
            if (!stopped) _ui.update { it.copy(errorToast = app.getString(R.string.receiver_cleanup_incomplete)) }
            dropRepo.inspect()
        }
    }
    fun dismissKillDialog() { legacySnapshot = emptyList(); _ui.update { it.copy(killDialogOpen = false) } }
    fun consumeError() { _ui.update { it.copy(errorToast = null) } }
}
