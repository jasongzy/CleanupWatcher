package com.jasongzy.cleanupwatcher

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object RuleJson {
    const val MAX_BYTES = 512 * 1024

    fun combine(existing: List<Rule>, imported: List<Rule>, replace: Boolean): List<Rule> {
        val rules =
            (if (replace) emptyList() else existing) + imported.map { it.copy(enabled = false) }
        val encoded = Rule.encode(rules)
        require(rules.size <= 128 && encoded.length <= 128 * 1024) { "limit" }
        require(Rule.decode(encoded) == rules) { "config" }
        return rules.sortedRules()
    }

    fun encode(rules: List<Rule>): String {
        Rule.decode(Rule.encode(rules))
        return JSONObject()
            .put("format", "cleanupwatcher")
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put(
                "rules",
                JSONArray().apply {
                    rules.sortedRules().forEach { rule ->
                        put(
                            JSONObject()
                                .put("root", rule.root)
                                .put("name", rule.pattern)
                                .put(
                                    "target",
                                    listOf("file", "empty-folder", "folder-tree")[rule.action],
                                )
                                .put(
                                    "match",
                                    if (rule.literal || rule.action == 2) "exact" else "glob",
                                )
                                .put("recursive", rule.recursive)
                                .put("enabled", rule.enabled)
                                .put("whitelist", JSONArray(rule.whitelist))
                        )
                    }
                },
            )
            .toString(2)
            .also { require(it.toByteArray().size <= MAX_BYTES) { "limit" } }
    }

    fun decode(text: String): List<Rule> {
        require(text.toByteArray().size <= MAX_BYTES) { "limit" }
        var depth = 0
        var quoted = false
        var escaped = false
        var literal = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else
                when (char) {
                    '"' -> {
                        require(!literal) { "config" }
                        quoted = true
                    }
                    '{',
                    '[' -> {
                        literal = false
                        require(++depth <= 8) { "config" }
                    }
                    '}',
                    ']' -> {
                        literal = false
                        require(--depth >= 0) { "config" }
                    }
                    in ",: \t\r\n" -> literal = false
                    in "'/#;=\\" -> throw IllegalArgumentException("config")
                    else -> literal = true
                }
        }
        require(depth == 0 && !quoted) { "config" }
        val input = JSONTokener(text)
        val document = input.nextValue() as? JSONObject ?: error("config")
        require(input.nextClean() == '\u0000') { "config" }
        require(document.keys().asSequence().toSet() - "versionCode" == setOf("format", "rules")) {
            "config"
        }
        require(document.get("format") == "cleanupwatcher") { "config" }
        val versionCode = document.opt("versionCode")
        require(versionCode == null || versionCode is Int && versionCode > 0) { "config" }
        val entries = document.getJSONArray("rules")
        require(entries.length() <= 128) { "limit" }
        return (0 until entries.length())
            .map { index ->
                val entry = entries.getJSONObject(index)
                require(
                    entry.keys().asSequence().toSet() ==
                        setOf(
                            "root",
                            "name",
                            "target",
                            "match",
                            "recursive",
                            "enabled",
                            "whitelist",
                        )
                ) {
                    "config"
                }
                fun string(key: String): String = entry.get(key) as? String ?: error("config")
                val action = listOf("file", "empty-folder", "folder-tree").indexOf(string("target"))
                val match = string("match")
                require(action >= 0 && match in setOf("exact", "glob")) { "config" }
                require(action != 2 || match == "exact") { "directory_pattern" }
                require(entry.get("recursive") is Boolean && entry.get("enabled") is Boolean) {
                    "config"
                }
                val whitelist = entry.getJSONArray("whitelist")
                require(whitelist.length() <= 128) { "limit" }
                Rule(
                        root = string("root"),
                        pattern = string("name"),
                        action = action,
                        recursive = entry.getBoolean("recursive"),
                        enabled = entry.getBoolean("enabled"),
                        literal = match == "exact",
                        whitelist =
                            (0 until whitelist.length()).map {
                                whitelist.get(it) as? String ?: error("whitelist")
                            },
                    )
                    .also { require(it.error() == null) { it.error() ?: "config" } }
            }
            .also { rules ->
                val encoded = Rule.encode(rules)
                require(encoded.length <= 128 * 1024) { "limit" }
                require(Rule.decode(encoded) == rules) { "config" }
            }
            .sortedRules()
    }
}
