package top.cenmin.tailcontrol.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import top.cenmin.tailcontrol.MainActivity

/** A non-foreground notice survives the end of a paused/timed-out receiving session. */
object ReceiverNotice {
    private const val CHANNEL = "TAILCONTROL_RECEIVER_ATTENTION"
    private const val ID = 3002
    fun show(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "TailControl", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("show_drop_screen", true)
        }
        val pi = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("TailControl")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true).setOngoing(false).setAutoCancel(true).setContentIntent(pi).build())
    }
    fun clear(context: Context) { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) }
}
