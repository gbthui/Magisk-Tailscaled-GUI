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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import top.cenmin.tailcontrol.MainActivity
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.DropRepository
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.model.BackendState
import top.cenmin.tailcontrol.core.model.ConflictBehavior
import javax.inject.Inject

@AndroidEntryPoint
class DropProtectService : Service() {
    companion object {
        const val CHANNEL_ID = "TAILCONTROL_DROP_PROTECT"
        const val NOTIFICATION_ID = 3001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PATH = "EXTRA_PATH"
        const val EXTRA_BEHAVIOR = "EXTRA_BEHAVIOR"
    }

    @Inject lateinit var prefs: PreferencesRepository
    @Inject lateinit var dropRepo: DropRepository
    @Inject lateinit var tailRepo: TailscaleRepository
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter foreground before any root, DataStore, or capability check can block.
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.loading)))
        when (intent?.action) {
            ACTION_START -> startProtect(intent.getStringExtra(EXTRA_PATH),
                ConflictBehavior.fromCli(intent.getStringExtra(EXTRA_BEHAVIOR)))
            ACTION_STOP -> stopProtect()
            else -> scope.launch {
                val cfg = prefs.dropConfig.first()
                if (cfg.enabled) startProtect(cfg.path, cfg.conflict) else stopProtect()
            }
        }
        return START_STICKY
    }

    private fun startProtect(pathArg: String?, behavior: ConflictBehavior) {
        val path = pathArg ?: "/sdcard/Download/TailDrop/"
        val previous = loopJob
        previous?.cancel()
        loopJob = scope.launch {
            previous?.join()
            // This gate also covers Quick Settings, boot, and service restarts.
            if (!tailRepo.isFileCommandSupported()) {
                dropRepo.appendLog(getString(R.string.taildrop_cli_unavailable))
                prefs.setDropEnabled(false)
                stopProtect()
                return@launch
            }
            val status = tailRepo.fetchStatus()
            dropRepo.appendLog(getString(R.string.status) + ": " + getString(status.backendState.labelRes))
            if (status.backendState !is BackendState.Running) {
                dropRepo.appendLog(getString(R.string.drop_stop_due_to_tail))
                stopProtect()
                return@launch
            }
            // Each receiver has one verified PID; do not kill unrelated tailscale clients by count.
            while (isActive) {
                try {
                    val (storedPath, storedBehavior, pid) = prefs.loadDropRuntime()
                    val needRestart = storedPath != path || storedBehavior != behavior
                    if (needRestart) dropRepo.stopFileGet()
                    val alive = !needRestart && pid != null && pid > 0 && dropRepo.isProcessAlive(pid)
                    if (!alive) {
                        val receiverPid = dropRepo.startFileGet(path, behavior)
                        if (receiverPid != null) {
                            dropRepo.appendLog(getString(R.string.msg_file_get_success, receiverPid, receiverPid))
                            updateNotification(getString(R.string.Daemon_running))
                        } else {
                            dropRepo.appendLog(getString(R.string.msg_file_get_failed_no_ppid))
                            updateNotification(getString(R.string.msg_file_get_failed_no_ppid))
                        }
                    } else {
                        updateNotification(getString(R.string.Daemon_running))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropRepo.appendLog(getString(R.string.msg_file_get_unexpected, e.message.orEmpty()))
                    Timber.e(e, "drop loop failure")
                }
                delay(3000)
            }
        }
    }

    private fun stopProtect() {
        val previous = loopJob
        loopJob = null
        previous?.cancel()
        scope.launch {
            previous?.join()
            // Finish receiver cleanup before stopSelf/onDestroy cancels this scope.
            withContext(NonCancellable) { dropRepo.stopFileGet() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runBlocking {
            if (!prefs.dropConfig.first().enabled) stopProtect()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "TailControl ${getString(R.string.drop_label)}", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("show_drop_screen", true)
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("TailControl Drop")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
