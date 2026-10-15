package com.jasongzy.cleanupwatcher

import org.json.JSONObject

data class CleanupResult(
    val id: String,
    val root: String,
    val paths: List<String>,
    val deleted: Int,
    val restored: Int?,
    val error: String,
) {
    companion object {
        fun decode(value: JSONObject): CleanupResult {
            val list = value.getJSONArray("paths")
            require(list.length() <= 512 && list.toString().toByteArray().size <= 128 * 1024) {
                "limit"
            }
            val result =
                CleanupResult(
                    value.get("id") as? String ?: error("config"),
                    value.get("root") as? String ?: error("config"),
                    (0 until list.length()).map { list.get(it) as? String ?: error("config") },
                    if (value.has("deleted")) value.getInt("deleted") else 0,
                    if (value.has("restored")) value.getInt("restored") else null,
                    value.get("error") as? String ?: error("config"),
                )
            require(
                result.id.isNotEmpty() &&
                    result.id.toByteArray().size <= 64 &&
                    Rule.validRoot(result.root) &&
                    result.paths.all(Rule::validRelative) &&
                    result.deleted in 0..512 &&
                    (result.restored == null || result.restored >= 0) &&
                    (value.has("deleted") || result.restored != null)
            ) {
                "config"
            }
            return result
        }
    }
}

data class RecentRecord(val time: Long, val name: String, val deleted: Int, val error: String) {
    companion object {
        fun decode(value: JSONObject): RecentRecord {
            val result = CleanupResult.decode(value)
            return RecentRecord(
                value.getLong("time"),
                value.get("name") as? String ?: error("config"),
                result.deleted,
                result.error,
            )
        }
    }
}
