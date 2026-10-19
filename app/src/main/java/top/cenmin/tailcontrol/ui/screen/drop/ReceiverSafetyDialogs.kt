package top.cenmin.tailcontrol.ui.screen.drop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.cenmin.tailcontrol.R

@Composable
fun ReceiverSafetyDialogs(ui: DropUiState, viewModel: DropViewModel) {
    if (ui.pausedGeneration != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissResume,
            title = { Text(stringResource(R.string.receiver_resume_title)) },
            text = { Text(stringResource(R.string.receiver_resume_warning)) },
            confirmButton = { TextButton(onClick = viewModel::confirmResume) { Text(stringResource(R.string.receiver_resume_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissResume) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (ui.killDialogOpen) {
        AlertDialog(
            onDismissRequest = viewModel::dismissKillDialog,
            title = { Text(stringResource(R.string.receiver_legacy_title)) },
            text = { Text(if (ui.processCount == 0) stringResource(R.string.receiver_no_legacy)
                else stringResource(R.string.receiver_legacy_warning, ui.legacyPids)) },
            confirmButton = {
                TextButton(onClick = if (ui.processCount == 0) viewModel::dismissKillDialog else viewModel::confirmKillProcesses) {
                    Text(stringResource(if (ui.processCount == 0) R.string.done else R.string.confirm))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissKillDialog) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
