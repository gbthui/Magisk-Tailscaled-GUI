package top.cenmin.tailcontrol.share

import android.net.Uri
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.ui.component.DeviceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileShareScreen(
    uri: Uri,
    onClose: () -> Unit,
    viewModel: FileShareViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(uri) { viewModel.bind(uri) }
    val progress by animateFloatAsState(
        targetValue = (ui.progressPercent.coerceIn(0, 100)) / 100f,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "progress",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.fileName.ifBlank { stringResource(R.string.share_label) }) },
                actions = {
                    if (!ui.transferring) {
                        TextButton(onClick = onClose) {
                            Text(stringResource(R.string.close))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            R.string.transfer_status,
                            ui.progressText.ifBlank { stringResource(R.string.waiting) },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (ui.transferring && ui.progressPercent < 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (ui.transferring) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = viewModel::cancelTransfer) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.device_list_autofresh, ui.countdown))
                Button(onClick = viewModel::refreshOnce) { Text(stringResource(R.string.refresh)) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.peers, key = { it.name }) { peer ->
                    Box(Modifier.fillMaxWidth()) {
                        DeviceCard(device = peer, onClick = {
                            if (!ui.transferring && peer.online) viewModel.send(peer)
                        })
                    }
                }
            }
        }
    }

    if (ui.showNotRunningDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissNotRunning,
            title = { Text("Tailscale ${stringResource(R.string.status_service_stopped)}") },
            text = { Text(stringResource(R.string.tailscale_not_running)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissNotRunning()
                    viewModel.startTailscale()
                }) { Text(stringResource(R.string.start_tailscale)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNotRunning) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (ui.showOfflineDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOffline,
            title = { Text("Tailscale ${stringResource(R.string.offline_now)}") },
            text = { Text(stringResource(R.string.tailnet_unreachable)) },
            confirmButton = { TextButton(onClick = viewModel::dismissOffline) { Text(stringResource(R.string.done)) } },
        )
    }
}
