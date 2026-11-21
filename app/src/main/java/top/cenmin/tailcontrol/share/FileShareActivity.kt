package top.cenmin.tailcontrol.share

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.AndroidEntryPoint
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.ui.theme.TailControlTheme

@AndroidEntryPoint
class FileShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!Shell.getShell().isRoot) {
            AlertDialog.Builder(this)
                .setTitle(R.string.get_root_failed_title)
                .setMessage(R.string.get_root_failed_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.exit) { _, _ -> finish() }
                .show()
            return
        }

        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        if (uri == null) {
            finish()
            return
        }

        setContent {
            TailControlTheme {
                FileShareScreen(
                    uri = uri,
                    onClose = { finishAffinity() },
                )
            }
        }
    }
}
