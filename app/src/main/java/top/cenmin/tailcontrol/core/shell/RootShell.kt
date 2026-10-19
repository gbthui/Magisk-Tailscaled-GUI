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

@Singleton
class RootShell @Inject constructor() {
    companion object {
        // Main and dedicated shells must use the same root/mount configuration.
        fun builder(): Shell.Builder = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setTimeout(10)
    }

    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun exec(cmd: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            if (!Shell.getShell().isRoot) return@withContext CommandResult.failure("Root access denied", 126)
            // Always prepare this execution's environment. Never reuse another shell's which result.
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val r = Shell.cmd(ShellCommand.isolated(cmd)).to(out, err).exec()
            CommandResult(r.isSuccess, r.code, out, err)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "Unable to execute root command")
        }
    }

    suspend fun execText(cmd: String): String = exec(cmd).text

    suspend fun resolveExecutable(name: String): String? {
        val r = exec("command -v ${ShellCommand.quote(name)}")
        val path = r.out.singleOrNull()?.trim()?.takeIf { it.startsWith('/') } ?: return null
        return path.takeIf { r.ok && exec("[ -x ${ShellCommand.quote(it)} ]").ok }
    }

    /** A separate process for commands that consume binary stdin. Call on an IO dispatcher. */
    fun startProcess(vararg args: String): Process =
        ProcessBuilder("su", "-c", ShellCommand.process(*args)).start()

    /** Long-running commands use a dedicated shell and do not block the main shell. */
    fun stream(cmd: String): Flow<String> = callbackFlow {
        val shell = try {
            builder().build()
        } catch (e: Exception) {
            trySend(e.message ?: "Unable to create root shell")
            close()
            return@callbackFlow
        }
        if (!shell.isRoot) {
            shell.close()
            trySend("Root access denied")
            close()
            return@callbackFlow
        }
        val output = object : CallbackList<String>() {
            override fun onAddElement(s: String?) {
                if (s != null) trySend(s)
            }
        }
        try {
            shell.newJob()
                .add(ShellCommand.isolated(cmd))
                .to(output, output)
                .submit(Executor { it.run() }) { result ->
                    if (!result.isSuccess) trySend("Command failed (exit ${result.code})")
                    close()
                }
            awaitClose { runCatching { shell.close() } }
        } finally {
            runCatching { shell.close() }
        }
    }.flowOn(Dispatchers.IO)
}
