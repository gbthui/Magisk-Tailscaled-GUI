package top.cenmin.tailcontrol.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.share.FileShareActivity
import top.cenmin.tailcontrol.share.UploadSessions

class FileTransferService : Service() {
    companion object {
        const val CHANNEL_ID = "TAILSCALE_FILE_CHANNEL"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var isForegroundStarted = false
    private var latestStartId = 0
    private lateinit var builder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "Tailscale ${getString(R.string.file_transfer)}", NotificationManager.IMPORTANCE_LOW))
        val openIntent = Intent(this, FileShareActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Tailscale ${getString(R.string.file_transfer)}")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        // Notification work is isolated from the upload's stdin writer. A notification failure
        // cannot become a writeFailure or truncate an otherwise healthy transfer.
        scope.launch { UploadSessions.progress.collect { renderLatest() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            UploadSessions.cancelForTimeout()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!isForegroundStarted) {
            try {
                val current = UploadSessions.progress.value.lastOrNull()
                builder.setContentText(current?.text ?: getString(R.string.waiting))
                    .setProgress(100, current?.percent?.coerceIn(0, 100) ?: 0, current?.percent?.let { it < 0 } ?: true)
                // Required even without POST_NOTIFICATIONS permission and if a tiny upload
                // completed before Android delivered its one startup request.
                startForeground(NOTIFICATION_ID, builder.build())
                isForegroundStarted = true
            } catch (e: Exception) {
                Timber.e(e, "Unable to enter upload foreground service")
                UploadSessions.cancelForTimeout()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        renderLatest()
        return START_NOT_STICKY
    }

    private fun renderLatest() {
        if (!isForegroundStarted) return
        // Read current state rather than acting on a queued older 'empty' emission.
        val current = UploadSessions.progress.value.lastOrNull()
        if (current == null) {
            if (stopSelfResult(latestStartId)) {
                isForegroundStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            builder.setContentText(current.text)
                .setProgress(100, current.percent.coerceIn(0, 100), current.percent < 0)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Timber.w(e, "Unable to update upload notification; transfer continues")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        UploadSessions.cancelForTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
