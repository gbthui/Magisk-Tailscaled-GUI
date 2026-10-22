package top.cenmin.tailcontrol.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process progress only. Updating it never starts a service or invokes Android notification APIs. */
data class UploadProgress(val id: Long, val percent: Int, val text: String)

open class UploadSessionRegistry {
    private data class Entry(val job: Job, var progress: UploadProgress)
    private val entries = linkedMapOf<Long, Entry>()
    private var nextId = 0L
    private val _progress = MutableStateFlow<List<UploadProgress>>(emptyList())
    val progress: StateFlow<List<UploadProgress>> = _progress.asStateFlow()

    @Synchronized
    fun register(job: Job, percent: Int, text: String): Long {
        val id = ++nextId
        entries[id] = Entry(job, UploadProgress(id, percent, text))
        publish()
        // Also handles cancellation before a LAZY upload ever starts.
        job.invokeOnCompletion { finish(id) }
        return id
    }

    @Synchronized
    fun update(id: Long, percent: Int, text: String) {
        val entry = entries[id] ?: return // Late updates cannot resurrect a finished upload.
        entry.progress = UploadProgress(id, percent, text)
        publish()
    }

    @Synchronized
    fun finish(id: Long) {
        entries.remove(id)
        publish()
    }

    private fun publish() { _progress.value = entries.values.map { it.progress } }

    fun cancelForTimeout() {
        val jobs = synchronized(this) { entries.values.map { it.job } }
        jobs.forEach { it.cancel(CancellationException("Android data-sync session ended")) }
    }
}

/** Contains only this app's uploads, never system-wide tailscale PIDs. */
object UploadSessions : UploadSessionRegistry()
