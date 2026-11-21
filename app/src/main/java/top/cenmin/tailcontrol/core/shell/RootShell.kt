package top.cenmin.tailcontrol.core.shell

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

data class CommandResult(
    val ok: Boolean,
    val code: Int,
    val out: List<String>,
    val err: List<String>,
) {
    val text: String get() = (out + err).joinToString("\n").trim()
}

@Singleton
class RootShell @Inject constructor() {

    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun exec(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val r = Shell.cmd(isolated(cmd)).to(out, err).exec()
            CommandResult(r.isSuccess, r.code, out, err)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult(false, -1, emptyList(), listOf(e.message.orEmpty()))
        }
    }

    suspend fun execText(cmd: String): String = exec(cmd).text

    /**
     * 启动带二进制 stdin 的独立 root 子进程（如 `tailscale file cp ... - target:`）。
     * 通过 `exec` 让目标进程直接替换 shell，销毁该 Process 即精准终止当前传输，不牵连其他进程。
     */
    fun startProcess(vararg args: String): Process =
        ProcessBuilder("su", "-c", processScript(*args)).start()

    /**
     * 长流式命令使用专用 Shell 实例，不阻塞共享主 Shell。
     */
    fun stream(cmd: String): Flow<String> = callbackFlow {
        val shell = try {
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
                .build()
        } catch (e: Exception) {
            trySend(e.message ?: "Unable to create root shell")
            close()
            return@callbackFlow
        }
        val outCb = object : CallbackList<String>() {
            override fun onAddElement(s: String?) {
                if (s != null) trySend(s)
            }
        }
        val syncExecutor = Executor { it.run() }
        shell.newJob()
            .add(isolated(cmd))
            .to(outCb, outCb)
            .submit(syncExecutor) { _ ->
                close()
            }
        awaitClose {
            runCatching { shell.close() }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val FALLBACK_PATH =
            "/data/adb/tailscale/bin:/data/adb/tailscale/scripts:/data/adb/modules/magisk-tailscaled/system/bin"

        fun quote(value: String): String {
            require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
            return "'" + value.replace("'", "'\\''") + "'"
        }

        fun argv(vararg args: String): String {
            require(args.isNotEmpty() && args.first().isNotBlank()) { "Missing executable" }
            return args.joinToString(" ", transform = ::quote)
        }

        fun isolated(cmd: String, fallbackPath: String = FALLBACK_PATH): String =
            "(\nexport PATH=${quote(fallbackPath)}:\"\$PATH\"\n$cmd\n)"

        fun processScript(vararg args: String, fallbackPath: String = FALLBACK_PATH): String =
            "export PATH=${quote(fallbackPath)}:\"\$PATH\"\nexec ${argv(*args)}"
    }
}
