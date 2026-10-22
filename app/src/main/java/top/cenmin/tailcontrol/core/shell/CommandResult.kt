package top.cenmin.tailcontrol.core.shell

data class CommandResult(
    val ok: Boolean,
    val code: Int,
    val out: List<String>,
    val err: List<String>,
) {
    val text: String get() = (out + err).joinToString("\n").trim()

    companion object {
        fun failure(message: String, code: Int = -1) =
            CommandResult(false, code, emptyList(), listOf(message))
    }
}
