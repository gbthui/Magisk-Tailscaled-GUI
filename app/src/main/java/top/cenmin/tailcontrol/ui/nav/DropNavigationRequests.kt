package top.cenmin.tailcontrol.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot navigation event; a second notification remains consumable even on an existing task. */
class DropNavigationRequests(restored: Long? = null) {
    private var sequence = restored ?: 0L
    private val _pending = MutableStateFlow(restored)
    val pending = _pending.asStateFlow()
    fun request() { _pending.value = ++sequence }
    fun consumed(id: Long) { if (_pending.value == id) _pending.value = null }
    companion object { const val EXTRA_SHOW_DROP = "show_drop_screen" }
}
