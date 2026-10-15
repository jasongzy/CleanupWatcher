package com.jasongzy.cleanupwatcher

import org.junit.Assert.*
import org.junit.Test

class ShellFailureTest {
    @Test
    fun executionFailureKeepsShellDiagnostic() {
        assertEquals(
            "execution: exit=126\n/system/bin/sh: backend: Permission denied",
            shellFailure(126, "  /system/bin/sh: backend: Permission denied\n"),
        )
        assertEquals("execution: exit=1", shellFailure(1, ""))
    }

    @Test
    fun absentBackendRemainsRecognizableAndOutputIsBounded() {
        assertTrue(shellFailure(127, "not found").startsWith("offline:"))
        val result = shellFailure(1, "x".repeat(2000) + "failure")
        assertEquals(800, result.substringAfter('\n').length)
        assertTrue(result.endsWith("failure"))
    }
}
