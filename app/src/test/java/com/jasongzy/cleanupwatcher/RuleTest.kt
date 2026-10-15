package com.jasongzy.cleanupwatcher

import org.junit.Assert.*
import org.junit.Test

class RuleTest {
    @Test
    fun protocolValidatesEncodingAndFieldBounds() {
        val rule = Rule(pattern = "*.log", whitelist = List(128) { "x".repeat(200) + it })
        assertEquals(listOf(rule), Rule.decode(Rule.encode(listOf(rule))))
        assertEquals("config", rule.copy(id = "x".repeat(65)).error())
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            Rule.unhex("ff")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rule.decode(Rule.encode(listOf(rule)) + "\n")
        }
    }

    @Test
    fun protocolRoundTripsUnicodeAndWhitelist() {
        val rule =
            Rule(
                root = "/sdcard/测试 空格",
                pattern = "*.log",
                whitelist = listOf("keep", "nested/保留"),
            )
        assertEquals(listOf(rule), Rule.decode(Rule.encode(listOf(rule))))
    }

    @Test
    fun scopesRejectTraversalAndOtherUsers() {
        listOf(
                "",
                "/",
                "/data",
                "/data/adb",
                "/sdcard/.cleanupwatcher",
                "/sdcard/.CLEANUPWATCHER/child",
                "/sdcard/../data",
                "/sdcard2",
                "/sdcard//Logs",
                "/data/user/10/test.app/cache",
            )
            .forEach {
                assertFalse(it, Rule.validRoot(it))
            }
        assertTrue(Rule.validRoot("/data/user/0/test.app/cache/logs"))
    }

    @Test
    fun treeDeletionRequiresConcreteDirectoryName() {
        assertEquals("directory_pattern", Rule(action = 2, pattern = ".").error())
        assertEquals("directory_pattern", Rule(action = 2, pattern = "..").error())
        assertEquals("directory_pattern", Rule(action = 2, pattern = ".CLEANUPWATCHER").error())
        assertNull(Rule(action = 2, pattern = "Junk").error())
    }

    @Test
    fun duplicateIdentifiersAreRejected() {
        val rule = Rule(pattern = "*.log")
        assertThrows(IllegalArgumentException::class.java) {
            Rule.decode(Rule.encode(listOf(rule, rule)))
        }
    }

    @Test
    fun malformedRulesAreRejected() {
        val text = Rule.encode(listOf(Rule(pattern = "*.log")))
        assertTrue(text.startsWith("CW\n"))
        assertThrows(IllegalArgumentException::class.java) {
            Rule.decode(text.substringAfter('\n'))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Rule.decode(text.trimEnd() + " extra\n")
        }
    }
}
