package top.cenmin.tailcontrol.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.DropRepository
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.core.model.ReceiverRecoveryAction
import top.cenmin.tailcontrol.core.model.ReceiverRecoveryPolicy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiverForegroundRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository,
    private val dropRepo: DropRepository,
    private val systemStop: ReceiverSessionGuard,
) {
    /** Called from a RESUMED activity. Do not start from Application.onCreate or a background timer. */
    suspend fun reconcile(stillVisible: () -> Boolean) {
        val request = prefs.dropRequest.first()
        if (!request.config.enabled || DropProtectService.controllerActive) return
        val status = dropRepo.inspect()
        val latest = prefs.dropRequest.first()
        if (latest != request || !stillVisible()) return
        when (ReceiverRecoveryPolicy.decide(request.config.enabled, DropProtectService.controllerActive,
            systemStop.blocks(request.revision), status.phase)) {
            ReceiverRecoveryAction.NONE -> Unit
            ReceiverRecoveryAction.REQUIRE_REVIEW -> {
                if (prefs.disableDropIfRevision(request.revision)) {
                    ReceiverNotice.show(context, context.getString(R.string.receiver_open_app_to_resume))
                    dropRepo.appendLog("Receiving requires review; automatic foreground recovery was not attempted")
                }
            }
            ReceiverRecoveryAction.RESTART_CONTROLLER -> {
                try {
                    // Root start is still idempotent and generation/lock-fenced. Recreate only
                    // the Android controller; it will adopt or safely settle the existing owner.
                    context.startForegroundService(Intent(context, DropProtectService::class.java).apply {
                        action = DropProtectService.ACTION_START
                    })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    prefs.disableDropIfRevision(request.revision)
                    dropRepo.appendLog("Unable to recover receiving: ${e.message}")
                    ReceiverNotice.show(context, context.getString(R.string.receiver_open_app_to_resume))
                }
            }
        }
    }
}
