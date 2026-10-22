package top.cenmin.tailcontrol.service.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.DropRepository
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.model.BackendState
import top.cenmin.tailcontrol.core.model.ReceiverPhase
import top.cenmin.tailcontrol.service.ReceiverSessionGuard
import top.cenmin.tailcontrol.service.DropProtectService
import javax.inject.Inject

@AndroidEntryPoint
class DropTileService : TileService() {
    @Inject lateinit var prefs: PreferencesRepository
    @Inject lateinit var tailRepo: TailscaleRepository
    @Inject lateinit var dropRepo: DropRepository
    @Inject lateinit var sessionGuard: ReceiverSessionGuard
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartListening() {
        scope.launch {
            val enabled = prefs.dropConfig.first().enabled
            withContext(Dispatchers.Main) { updateTile(enabled) }
        }
    }

    @Suppress("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, DummyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
        scope.launch {
            try {
                // This read is only for checks. The actual toggle below is a DataStore transaction.
                if (!prefs.dropConfig.first().enabled) {
                    if (dropRepo.inspect().phase == ReceiverPhase.PAUSED) {
                        toast(getString(R.string.receiver_open_app_to_resume)); return@launch
                    }
                    if (tailRepo.fetchStatus().backendState !is BackendState.Running || !tailRepo.isFileCommandSupported()) {
                        toast(getString(R.string.taildrop_cli_unavailable)); return@launch
                    }
                }
                val target = prefs.toggleDropEnabled()
                if (target) sessionGuard.allowExplicitStart()
                val request = prefs.dropRequest.first()
                val svc = Intent(this@DropTileService, DropProtectService::class.java).apply {
                    action = if (target) DropProtectService.ACTION_START else DropProtectService.ACTION_STOP
                }
                try {
                    if (target) startForegroundService(svc) else startService(svc)
                } catch (e: Exception) {
                    if (target) prefs.disableDropIfRevision(request.revision) else dropRepo.stopFileGet()
                    toast(e.message ?: "Receiver service unavailable")
                }
                val enabled = prefs.dropConfig.first().enabled
                withContext(Dispatchers.Main) { updateTile(enabled) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Receiver control failed") }
        }
    }

    private suspend fun toast(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@DropTileService, message, Toast.LENGTH_LONG).show()
    }
    private fun updateTile(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.drop_label)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
