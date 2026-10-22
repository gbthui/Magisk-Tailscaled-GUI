package top.cenmin.tailcontrol.ui.screen.settings.experimental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.cenmin.tailcontrol.core.manager.AltRepoRouteManager
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import javax.inject.Inject

data class ExperimentalUiState(
    val altRepoOptimization: Boolean = false,
    val healthBannerDisabled: Boolean = false,
    val routeSyncLoading: Boolean = false,
    val routeSyncMessage: String? = null,
    val routeSyncSuccess: Boolean = true,
    val hasRouteBackup: Boolean = false,
)

@HiltViewModel
class ExperimentalViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val altRepoRouteManager: AltRepoRouteManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(ExperimentalUiState())
    val ui: StateFlow<ExperimentalUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.altRepoOptimizationEnabled.collect { enabled ->
                _ui.value = _ui.value.copy(altRepoOptimization = enabled)
                if (enabled) refreshBackupState()
            }
        }
        viewModelScope.launch {
            prefs.healthBannerDisabled.collect { _ui.value = _ui.value.copy(healthBannerDisabled = it) }
        }
    }

    fun setAltRepoOptimization(enabled: Boolean) = runRouteOperation {
        if (enabled) {
            if (!altRepoRouteManager.isAvailable()) {
                return@runRouteOperation AltRepoRouteManager.SyncResult(false,
                    "tailscaled.tun is unavailable. Install an AltRepo-compatible module before enabling this feature.")
            }
            prefs.setAltRepoOptimizationEnabled(true)
            altRepoRouteManager.syncRoutes()
        } else {
            val result = altRepoRouteManager.removeRoutes()
            // Do not hide the controls while our owned blocks remain after a failed edit.
            // Runtime stop failure is reported separately even if the on-disk cleanup succeeded.
            if (result.scriptsUpdated) prefs.setAltRepoOptimizationEnabled(false)
            result
        }
    }

    fun setHealthBannerDisabled(disabled: Boolean) {
        viewModelScope.launch { prefs.setHealthBannerDisabled(disabled) }
    }
    fun syncRoutes() = runRouteOperation { altRepoRouteManager.syncRoutes() }
    fun restoreBackup() = runRouteOperation {
        val result = altRepoRouteManager.restoreBackup()
        if (result.ok) prefs.setAltRepoOptimizationEnabled(false)
        result
    }

    private fun runRouteOperation(operation: suspend () -> AltRepoRouteManager.SyncResult) {
        if (_ui.value.routeSyncLoading) return
        _ui.value = _ui.value.copy(routeSyncLoading = true, routeSyncMessage = null)
        viewModelScope.launch {
            try {
                val result = operation()
                val hasBackup = altRepoRouteManager.hasBackup()
                _ui.value = _ui.value.copy(routeSyncMessage = result.message,
                    routeSyncSuccess = result.ok, hasRouteBackup = hasBackup)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _ui.value = _ui.value.copy(routeSyncSuccess = false, routeSyncMessage = e.message ?: "TUN operation failed")
            } finally { _ui.value = _ui.value.copy(routeSyncLoading = false) }
        }
    }

    fun clearSyncMessage() { _ui.value = _ui.value.copy(routeSyncMessage = null) }
    private suspend fun refreshBackupState() {
        _ui.value = _ui.value.copy(hasRouteBackup = altRepoRouteManager.hasBackup())
    }
}
