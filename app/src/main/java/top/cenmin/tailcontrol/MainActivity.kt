package top.cenmin.tailcontrol

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.ui.component.UpdateDialog
import top.cenmin.tailcontrol.ui.nav.TailNavGraph
import top.cenmin.tailcontrol.ui.screen.settings.UpdateViewModel
import top.cenmin.tailcontrol.ui.theme.TailControlTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!Shell.getShell().isRoot) {
            AlertDialog.Builder(this)
                .setTitle(R.string.get_root_failed_title)
                .setMessage(R.string.get_root_failed_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.exit) { _, _ -> finish() }
                .show()
            return
        }

        setContent {
            val dynamic by prefs.dynamicColorEnabled.collectAsState(initial = true)
            TailControlTheme(dynamicColor = dynamic) {
                MainActivityContent()
            }
        }
    }

    @Composable
    fun MainActivityContent() {
        val updateViewModel: UpdateViewModel = viewModel()
        val silentUpdate by updateViewModel.silentUpdateResult.collectAsState()
        var showDialog by remember { mutableStateOf(false) }

        // 启动时静默检查
        LaunchedEffect(Unit) {
            lifecycleScope.launch {
                updateViewModel.silentCheckUpdateIfNeeded()
            }
        }

        // 发现更新时显示对话框
        LaunchedEffect(silentUpdate) {
            if (silentUpdate != null) {
                showDialog = true
            }
        }
        if (showDialog && silentUpdate != null) {
            UpdateDialog(
                result = silentUpdate,
                onDismiss = {
                    updateViewModel.consumeSilentUpdate()
                },
                onOpenDownloadPage = {
                    updateViewModel.openDownloadPage()
                }
            )
        }

        TailNavGraph()
    }
}
