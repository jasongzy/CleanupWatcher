package com.jasongzy.cleanupwatcher

import org.junit.Assert.*
import org.junit.Test

class BackendStatusTest {
    @Test
    fun componentVersionsKeepActualNamesAndValidateDisplayValues() {
        assertEquals(ComponentVersion("1.0.0", 1), ComponentVersion.decode("1.0.0", 1))
        assertEquals(ComponentVersion("v1.0", 1), ComponentVersion.decode("v1.0", 1))
        assertEquals(ComponentVersion(code = 2), ComponentVersion.decode(null, 2))
        assertEquals(ComponentVersion(name = "v0.9"), ComponentVersion.decode("v0.9", null))
        for (name in listOf(null, "", " ", "v1\n", "x".repeat(65), 1)) {
            assertNull(ComponentVersion.decode(name, 1).name)
        }
        for (code in listOf(null, 0, -1, "1", 1.0, 2147483648L)) {
            assertNull(ComponentVersion.decode("v1.0", code).code)
        }
    }

    @Test
    fun missingMalformedAndOlderVersionsNeedUpdating() {
        for (version in listOf(null, 0, -1, "1", 1.0, 2147483648L)) {
            assertTrue(needsUpdate(version, 1))
        }
        assertTrue(needsUpdate(1, 2))
        assertFalse(needsUpdate(1, 1))
        assertFalse(needsUpdate(2, 1))
    }

    @Test
    fun updateWarningsAreIndependentOfProcessAndModuleActivation() {
        val state =
            UiState(
                checked = true,
                installed = true,
                connected = true,
                magisk = true,
                module = "enabled",
                backendNeedsUpdate = true,
                moduleNeedsUpdate = true,
            )
        assertEquals(R.string.needs_update, backendStatusResource(state))
        assertEquals(R.string.needs_update, backendStatusResource(state.copy(connected = false)))
        assertEquals(R.string.needs_update, backendStatusResource(state.copy(checking = true)))
        assertEquals(R.string.needs_update, moduleStatusResource(state))
        assertEquals(R.string.needs_update, moduleStatusResource(state.copy(module = "disabled")))
        assertTrue(state.moduleActive)
        assertEquals(
            R.string.module_removing,
            moduleStatusResource(state.copy(module = "removing")),
        )
        assertEquals(
            R.string.module_enabled,
            moduleStatusResource(state.copy(moduleNeedsUpdate = false)),
        )
        assertEquals(
            R.string.backend_ready,
            backendStatusResource(state.copy(backendNeedsUpdate = false)),
        )
        assertEquals(R.string.status_unknown, backendStatusResource(state.copy(checked = false)))
        assertEquals(R.string.status_unknown, moduleStatusResource(state.copy(checked = false)))
    }

    @Test
    fun startupWaitsForTheProcessStatusAfterInspectingInstallation() {
        val initial = UiState(busy = true)
        val installed = initial.copy(installed = true, backendPresent = true)
        val running = installed.copy(connected = true)
        for (state in listOf(initial, installed, running)) {
            assertEquals(R.string.status_unknown, backendStatusResource(state))
        }
        val ready = running.copy(checked = true, rules = listOf(Rule(enabled = true)))
        assertEquals(R.string.ready, backendStatusResource(ready))
        assertEquals(R.string.stopped, backendStatusResource(installed.copy(checked = true)))
        assertEquals(R.string.not_installed, backendStatusResource(initial.copy(checked = true)))
        assertEquals(R.string.status_failed, backendStatusResource(installed.copy(error = "root")))
    }

    @Test
    fun onlyEnabledRulesContributeToTheErrorStatus() {
        val healthy = Rule(id = "healthy", enabled = true)
        val failed = Rule(id = "failed", enabled = true)
        val disabled = Rule(id = "disabled")
        val state =
            UiState(
                checked = true,
                connected = true,
                installed = true,
                rules = listOf(healthy, failed, disabled),
                health =
                    mapOf(
                        "healthy" to "",
                        "failed" to "watch",
                        "disabled" to "path",
                        "removed" to "rate",
                    ),
            )
        assertEquals(1, state.ruleErrorCount)
        assertEquals(R.string.rule_errors, backendStatusResource(state))
        val allFailed = state.copy(health = state.health + ("healthy" to "watch"))
        assertEquals(2, allFailed.ruleErrorCount)
        assertEquals(R.string.rule_errors, backendStatusResource(allFailed))
        assertEquals(R.string.checking, backendStatusResource(state.copy(checking = true)))
        assertEquals(R.string.stopped, backendStatusResource(state.copy(paused = true)))
        assertEquals(R.string.ready, backendStatusResource(state.copy(health = emptyMap())))
    }
}
