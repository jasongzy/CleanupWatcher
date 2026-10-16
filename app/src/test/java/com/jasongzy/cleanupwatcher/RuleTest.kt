package com.jasongzy.cleanupwatcher

import org.junit.Assert.*
import org.junit.Test

class RuleTest {
    @Test
    fun storedTreeRuleKeepsItsTypeAndScope() {
        val text = "CW\n74657374 2f736463617264 4361636865 1 1 1 0 -\n"
        val rule = Rule.decode(text).single()
        assertEquals(RuleAction.EmptyTree, rule.action)
        assertEquals("Cache", rule.pattern)
        assertTrue(rule.recursive && rule.enabled)
        assertEquals(text, Rule.encode(listOf(rule)))
    }

    @Test
    fun emptyChildrenHasAConstantScope() {
        val rule =
            Rule(pattern = "Cache", recursive = true, literal = true)
                .withAction(RuleAction.EmptyChildren)
        assertEquals("*", rule.pattern)
        assertFalse(rule.recursive || rule.literal)
        assertNull(rule.error())
        assertEquals("config", rule.copy(recursive = true).error())
        assertEquals("config", rule.copy(literal = true).error())
        assertEquals("config", rule.copy(pattern = "Cache").error())
        assertEquals("", rule.withAction(RuleAction.FolderTree).pattern)
    }

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
        assertEquals(
            "directory_pattern",
            Rule(action = RuleAction.FolderTree, pattern = ".").error(),
        )
        assertEquals(
            "directory_pattern",
            Rule(action = RuleAction.FolderTree, pattern = "..").error(),
        )
        assertEquals(
            "directory_pattern",
            Rule(action = RuleAction.FolderTree, pattern = ".CLEANUPWATCHER").error(),
        )
        assertNull(Rule(action = RuleAction.FolderTree, pattern = "Junk").error())
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
