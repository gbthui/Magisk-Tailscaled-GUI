package top.cenmin.tailcontrol.share

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Tracks only this application's upload jobs, never system-wide tailscale PIDs. */
object UploadSessions {
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    fun register(job: Job) { jobs.add(job) }
    fun unregister(job: Job) { jobs.remove(job) }
    fun cancelForTimeout() {
        jobs.toList().forEach { it.cancel(CancellationException("Android data-sync session timed out")) }
    }
}
