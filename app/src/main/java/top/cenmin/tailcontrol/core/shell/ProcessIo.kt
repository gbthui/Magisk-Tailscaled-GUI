package top.cenmin.tailcontrol.core.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object ProcessIo {
    private const val OUTPUT_LIMIT = 64 * 1024

    suspend fun transfer(
        process: Process,
        source: InputStream,
        expectedBytes: Long = -1,
        onBytes: (Long) -> Unit = {},
    ): CommandResult = coroutineScope {
        val stdout = async(Dispatchers.IO) { readTail(process.inputStream) }
        val stderr = async(Dispatchers.IO) { readTail(process.errorStream) }
        val writer = async(Dispatchers.IO) {
            try {
                runInterruptible {
                    var copied = 0L
                    source.use { input ->
                        process.outputStream.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                output.write(buffer, 0, n)
                                copied += n
                                onBytes(copied)
                            }
                            output.flush()
                        }
                    }
                    if (expectedBytes >= 0 && copied != expectedBytes) {
                        throw IOException("Source size changed: expected $expectedBytes bytes, read $copied")
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }
        try {
            val writeFailure = writer.await()
            if (writeFailure != null) {
                val stopped = runInterruptible(Dispatchers.IO) { process.waitFor(1, TimeUnit.SECONDS) }
                if (!stopped) process.destroyForcibly()
            }
            val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
            val out = stdout.await()
            val err = stderr.await().toMutableList()
            if (writeFailure != null) err += "Input transfer failed: ${writeFailure.message}"
            CommandResult(
                ok = exit == 0 && writeFailure == null,
                code = if (exit == 0 && writeFailure != null) -1 else exit,
                out = out,
                err = err,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { source.close() }
            writer.cancel()
            stdout.cancel()
            stderr.cancel()
        }
    }

    private fun readTail(input: InputStream): List<String> {
        val tail = StringBuilder()
        try {
            input.reader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    tail.append(buffer, 0, n)
                    if (tail.length > OUTPUT_LIMIT) tail.delete(0, tail.length - OUTPUT_LIMIT)
                }
            }
        } catch (_: IOException) {
            // Stream closed on process termination/cancellation; keep collected tail.
        }
        return tail.toString().trim().takeIf { it.isNotEmpty() }?.lines().orEmpty()
    }
}
