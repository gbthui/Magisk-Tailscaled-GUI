package top.cenmin.tailcontrol

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import top.cenmin.tailcontrol.core.data.PreferencesRepository
import top.cenmin.tailcontrol.service.ReceiverForegroundRecovery
import top.cenmin.tailcontrol.ui.component.UpdateDialog
import top.cenmin.tailcontrol.ui.nav.DropNavigationRequests
import top.cenmin.tailcontrol.ui.nav.TailNavGraph
import top.cenmin.tailcontrol.ui.screen.settings.UpdateViewModel
import top.cenmin.tailcontrol.ui.theme.TailControlTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: PreferencesRepository
    @Inject lateinit var receiverRecovery: ReceiverForegroundRecovery
    private lateinit var dropNavigation: DropNavigationRequests
    private var recoveryJob: Job? = null
    private var rootGranted = false
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dropNavigation = DropNavigationRequests(savedInstanceState?.getLong("pending_drop_navigation")?.takeIf { it > 0 })
        acceptNavigationIntent(intent)
        rootGranted = Shell.getShell().isRoot
        if (!rootGranted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.get_root_failed_title).setMessage(R.string.get_root_failed_msg)
                .setCancelable(false).setPositiveButton(R.string.exit) { _, _ -> finish() }.show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val dynamic by prefs.dynamicColorEnabled.collectAsState(initial = true)
            TailControlTheme(dynamicColor = dynamic) { MainActivityContent() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNavigationIntent(intent)
    }

    private fun acceptNavigationIntent(source: Intent) {
        if (source.getBooleanExtra(DropNavigationRequests.EXTRA_SHOW_DROP, false)) {
            dropNavigation.request()
            // Consume the extra, not only its navigation effect, so rotation does not replay it.
            source.removeExtra(DropNavigationRequests.EXTRA_SHOW_DROP)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        dropNavigation.pending.value?.let { outState.putLong("pending_drop_navigation", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!rootGranted) return
        recoveryJob?.cancel()
        recoveryJob = lifecycleScope.launch {
            try {
                receiverRecovery.reconcile { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Timber.w(e, "Unable to inspect receiving on foreground return") }
        }
    }

    override fun onPause() {
        recoveryJob?.cancel()
        super.onPause()
    }

    @Composable
    fun MainActivityContent() {
        val updateViewModel: UpdateViewModel = viewModel()
        val silentUpdate by updateViewModel.silentUpdateResult.collectAsState()
        val dropRequest by dropNavigation.pending.collectAsState()
        var showDialog by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { updateViewModel.silentCheckUpdateIfNeeded() }
        LaunchedEffect(silentUpdate) { if (silentUpdate != null) showDialog = true }
        if (showDialog && silentUpdate != null) {
            UpdateDialog(result = silentUpdate,
                onDismiss = { updateViewModel.consumeSilentUpdate() },
                onOpenDownloadPage = { updateViewModel.openDownloadPage() })
        }
        TailNavGraph(openDropRequest = dropRequest, onDropOpened = dropNavigation::consumed)
    }
}
