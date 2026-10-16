package com.jasongzy.cleanupwatcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuleEditingTest {
    @Test
    fun editingPreservesTheStoredEnabledState() {
        for (enabled in listOf(false, true)) {
            val original = Rule(pattern = "old.log", enabled = enabled)
            val edited = original.copy(pattern = "new.log", enabled = !enabled)
            val updated = replaceRules(listOf(original), listOf(edited), original.id).single()
            assertEquals(edited.copy(enabled = enabled), updated)
            assertEquals(enabled, original.enabled)
        }
    }

    @Test
    fun batchEditingKeepsOnlyExistingRulesEnabled() {
        val original = Rule(pattern = "old.log", enabled = true)
        val untouched = Rule(root = "/sdcard/Other", pattern = "keep.log", enabled = true)
        val added = Rule(pattern = "added.log", enabled = true)
        val edited = original.copy(pattern = "changed.log", enabled = false)
        val updated = replaceRules(listOf(original, untouched), listOf(edited, added), original.id)
        assertTrue(updated.first { it.id == original.id }.enabled)
        assertEquals(untouched, updated.first { it.id == untouched.id })
        assertFalse(updated.first { it.id == added.id }.enabled)
        assertFalse(replaceRules(emptyList(), listOf(added), added.id).single().enabled)
    }

    @Test
    fun listAndJsonShareStablePathNameAndTargetOrder() {
        val rules =
            listOf(
                Rule(root = "/sdcard/Z", pattern = "a.log"),
                Rule(root = "/sdcard/A", pattern = "z.log"),
                Rule(
                    root = "/sdcard/A",
                    pattern = "a.log",
                    action = RuleAction.FolderTree,
                    literal = true,
                ),
                Rule(root = "/sdcard/A", pattern = "a.log", enabled = true),
            )
        val expected = listOf(rules[3], rules[2], rules[1], rules[0])
        assertEquals(expected, rules.sortedRules())
        assertEquals(
            expected.map { it.id },
            expected.map { it.copy(enabled = !it.enabled) }.sortedRules().map { it.id },
        )
        val exported = JSONObject(RuleJson.encode(rules)).getJSONArray("rules")
        assertEquals(
            expected.map { it.root },
            (0 until exported.length()).map { exported.getJSONObject(it).getString("root") },
        )
        assertEquals(
            expected.map { it.pattern },
            (0 until exported.length()).map { exported.getJSONObject(it).getString("name") },
        )
        assertEquals("file", exported.getJSONObject(0).getString("target"))
        assertEquals("folder-tree", exported.getJSONObject(1).getString("target"))
    }
}
