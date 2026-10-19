package top.cenmin.tailcontrol.ui.screen.settings.experimental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
            prefs.healthBannerDisabled.collect {
                _ui.value = _ui.value.copy(healthBannerDisabled = it)
            }
        }
    }

    fun setAltRepoOptimization(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !altRepoRouteManager.isAvailable()) {
                _ui.value = _ui.value.copy(routeSyncLoading = false, routeSyncSuccess = false,
                    routeSyncMessage = "tailscaled.tun is unavailable. Install an AltRepo-compatible module before enabling this feature.")
                return@launch
            }
            prefs.setAltRepoOptimizationEnabled(enabled)
            if (enabled) {
                syncRoutes()
            } else {
                _ui.value = _ui.value.copy(routeSyncLoading = true, routeSyncMessage = null)
                val result = altRepoRouteManager.removeRoutes()
                _ui.value = _ui.value.copy(routeSyncLoading = false,
                    routeSyncMessage = result.message, routeSyncSuccess = result.ok)
            }
        }
    }

    fun setHealthBannerDisabled(disabled: Boolean) {
        viewModelScope.launch { prefs.setHealthBannerDisabled(disabled) }
    }

    fun syncRoutes() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(routeSyncLoading = true, routeSyncMessage = null)
            val result = altRepoRouteManager.syncRoutes()
            val hasBackup = altRepoRouteManager.hasBackup()
            _ui.value = _ui.value.copy(routeSyncLoading = false, routeSyncMessage = result.message,
                routeSyncSuccess = result.ok, hasRouteBackup = hasBackup)
        }
    }

    fun restoreBackup() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(routeSyncLoading = true, routeSyncMessage = null)
            val result = altRepoRouteManager.restoreBackup()
            val hasBackup = altRepoRouteManager.hasBackup()
            if (result.ok) prefs.setAltRepoOptimizationEnabled(false)
            _ui.value = _ui.value.copy(routeSyncLoading = false, routeSyncMessage = result.message,
                routeSyncSuccess = result.ok, hasRouteBackup = hasBackup)
        }
    }

    fun clearSyncMessage() { _ui.value = _ui.value.copy(routeSyncMessage = null) }
    private suspend fun refreshBackupState() {
        _ui.value = _ui.value.copy(hasRouteBackup = altRepoRouteManager.hasBackup())
    }
}
