package com.jasongzy.cleanupwatcher

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test
    fun exactLimitIsAcceptedAndOverflowRejected() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBounded(2))
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBounded(2)
        }
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readBounded(2))
    }
}
