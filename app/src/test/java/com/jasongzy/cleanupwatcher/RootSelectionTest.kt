package com.jasongzy.cleanupwatcher

import org.junit.Assert.*
import org.junit.Test

class RootSelectionTest {
    @Test
    fun pickedNamesStayLiteralAndDisabled() {
        val rules =
            rulesForEntries(
                listOf(
                    RootEntry("/sdcard/a*.log", "a*.log", "file"),
                    RootEntry("/sdcard/垃圾[1]", "垃圾[1]", "directory"),
                    RootEntry("/sdcard/with space / ", " ", "file"),
                ),
                false,
            )
        assertEquals(listOf("a*.log", "垃圾[1]", " "), rules.map { it.pattern })
        assertEquals("/sdcard/with space ", rules.last().root)
        assertEquals(listOf(0, 2, 0), rules.map { it.action })
        assertTrue(rules.all { it.literal && !it.enabled && !it.recursive })
        assertEquals(rules, Rule.decode(Rule.encode(rules)))
    }

    @Test
    fun folderContentsCoverSelectedDescendants() {
        val entries =
            listOf(
                RootEntry("/sdcard/Junk", "Junk", "directory"),
                RootEntry("/sdcard/Junk/event.log", "event.log", "file"),
            )
        assertEquals(listOf("Junk"), rulesForEntries(entries, false).map { it.pattern })
        assertEquals(listOf(1, 0), rulesForEntries(entries, true).map { it.action })
        assertEquals(1, rulesForEntries(listOf(entries[0], entries[0]), false).size)
    }

    @Test
    fun cleanupRejectsUnsafeSelections() {
        listOf(
                RootEntry("/system/build.prop", "build.prop", "file"),
                RootEntry("/sdcard/link", "link", "link"),
                RootEntry("/sdcard/file", "different", "file"),
            )
            .forEach { entry ->
                assertThrows(IllegalArgumentException::class.java) {
                    rulesForEntries(listOf(entry), false)
                }
            }
    }

    @Test
    fun oversizedSelectionIsRejectedBeforeSaving() {
        val parent = "/sdcard/" + List(20) { "a".repeat(200) }.joinToString("/")
        val entries = List(20) { RootEntry("$parent/$it.log", "$it.log", "file") }
        assertThrows(IllegalArgumentException::class.java) { rulesForEntries(entries, false) }
    }

    @Test
    fun changingTargetTypePreservesExactNames() {
        val directory = Rule(pattern = "Junk[1]", action = 2)
        assertTrue(directory.withAction(1).literal)
        assertTrue(directory.withAction(0).literal)
        assertTrue(Rule(pattern = "*.log").withAction(2).withAction(1).literal)
        assertFalse(Rule(pattern = "*.log").withAction(1).literal)
    }
}
