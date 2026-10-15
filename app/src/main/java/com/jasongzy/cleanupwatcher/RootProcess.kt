package com.jasongzy.cleanupwatcher

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun runProcess(
    command: List<String>,
    input: String = "",
    timeoutMillis: Long = 90_000,
): Pair<Int, String> {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val output = ByteArrayOutputStream()
    val failure = AtomicReference<Exception?>()
    fun remaining(): Long =
        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
    fun transfer(action: () -> Unit): Thread = Thread {
        try {
            action()
        } catch (e: Exception) {
            failure.compareAndSet(null, e)
            process.destroyForcibly()
        }
    }
        .apply {
            isDaemon = true
            start()
        }
    val reader = transfer {
        process.inputStream.use { stream ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 1024 * 1024) { "limit" }
                output.write(buffer, 0, count)
            }
        }
    }
    val writer = transfer { process.outputStream.bufferedWriter().use { it.write(input) } }
    return try {
        check(process.waitFor(remaining(), TimeUnit.MILLISECONDS)) { "timeout" }
        for (thread in listOf(reader, writer)) {
            val wait = remaining()
            if (wait > 0) thread.join(wait)
            check(!thread.isAlive) { "timeout" }
        }
        failure.get()?.let { throw it }
        process.exitValue() to
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
