package top.cenmin.tailcontrol.share

object TransferProgress {
    /** -1 means indeterminate. Completion must come from the CLI exit status, never this counter. */
    fun percent(bytes: Long, total: Long): Int = when {
        total < 0 -> -1
        total == 0L -> 0
        else -> (bytes.toDouble() / total * 100).toInt().coerceIn(0, 99)
    }
}
