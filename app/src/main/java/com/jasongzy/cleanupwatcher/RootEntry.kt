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

internal fun rulesForEntries(entries: List<RootEntry>, folderAction: RuleAction): List<Rule> {
    require(folderAction != RuleAction.File) { "config" }
    require(entries.isNotEmpty() && entries.size <= 128) { "limit" }
    require(entries.all { it.selectable && it.type in setOf("file", "directory") }) { "type" }
    if (folderAction == RuleAction.EmptyChildren)
        require(entries.all { it.type == "directory" }) { "type" }
    val targets = entries.distinctBy { it.path }
    val effective =
        if (folderAction != RuleAction.FolderTree) targets
        else
            targets.filter { target ->
                targets.none { it.type == "directory" && target.path.startsWith(it.path + "/") }
            }
    return effective
        .map { entry ->
            val split = entry.path.lastIndexOf('/')
            require(split >= 0 && entry.path.substring(split + 1) == entry.name) { "path" }
            Rule(
                    root =
                        if (folderAction == RuleAction.EmptyChildren) entry.path
                        else entry.path.substring(0, split).ifEmpty { "/" },
                    pattern = if (folderAction == RuleAction.EmptyChildren) "*" else entry.name,
                    action = if (entry.type == "directory") folderAction else RuleAction.File,
                    literal = folderAction != RuleAction.EmptyChildren,
                )
                .also { require(it.error() == null) { it.error() ?: "config" } }
        }
        .also { require(Rule.encode(it).length <= 128 * 1024) { "limit" } }
}
