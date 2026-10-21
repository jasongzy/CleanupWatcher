package com.jasongzy.cleanupwatcher

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuleStorageTest {
    @Test
    fun failedUpdatesRestorePreviousRulesBeforeResuming() = runBlocking {
        for (failure in listOf("pause", "new", "persist")) {
            val events = mutableListOf<String>()
            val problem = IllegalStateException(failure)
            try {
                storeRules(
                    "old",
                    "new",
                    true,
                    call = {
                        events += it
                        if (it == failure) throw problem
                        JSONObject().put("paused", false).put("running", true)
                    },
                    configure = {
                        events += it
                        if (it == failure) throw problem
                        JSONObject().put("running", true)
                    },
                    persist = {
                        events += "persist"
                        if (failure == "persist") throw problem
                    },
                )
                fail("Expected update failure")
            } catch (e: IllegalStateException) {
                assertSame(problem, e)
            }
            assertEquals(listOf("old", "resume"), events.takeLast(2))
        }
    }

    @Test
    fun failedRollbackKeepsMonitoringPaused() = runBlocking {
        val events = mutableListOf<String>()
        val rollback = IllegalStateException("rollback")
        try {
            storeRules(
                "old",
                "new",
                true,
                call = {
                    events += it
                    JSONObject().put("paused", false)
                },
                configure = {
                    events += it
                    if (it == "old") throw rollback
                    JSONObject().put("running", true)
                },
                persist = { error("disk") },
            )
            fail("Expected write failure")
        } catch (e: IllegalStateException) {
            assertEquals("disk", e.message)
            assertArrayEquals(arrayOf(rollback), e.suppressed)
        }
        assertEquals(listOf("status", "pause", "new", "old"), events)
    }

    @Test
    fun successfulUpdatesPreserveMonitoringState() = runBlocking {
        for (paused in listOf(false, true)) {
            val events = mutableListOf<String>()
            storeRules(
                "old",
                "new",
                true,
                call = {
                    events += it
                    JSONObject().put("paused", paused).put("running", true)
                },
                configure = {
                    events += it
                    JSONObject().put("running", true)
                },
                persist = { events += "persist" },
            )
            assertEquals(
                if (paused) listOf("status", "new", "persist")
                else listOf("status", "pause", "new", "persist", "resume"),
                events,
            )
        }
    }
}
