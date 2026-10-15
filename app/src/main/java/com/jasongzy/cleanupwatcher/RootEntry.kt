package com.jasongzy.cleanupwatcher

data class RootEntry(
    val path: String,
    val name: String,
    val type: String,
    val directory: Boolean = type == "directory",
    val selectable: Boolean = type in setOf("file", "directory"),
    val key: String = path,
    val modified: Long = 0,
    val size: Long = -1,
)

data class RootPage(val path: String, val entries: List<RootEntry>, val cursor: String)

internal fun rulesForEntries(entries: List<RootEntry>, emptyOnly: Boolean): List<Rule> {
    require(entries.isNotEmpty() && entries.size <= 128) { "limit" }
    require(entries.all { it.selectable && it.type in setOf("file", "directory") }) { "type" }
    val targets = entries.distinctBy { it.path }
    val effective =
        if (emptyOnly) targets
        else
            targets.filter { target ->
                targets.none { it.type == "directory" && target.path.startsWith(it.path + "/") }
            }
    return effective
        .map { entry ->
            val split = entry.path.lastIndexOf('/')
            require(split >= 0 && entry.path.substring(split + 1) == entry.name) { "path" }
            Rule(
                    root = entry.path.substring(0, split).ifEmpty { "/" },
                    pattern = entry.name,
                    action =
                        if (entry.type == "directory") {
                            if (emptyOnly) 1 else 2
                        } else 0,
                    literal = true,
                )
                .also { require(it.error() == null) { it.error() ?: "config" } }
        }
        .also { require(Rule.encode(it).length <= 128 * 1024) { "limit" } }
}
