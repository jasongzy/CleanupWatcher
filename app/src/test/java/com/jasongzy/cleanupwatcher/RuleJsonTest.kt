package com.jasongzy.cleanupwatcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuleJsonTest {
    @Test
    fun treeAndDirectEmptyRulesHaveDistinctFormats() {
        val tree =
            Rule(pattern = "Cache", action = RuleAction.EmptyTree, recursive = true, enabled = true)
        val children =
            Rule(root = "/sdcard/Cache", whitelist = listOf("keep"))
                .withAction(RuleAction.EmptyChildren)
        val document = JSONObject(RuleJson.encode(listOf(tree, children)))
        val entries = document.getJSONArray("rules")
        assertEquals("empty-tree", entries.getJSONObject(0).getString("target"))
        val entry = entries.getJSONObject(1)
        assertEquals("empty-children", entry.getString("target"))
        assertEquals(
            setOf("root", "target", "enabled", "whitelist"),
            entry.keys().asSequence().toSet(),
        )
        val restored = RuleJson.decode(document.toString())
        assertEquals(
            listOf(tree, children).map { it.copy(id = "") },
            restored.map { it.copy(id = "") },
        )
        entry.put("recursive", true)
        assertThrows(IllegalArgumentException::class.java) { RuleJson.decode(document.toString()) }
    }

    @Test
    fun unorderedImportAndMergedRulesAreSortedImmediately() {
        val existing = Rule(root = "/sdcard/B", pattern = "middle.log", enabled = true)
        val incoming =
            listOf(
                Rule(root = "/sdcard/Z", pattern = "last.log", enabled = true),
                Rule(root = "/sdcard/A", pattern = "first.log", enabled = true),
            )
        val document = JSONObject(RuleJson.encode(incoming))
        val entries = document.getJSONArray("rules")
        document.put("rules", JSONArray(listOf(entries.getJSONObject(1), entries.getJSONObject(0))))
        val imported = RuleJson.decode(document.toString())
        assertEquals(listOf("/sdcard/A", "/sdcard/Z"), imported.map { it.root })
        assertTrue(imported.all { it.enabled })
        assertEquals(
            listOf("/sdcard/A", "/sdcard/B", "/sdcard/Z"),
            RuleJson.combine(listOf(existing), imported.reversed(), replace = false).map {
                it.root
            },
        )
        assertEquals(
            imported.map { it.copy(enabled = false) },
            RuleJson.combine(listOf(existing), imported.reversed(), replace = true),
        )
    }

    @Test
    fun appendKeepsExistingRulesAndOverwriteReplacesThem() {
        val existing = listOf(Rule(pattern = "old.log", enabled = true))
        val imported = listOf(Rule(pattern = "new.log", enabled = true))
        val appended = RuleJson.combine(existing, imported, replace = false)
        assertEquals(existing.single(), appended.first { it.id == existing.single().id })
        val added = imported.single().copy(enabled = false)
        assertEquals(listOf(added, existing.single()), appended)
        assertEquals(listOf(added), RuleJson.combine(existing, imported, replace = true))
        assertTrue(RuleJson.combine(existing, emptyList(), replace = true).isEmpty())
        assertEquals(existing, RuleJson.combine(existing, emptyList(), replace = false))
    }

    @Test
    fun overwriteValidatesIncomingRulesIndependentlyOfExistingCount() {
        val existing = List(128) { Rule(pattern = "old-$it.log", enabled = true) }
        val imported = listOf(Rule(pattern = "new.log"))
        assertThrows(Exception::class.java) {
            RuleJson.combine(existing, imported, replace = false)
        }
        assertEquals(imported, RuleJson.combine(existing, imported, replace = true))
        assertEquals(128, existing.size)
        assertTrue(existing.all { it.enabled })
        val oversized =
            List(128) {
                Rule(root = "/sdcard/" + "a/".repeat(300) + "folder", pattern = "new-$it.log")
            }
        assertThrows(Exception::class.java) {
            RuleJson.combine(existing, oversized, replace = true)
        }
        assertThrows(Exception::class.java) {
            RuleJson.combine(
                existing,
                listOf(imported.single().copy(root = "/data/adb")),
                replace = true,
            )
        }
    }

    @Test
    fun importsExactPathsAndNamesWithTheirEnabledStateAndFreshIds() {
        val original =
            Rule(
                root = "/sdcard/空 格",
                pattern = "a*[1].log",
                literal = true,
                enabled = true,
                recursive = true,
                whitelist = listOf("keep", "images/photo.jpg"),
            )
        val exported = RuleJson.encode(listOf(original))
        assertEquals("cleanupwatcher", JSONObject(exported).getString("format"))
        assertEquals(BuildConfig.VERSION_CODE, JSONObject(exported).getInt("versionCode"))
        val restored = RuleJson.decode(exported).single()
        assertEquals(original.copy(id = restored.id), restored)
        assertNotEquals(original.id, restored.id)
        val tree = original.copy(action = RuleAction.FolderTree, literal = false)
        assertTrue(RuleJson.decode(RuleJson.encode(listOf(tree))).single().literal)
    }

    @Test
    fun importKeepsActivationIntentSeparateFromSavedRules() {
        val exported =
            listOf(Rule(pattern = "active.log", enabled = true), Rule(pattern = "inactive.log"))
        val imported = RuleJson.decode(RuleJson.encode(exported))
        assertEquals(listOf("active.log"), imported.filter { it.enabled }.map { it.pattern })
        val existing = Rule(pattern = "existing.log", enabled = true)
        for (replace in listOf(false, true)) {
            val merged = RuleJson.combine(listOf(existing), imported, replace)
            val importedIds = imported.map { it.id }.toSet()
            assertEquals(2, merged.count { it.id in importedIds })
            assertTrue(merged.filter { it.id in importedIds }.none { it.enabled })
            assertEquals(
                if (replace) emptyList() else listOf(existing),
                merged.filterNot { it.id in importedIds },
            )
        }
    }

    @Test
    fun validatesOptionalVersionCodeWithoutRestrictingImportVersion() {
        val document = JSONObject(RuleJson.encode(emptyList()))
        for (value in listOf(JSONObject.NULL, 0, -1, "1", 1.5, 2147483648L)) {
            document.put("versionCode", value)
            assertThrows(Exception::class.java) { RuleJson.decode(document.toString()) }
        }
        document.put("versionCode", Int.MAX_VALUE)
        assertTrue(RuleJson.decode(document.toString()).isEmpty())
        document.remove("versionCode")
        assertTrue(RuleJson.decode(document.toString()).isEmpty())
    }

    @Test
    fun rejectsUnsafeOrAmbiguousFields() {
        val sample = RuleJson.encode(listOf(Rule(pattern = "*.log")))
        fun rejected(change: (JSONObject) -> Unit) {
            val document = JSONObject(sample)
            change(document.getJSONArray("rules").getJSONObject(0))
            assertThrows(Exception::class.java) { RuleJson.decode(document.toString()) }
        }
        rejected { it.put("root", "/data/adb") }
        rejected { it.put("recursive", "true") }
        rejected { it.put("enabled", "true") }
        rejected { it.put("target", "folder-tree") }
        rejected { it.put("extra", true) }
        rejected { it.remove("whitelist") }
        rejected { it.getJSONArray("whitelist").put("../precious") }
        rejected { it.getJSONArray("whitelist").put("/sdcard/precious") }
        rejected { it.getJSONArray("whitelist").put("keep\nother") }
        rejected { it.put("name", "\uD800") }
        assertThrows(Exception::class.java) { RuleJson.decode(sample + " true") }
    }

    @Test
    fun boundsCountSizeAndNestingBeforeImport() {
        val rule = Rule(pattern = "*.log")
        val document = JSONObject(RuleJson.encode(listOf(rule)))
        val entries = document.getJSONArray("rules")
        repeat(128) { entries.put(entries.getJSONObject(0)) }
        assertThrows(Exception::class.java) { RuleJson.decode(document.toString()) }
        assertThrows(Exception::class.java) { RuleJson.decode(" ".repeat(RuleJson.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) {
            RuleJson.decode("[".repeat(20) + "0" + "]".repeat(20))
        }
        val nested = "[".repeat(20) + "0" + "]".repeat(20)
        for (quote in listOf("'\"'", "literal\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                RuleJson.decode("[$quote,$nested,$quote]")
            }
        }
    }
}
