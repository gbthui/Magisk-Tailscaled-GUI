package top.cenmin.tailcontrol.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.model.BackendState
import top.cenmin.tailcontrol.core.model.TailscaleDevice
import top.cenmin.tailcontrol.core.shell.ProcessIo
import top.cenmin.tailcontrol.core.shell.RootShell
import top.cenmin.tailcontrol.core.shell.ShellCommand
import top.cenmin.tailcontrol.service.FileTransferService
import java.io.IOException
import javax.inject.Inject

data class FileShareUiState(
    val fileUri: Uri? = null,
    val fileName: String = "",
    val totalBytes: Long = -1,
    val peers: List<TailscaleDevice> = emptyList(),
    val countdown: Int = 0,
    val transferring: Boolean = false,
    val transferFinished: Boolean = false,
    val progressPercent: Int = 0,
    val progressText: String = "",
    val showNotRunningDialog: Boolean = false,
    val showOfflineDialog: Boolean = false,
    val cancelDialogOpen: Boolean = false,
    val processingDialog: Boolean = false,
    val doneDialog: Boolean = false,
    val tailscaleState: BackendState = BackendState.Unknown,
)

@HiltViewModel
class FileShareViewModel @Inject constructor(
    private val app: Application,
    private val tailRepo: TailscaleRepository,
    private val shell: RootShell,
) : ViewModel() {
    private val _ui = MutableStateFlow(FileShareUiState())
    val ui: StateFlow<FileShareUiState> = _ui.asStateFlow()
    private var transferJob: Job? = null
    private var refreshJob: Job? = null

    fun bind(uri: Uri) {
        val name = runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "shared_file"
        val total = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        }.getOrDefault(-1)
        _ui.update { it.copy(fileUri = uri, fileName = name, totalBytes = total) }
        startAutoRefresh()
    }

    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                for (i in 5 downTo 1) { _ui.update { it.copy(countdown = i) }; delay(1000) }
            }
        }
    }

    fun refreshOnce() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = tailRepo.fetchStatus()
            _ui.update { it.copy(tailscaleState = status.backendState, peers = status.peers,
                showNotRunningDialog = status.backendState !is BackendState.Running,
                showOfflineDialog = status.backendState is BackendState.Running && status.self?.online != true) }
        }
    }
    fun startTailscale() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = tailRepo.up()
            if (!result.ok) showFailure(result.text)
            refreshOnce()
        }
    }
    fun dismissNotRunning() { _ui.update { it.copy(showNotRunningDialog = false) } }
    fun dismissOffline() { _ui.update { it.copy(showOfflineDialog = false) } }

    fun send(peer: TailscaleDevice) {
        if (_ui.value.transferring || transferJob?.isActive == true) return
        val current = _ui.value
        val uri = current.fileUri ?: return
        val total = current.totalBytes
        val initial = TransferProgress.percent(0, total)
        val initialText = progressText(0, initial)
        _ui.update { it.copy(transferring = true, transferFinished = false,
            progressPercent = initial, progressText = initialText) }
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            val sessionJob = currentCoroutineContext().job
            UploadSessions.register(sessionJob)
            try {
                pushNotification(initial, initialText)
                val target = peer.ipv4 ?: peer.ipv6?.let { "[$it]" }
                    ?: peer.dnsName.trimEnd('.').ifBlank { peer.name }
                val command = ShellCommand.fileSend(current.fileName, target)
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the shared file")
                val process = try { shell.startProcess(*command) } catch (e: Exception) { input.close(); throw e }
                var lastUpdate = 0L
                var lastPercent = initial
                val result = ProcessIo.transfer(process, input, total) { bytes ->
                    val percent = TransferProgress.percent(bytes, total)
                    val now = System.nanoTime()
                    if (percent != lastPercent || now - lastUpdate >= 250_000_000L) {
                        lastUpdate = now; lastPercent = percent
                        val text = progressText(bytes, percent)
                        _ui.update { it.copy(progressPercent = percent, progressText = text) }
                        pushNotification(percent, text)
                    }
                }
                if (!result.ok) throw IOException(result.text.ifBlank { "CLI exited with code ${result.code}" })
                _ui.update { it.copy(transferring = false, transferFinished = true, progressPercent = 100,
                    progressText = app.getString(R.string.transfer_complete_progress)) }
                pushNotification(100, app.getString(R.string.transfer_complete))
            } catch (e: CancellationException) {
                showFailure(e.message ?: app.getString(R.string.transfer_canceled))
                throw e
            } catch (e: Exception) { showFailure(e.message ?: "Unable to transfer file") }
            finally {
                UploadSessions.unregister(sessionJob)
                if (_ui.value.progressPercent != 100) stopNotification()
            }
        }
    }

    private fun progressText(bytes: Long, percent: Int): String = if (percent < 0) {
        app.getString(R.string.taildrop_processed_bytes, Formatter.formatShortFileSize(app, bytes))
    } else "$percent%"
    private fun showFailure(message: String) {
        _ui.update { it.copy(transferring = false, transferFinished = true,
            progressText = app.getString(R.string.transfer_failed, message)) }
    }
    fun openCancelDialog() { _ui.update { it.copy(cancelDialogOpen = true) } }
    fun dismissCancelDialog() { _ui.update { it.copy(cancelDialogOpen = false) } }
    fun confirmCancel(onComplete: () -> Unit) {
        _ui.update { it.copy(cancelDialogOpen = false, processingDialog = true) }
        viewModelScope.launch {
            transferJob?.cancelAndJoin(); transferJob = null
            _ui.update { it.copy(processingDialog = false, transferring = false, transferFinished = true,
                progressText = app.getString(R.string.transfer_canceled), progressPercent = 0, doneDialog = true) }
            stopNotification(); onComplete()
        }
    }
    fun dismissDoneDialog() { _ui.update { it.copy(doneDialog = false) } }
    fun stopNotification() { app.stopService(Intent(app, FileTransferService::class.java)) }
    private fun pushNotification(progress: Int, text: String) {
        app.startForegroundService(Intent(app, FileTransferService::class.java).apply {
            action = FileTransferService.ACTION_START
            putExtra(FileTransferService.EXTRA_PROGRESS, progress)
            putExtra(FileTransferService.EXTRA_CONTENT_TEXT, text)
        })
    }
    override fun onCleared() {
        transferJob?.cancel(); refreshJob?.cancel(); stopNotification(); super.onCleared()
    }
}
