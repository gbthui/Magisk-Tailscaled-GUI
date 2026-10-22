package top.cenmin.tailcontrol.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import top.cenmin.tailcontrol.core.model.ReceiverRecoveryPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** Small synchronous timeout marker: survives death before the async DataStore update finishes. */
@Singleton
class ReceiverSessionGuard @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.getSharedPreferences("receiver-system-stop", Context.MODE_PRIVATE)
    fun blocks(revision: Long): Boolean {
        val blocked = store.getLong("revision", Long.MIN_VALUE)
        return ReceiverRecoveryPolicy.systemStopBlocks(blocked, revision)
    }
    fun block(revision: Long?) {
        check(store.edit().putLong("revision", revision ?: -1L).commit()) { "Cannot persist receiver timeout marker" }
    }
    fun allowExplicitStart() {
        check(store.edit().remove("revision").commit()) { "Cannot clear receiver timeout marker" }
    }
}
