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
        val record = RecentRecord.decode(response().put("time", 123L).put("name", "*.log"))
        assertEquals(123L, record.time)
        assertEquals("*.log", record.name)
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
