package com.jasongzy.cleanupwatcher

import java.io.File
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
            File(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).path,
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
}
