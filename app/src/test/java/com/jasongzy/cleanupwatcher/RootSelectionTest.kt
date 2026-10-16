package com.jasongzy.cleanupwatcher

import org.junit.Assert.*
import org.junit.Test

class RootSelectionTest {
    @Test
    fun emptyChildrenSelectionsUseEachFolderAsTheirScope() {
        val entries =
            listOf(
                RootEntry("/sdcard/Cache", "Cache", "directory"),
                RootEntry("/sdcard/Cache/Nested", "Nested", "directory"),
            )
        val rules = rulesForEntries(entries, RuleAction.EmptyChildren)
        assertEquals(entries.map { it.path }, rules.map { it.root })
        assertTrue(
            rules.all {
                it.action == RuleAction.EmptyChildren &&
                    it.pattern == "*" &&
                    !it.recursive &&
                    !it.literal
            }
        )
        assertThrows(IllegalArgumentException::class.java) {
            rulesForEntries(
                listOf(RootEntry("/sdcard/file", "file", "file")),
                RuleAction.EmptyChildren,
            )
        }
    }

    @Test
    fun pickedNamesStayLiteralAndDisabled() {
        val rules =
            rulesForEntries(
                listOf(
                    RootEntry("/sdcard/a*.log", "a*.log", "file"),
                    RootEntry("/sdcard/垃圾[1]", "垃圾[1]", "directory"),
                    RootEntry("/sdcard/with space / ", " ", "file"),
                ),
                RuleAction.FolderTree,
            )
        assertEquals(listOf("a*.log", "垃圾[1]", " "), rules.map { it.pattern })
        assertEquals("/sdcard/with space ", rules.last().root)
        assertEquals(
            listOf(RuleAction.File, RuleAction.FolderTree, RuleAction.File),
            rules.map { it.action },
        )
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
        assertEquals(
            listOf("Junk"),
            rulesForEntries(entries, RuleAction.FolderTree).map { it.pattern },
        )
        assertEquals(
            listOf(RuleAction.EmptyTree, RuleAction.File),
            rulesForEntries(entries, RuleAction.EmptyTree).map { it.action },
        )
        assertEquals(1, rulesForEntries(listOf(entries[0], entries[0]), RuleAction.FolderTree).size)
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
                    rulesForEntries(listOf(entry), RuleAction.FolderTree)
                }
            }
    }

    @Test
    fun oversizedSelectionIsRejectedBeforeSaving() {
        val parent = "/sdcard/" + List(20) { "a".repeat(200) }.joinToString("/")
        val entries = List(20) { RootEntry("$parent/$it.log", "$it.log", "file") }
        assertThrows(IllegalArgumentException::class.java) {
            rulesForEntries(entries, RuleAction.FolderTree)
        }
    }

    @Test
    fun changingTargetTypePreservesExactNames() {
        val directory = Rule(pattern = "Junk[1]", action = RuleAction.FolderTree)
        assertTrue(directory.withAction(RuleAction.EmptyTree).literal)
        assertTrue(directory.withAction(RuleAction.File).literal)
        assertTrue(
            Rule(pattern = "*.log")
                .withAction(RuleAction.FolderTree)
                .withAction(RuleAction.EmptyTree)
                .literal
        )
        assertFalse(Rule(pattern = "*.log").withAction(RuleAction.EmptyTree).literal)
    }
}
