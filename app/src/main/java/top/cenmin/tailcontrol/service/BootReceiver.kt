package top.cenmin.tailcontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var prefs: PreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        // Credential-encrypted DataStore is not available during locked boot.
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON")) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val request = prefs.dropRequest.first()
                if (!request.config.enabled) return@launch
                if (Build.VERSION.SDK_INT >= 35) {
                    // Android 15 forbids dataSync foreground-service starts from BOOT_COMPLETED.
                    prefs.disableDropIfRevision(request.revision)
                    ReceiverNotice.show(context, context.getString(R.string.receiver_boot_paused))
                    Timber.w("Taildrop boot restore paused: open the app to start a receiving session on Android 15+")
                    return@launch
                }
                try {
                    context.startForegroundService(Intent(context, DropProtectService::class.java).apply {
                        action = DropProtectService.ACTION_START
                    })
                } catch (e: Exception) {
                    prefs.disableDropIfRevision(request.revision)
                    Timber.e(e, "Cannot restore Taildrop foreground service")
                }
            } finally { pending.finish() }
        }
    }
}
