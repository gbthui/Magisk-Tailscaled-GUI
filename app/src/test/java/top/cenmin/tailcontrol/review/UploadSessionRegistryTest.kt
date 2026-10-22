package top.cenmin.tailcontrol.review

import kotlinx.coroutines.Job
import org.junit.Test
import top.cenmin.tailcontrol.share.UploadSessionRegistry

class UploadSessionRegistryTest {
    @Test fun progressUpdatesDoNotInvokePlatformCallbacksOrCancelTheProducer() {
        val registry = UploadSessionRegistry()
        val job = Job()
        val id = registry.register(job, 0, "starting")
        repeat(1000) { registry.update(id, it % 100, "progress $it") }
        check(job.isActive)
        check(registry.progress.value.single().text == "progress 999")
        job.complete()
    }
    @Test fun lateProgressCannotResurrectAFinishedSession() {
        val registry = UploadSessionRegistry()
        val job = Job()
        val id = registry.register(job, 0, "start")
        job.complete()
        registry.update(id, 90, "late")
        check(registry.progress.value.isEmpty())
    }
    @Test fun oldCompletionCannotStopANewerUpload() {
        val registry = UploadSessionRegistry()
        val old = Job(); val newer = Job()
        val oldId = registry.register(old, 0, "old")
        val newId = registry.register(newer, 0, "new")
        old.complete(); registry.finish(oldId)
        check(registry.progress.value.single().id == newId)
        check(newer.isActive)
        newer.complete()
    }
    @Test fun cancellationBeforeUploadStartsRemovesItsNotificationState() {
        val registry = UploadSessionRegistry()
        val job = Job()
        registry.register(job, 0, "starting")
        job.cancel()
        check(registry.progress.value.isEmpty())
    }
    @Test fun systemTimeoutOnlyCancelsRegisteredUploads() {
        val registry = UploadSessionRegistry()
        val registered = Job(); val unrelated = Job()
        registry.register(registered, 1, "running")
        registry.cancelForTimeout()
        check(registered.isCancelled && unrelated.isActive)
        check(registry.progress.value.isEmpty())
        unrelated.complete()
    }
}
