package com.jasongzy.cleanupwatcher

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RootProcessTest {
    @Test
    fun malformedUtf8IsRejected() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            runProcess(command("invalid"), timeoutMillis = 5_000)
        }
    }

    private fun command(mode: String): List<String> =
        listOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp",
            File(
                    requireNotNull(ProcessFixture::class.java.protectionDomain)
                        .codeSource
                        .location
                        .toURI()
                )
                .path,
            ProcessFixture::class.java.name,
            mode,
        )

    @Test
    fun inputAndOutputAreTransferredConcurrently() {
        val input = "测试规则\n".repeat(20_000)
        assertEquals(0 to input, runProcess(command("echo"), input, 5_000))
    }

    @Test
    fun timeoutIncludesBlockedInput() {
        val started = System.nanoTime()
        val error =
            assertThrows(IllegalStateException::class.java) {
                runProcess(command("stall"), "x".repeat(128 * 1024), 500)
            }
        assertEquals("timeout", error.message)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun oversizedOutputFailsExplicitly() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                runProcess(command("flood"), timeoutMillis = 5_000)
            }
        assertEquals("limit", error.message)
    }

    @Test
    fun streamingDecodesUtf8BeforeProcessExitAndCancels() = runBlocking {
        val line = withTimeout(5_000) { streamProcess(command("stream")).first() }
        assertTrue(line.startsWith("测试:"))
        val pid = line.substringAfter(':').toLong()
        withTimeout(5_000) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
        }
    }

    @Test
    fun streamingRejectsOversizedAndMalformedMessages() {
        for ((mode, expected) in listOf("flood" to "limit", "invalid" to "offline")) {
            val error =
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { withTimeout(5_000) { streamProcess(command(mode)).collect() } }
                }
            assertEquals(expected, error.message)
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            runBlocking { withTimeout(5_000) { streamProcess(command("invalid-line")).collect() } }
        }
    }
}
