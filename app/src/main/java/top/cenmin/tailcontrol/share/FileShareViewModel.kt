package top.cenmin.tailcontrol.share

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.cenmin.tailcontrol.R
import top.cenmin.tailcontrol.core.data.TailscaleRepository
import top.cenmin.tailcontrol.core.model.BackendState
import top.cenmin.tailcontrol.core.model.TailscaleDevice
import top.cenmin.tailcontrol.core.shell.ProcessIo
import top.cenmin.tailcontrol.core.shell.RootShell
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
        val rawName = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        val name = sanitizeFileName(rawName ?: uri.lastPathSegment)
        val total = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                fd.statSize.takeIf { it >= 0 } ?: -1L
            } ?: -1L
        }.getOrDefault(-1L)
        _ui.update { it.copy(fileUri = uri, fileName = name, totalBytes = total) }
        startAutoRefresh()
    }

    private fun sanitizeFileName(raw: String?): String =
        raw?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filter { it >= ' ' && it != '\u007f' }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "shared_file"

    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                for (i in 5 downTo 1) {
                    _ui.update { it.copy(countdown = i) }
                    delay(1000)
                }
            }
        }
    }

    fun refreshOnce() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = tailRepo.fetchStatus()
            val state = status.backendState
            _ui.update { cur ->
                if (state is BackendState.Running) {
                    val online = status.self?.online == true
                    cur.copy(
                        tailscaleState = state,
                        peers = if (online) status.peers else cur.peers,
                        showOfflineDialog = !online,
                        showNotRunningDialog = false,
                    )
                } else {
                    cur.copy(
                        tailscaleState = state,
                        showNotRunningDialog = true,
                        showOfflineDialog = false,
                    )
                }
            }
        }
    }

    fun startTailscale() {
        viewModelScope.launch(Dispatchers.IO) {
            tailRepo.up()
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
        val initialPct = if (total >= 0) 0 else -1

        _ui.update {
            it.copy(
                transferring = true,
                transferFinished = false,
                progressPercent = initialPct,
                progressText = if (initialPct >= 0) "0%" else app.getString(R.string.transferring),
            )
        }

        transferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = peer.ipv4
                    ?: peer.ipv6?.let { "[$it]" }
                    ?: peer.dnsName.trimEnd('.').ifBlank { peer.name }
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open shared file")
                val process = try {
                    shell.startProcess("tailscale", "file", "cp", "--name", current.fileName, "--", "-", "$target:")
                } catch (e: Exception) {
                    input.close()
                    throw e
                }

                var lastUpdateNs = 0L
                var lastPct = initialPct
                val result = ProcessIo.transfer(process, input, total) { bytes ->
                    val pct = computePercent(bytes, total)
                    val now = System.nanoTime()
                    if (pct != lastPct || now - lastUpdateNs >= 250_000_000L) {
                        lastPct = pct
                        lastUpdateNs = now
                        val text = if (pct >= 0) "$pct%" else Formatter.formatShortFileSize(app, bytes)
                        _ui.update { it.copy(progressPercent = pct, progressText = text) }
                    }
                }

                if (!result.ok) {
                    throw IOException(result.text.ifBlank { "Exit code ${result.code}" })
                }

                _ui.update {
                    it.copy(
                        transferring = false,
                        transferFinished = true,
                        progressPercent = 100,
                        progressText = app.getString(R.string.transfer_complete_progress),
                    )
                }
            } catch (e: CancellationException) {
                _ui.update {
                    it.copy(
                        transferring = false,
                        transferFinished = false,
                        progressPercent = 0,
                        progressText = app.getString(R.string.transfer_canceled),
                    )
                }
                throw e
            } catch (e: Throwable) {
                _ui.update {
                    it.copy(
                        transferring = false,
                        transferFinished = true,
                        progressPercent = 0,
                        progressText = app.getString(R.string.transfer_failed, e.message ?: "Unknown error"),
                    )
                }
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
    }

    private fun computePercent(bytes: Long, total: Long): Int {
        if (total < 0) return -1
        if (total == 0L) return 0
        val clamped = bytes.coerceAtLeast(0L)
        return ((clamped.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 99)
    }

    override fun onCleared() {
        super.onCleared()
        transferJob?.cancel()
        refreshJob?.cancel()
    }
}
