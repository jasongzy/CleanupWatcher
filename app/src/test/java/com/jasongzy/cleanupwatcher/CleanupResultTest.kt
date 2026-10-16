package com.jasongzy.cleanupwatcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CleanupResultTest {
    private fun response(): JSONObject =
        JSONObject()
            .put("id", "rule")
            .put("root", "/sdcard/Logs")
            .put("paths", JSONArray(listOf("a.log")))
            .put("deleted", 0)
            .put("error", "")

    @Test
    fun cleanupAndRecoveryHaveExplicitResults() {
        val result = CleanupResult.decode(response())
        assertEquals(listOf("a.log"), result.paths)
        assertNull(result.restored)
        val recovery =
            response().apply {
                remove("deleted")
                put("restored", 1)
            }
        assertEquals(1, CleanupResult.decode(recovery).restored)
        val record =
            RecentRecord.decode(response().put("time", 123L).put("name", "*.log").put("deleted", 1))
        assertEquals(123L, record.time)
        assertEquals("*.log", record.name)
        assertEquals("/sdcard/Logs", record.root)
        assertEquals(listOf("a.log"), record.paths)
    }

    @Test
    fun recentRecordsContainExactlyTheSuccessfulDeletions() {
        val value = response().put("time", 123L).put("name", "*.log")
        assertThrows(IllegalArgumentException::class.java) { RecentRecord.decode(value) }
        value.put("deleted", 2)
        assertThrows(IllegalArgumentException::class.java) { RecentRecord.decode(value) }
        value.put("deleted", 1).put("error", "cancelled")
        val record = RecentRecord.decode(value)
        assertEquals(listOf("a.log"), record.paths)
        assertEquals("cancelled", record.error)
    }

    @Test
    fun recentRecordsWaitForACompatibleBackend() {
        val record = response().put("time", 123L).put("name", "*.log").put("deleted", 1)
        val status = JSONObject().put("records", JSONArray().put(record))
        assertTrue(RecentRecord.decodeAll(status, 2).isEmpty())
        status.put("versionCode", 1)
        assertTrue(RecentRecord.decodeAll(status, 2).isEmpty())
        status.put("versionCode", 2)
        assertEquals(listOf("a.log"), RecentRecord.decodeAll(status, 2).single().paths)
        record.put("paths", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { RecentRecord.decodeAll(status, 2) }
        status.put("versionCode", 1)
        assertTrue(RecentRecord.decodeAll(status, 2).isEmpty())
        status.put("versionCode", 2).put("records", JSONArray(List(100) { record }))
        assertThrows(IllegalArgumentException::class.java) { RecentRecord.decodeAll(status, 2) }
    }

    @Test
    fun unsafeAndMalformedResponsePathsAreRejected() {
        listOf("../outside", "/absolute", "a//b").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                CleanupResult.decode(response().put("paths", JSONArray(listOf(path))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CleanupResult.decode(response().put("root", "/data/adb"))
        }
        assertThrows(IllegalStateException::class.java) {
            CleanupResult.decode(response().put("id", 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CleanupResult.decode(response().put("paths", JSONArray(List(513) { "a.log" })))
        }
    }
}
