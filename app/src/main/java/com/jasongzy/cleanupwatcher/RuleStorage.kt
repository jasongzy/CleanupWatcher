package com.jasongzy.cleanupwatcher

import org.json.JSONObject

internal suspend fun storeRules(
    previous: String,
    next: String,
    installed: Boolean,
    call: suspend (String) -> JSONObject,
    configure: suspend (String) -> JSONObject,
    persist: suspend () -> Unit,
): JSONObject? {
    var resume = false
    if (installed) {
        try {
            resume = !call("status").getBoolean("paused")
        } catch (e: Exception) {
            if (e.message?.substringBefore(':') != "offline") throw e
        }
    }
    val response =
        try {
            if (resume) call("pause")
            val configured = if (installed) configure(next) else null
            persist()
            configured
        } catch (e: Exception) {
            if (installed) {
                try {
                    configure(previous)
                    if (resume) call("resume")
                } catch (restoreError: Exception) {
                    e.addSuppressed(restoreError)
                }
            }
            throw e
        }
    return if (resume && response?.optBoolean("running") == true) call("resume") else response
}
