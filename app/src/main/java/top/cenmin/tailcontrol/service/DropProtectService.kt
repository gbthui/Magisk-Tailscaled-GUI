package top.cenmin.tailcontrol.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import top.cenmin.tailcontrol.MainActivity
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.DropRepository
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.core.model.DropRequest
import top.cenmin.tailcontrol.core.model.ReceiverPhase
import javax.inject.Inject

/** A bounded Android observer/controller. The root owner supplies the lock and expiring lease. */
@AndroidEntryPoint
class DropProtectService : Service() {
    companion object {
        const val CHANNEL_ID = "TAILCONTROL_DROP_PROTECT"
        const val NOTIFICATION_ID = 3001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        // Retained for old callers. Configuration is always read from the latest revision.
        const val EXTRA_PATH = "EXTRA_PATH"
        const val EXTRA_BEHAVIOR = "EXTRA_BEHAVIOR"
        @Volatile var controllerActive = false
            private set
    }

    @Inject lateinit var prefs: PreferencesRepository
    @Inject lateinit var dropRepo: DropRepository
    @Inject lateinit var sessionGuard: ReceiverSessionGuard
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var latestStartId = 0
    @Volatile private var timedOut = false
    @Volatile private var lastRequest: DropRequest? = null

    override fun onCreate() {
        super.onCreate()
        controllerActive = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "TailControl ${getString(R.string.drop_label)}", NotificationManager.IMPORTANCE_LOW))
        // Exactly one consumer performs start/stop/reconfigure. No separate cleanup launch can race it.
        scope.launch {
            for (ignored in wake) {
                if (timedOut) break
                val requestId = latestStartId
                if (requestId == 0) continue
                try {
                    val request = prefs.dropRequest.first()
                    lastRequest = request
                    val observed = if (request.config.enabled) dropRepo.ensureRunning(request.config)
                        else dropRepo.stopFileGet()
                    when (observed.phase) {
                        ReceiverPhase.PAUSED, ReceiverPhase.BLOCKED -> {
                            if (request.config.enabled) prefs.disableDropIfRevision(request.revision)
                            showStatus(getString(R.string.receiver_attention, observed.reason))
                            ReceiverNotice.show(this@DropProtectService, getString(R.string.receiver_attention, observed.reason))
                        }
                        ReceiverPhase.RUNNING -> {
                            showStatus(getString(R.string.Daemon_running))
                            ReceiverNotice.clear(this@DropProtectService)
                        }
                        ReceiverPhase.UNKNOWN -> showStatus(getString(R.string.receiver_unconfirmed, observed.reason))
                        ReceiverPhase.STARTING, ReceiverPhase.STOPPING -> showStatus(getString(R.string.loading))
                        ReceiverPhase.STOPPED -> Unit
                    }
                    val latest = prefs.dropRequest.first()
                    if (!latest.config.enabled && (observed.quiescent || observed.phase == ReceiverPhase.BLOCKED)) {
                        // The UI's enabled flag is user intent; do not report stopped for UNKNOWN.
                        withContext(Dispatchers.Main) {
                            if (!timedOut && latestStartId == requestId) {
                                if (stopSelfResult(requestId)) stopForeground(STOP_FOREGROUND_REMOVE)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropRepo.appendLog("Receiver control failed: ${e.message}")
                    Timber.e(e, "receiver control failure")
                    // No new consumer on an exception. Without successful renewal, the root lease expires.
                }
            }
        }
        scope.launch { prefs.dropRequest.distinctUntilChanged().collect { wake.trySend(Unit) } }
        scope.launch { while (isActive) { delay(3000); wake.trySend(Unit) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (timedOut) { stopSelf(startId); return START_NOT_STICKY }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.loading)))
            wake.trySend(Unit)
        } catch (e: Exception) {
            dropRepo.appendLog("Cannot enter foreground: ${e.message}")
            finishForSystemLimit()
        }
        // Do not evade dataSync time limits by repeatedly resurrecting this service.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { finishForSystemLimit() }

    private fun finishForSystemLimit() {
        timedOut = true
        val generation = dropRepo.status.value.generation
        val revision = lastRequest?.revision
        scope.cancel() // Stop issuing renewals immediately, even if su is currently unavailable.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        runCatching { sessionGuard.block(revision) }
            .onFailure { Timber.e(it, "Unable to persist receiver system-stop marker") }
        dropRepo.appendLog(getString(R.string.receiver_timed_out))
        ReceiverNotice.show(this, getString(R.string.receiver_timed_out))
        // Not in the service's cancelled scope. Generation and revision fence stale cleanup.
        val cleanup = CoroutineScope(Dispatchers.IO + SupervisorJob())
        cleanup.launch {
            try {
                if (revision != null) prefs.disableDropIfRevision(revision)
                dropRepo.requestStop(generation)
            } finally { cleanup.cancel() }
        }
        // stopSelf was already called before any persistence or root cleanup work.
    }

    override fun onDestroy() {
        controllerActive = false
        scope.cancel()
        wake.close()
        // Unexpected process/service destruction stops renewals. Root cleanup has a 20s lease bound.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("show_drop_screen", true)
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("TailControl Drop").setContentText(text)
            .setOngoing(true).setContentIntent(pi).build()
    }

    private fun showStatus(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
