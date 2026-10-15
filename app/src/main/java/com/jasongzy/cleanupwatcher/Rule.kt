package com.jasongzy.cleanupwatcher

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal fun List<Rule>.sortedRules(): List<Rule> =
    sortedWith(compareBy<Rule>({ it.root }, { it.pattern }, { it.action }))

internal fun replaceRules(
    existing: List<Rule>,
    replacements: List<Rule>,
    replacedId: String,
): List<Rule> {
    val previous = existing.associateBy { it.id }
    return (existing.filterNot { it.id == replacedId } +
            replacements.map { it.copy(enabled = previous[it.id]?.enabled == true) })
        .sortedRules()
}

// Logical scope identifiers are validated and resolved by the root backend.
@SuppressLint("SdCardPath")
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val root: String = "/sdcard",
    val pattern: String = "",
    val action: Int = 0,
    val recursive: Boolean = false,
    val enabled: Boolean = false,
    val whitelist: List<String> = emptyList(),
    val literal: Boolean = false,
) {
    fun withAction(value: Int): Rule =
        copy(action = value, literal = literal || action == 2 || value == 2)

    fun error(): String? =
        when {
            id.isEmpty() || id.toByteArray().size > 64 || id.contains('\u0000') -> "config"
            !validRoot(root) -> "scope"
            pattern.isEmpty() ||
                (pattern.isBlank() && !literal && action != 2) ||
                pattern.toByteArray().size > 255 ||
                pattern.contains('/') ||
                pattern.contains('\u0000') -> "pattern"
            action !in 0..2 -> "config"
            action == 2 &&
                (pattern in listOf(".", "..") ||
                    pattern.equals(".cleanupwatcher", ignoreCase = true)) -> "directory_pattern"
            whitelist.size > 128 ||
                whitelist.any {
                    !validRelative(it) || it.contains('\n') || it.contains('\r')
                } -> "whitelist"
            else -> null
        }

    companion object {
        fun validRelative(path: String): Boolean =
            path.isNotEmpty() &&
                path.toByteArray().size <= 4096 &&
                !path.contains('\u0000') &&
                path.split('/').all {
                    it.isNotEmpty() && it != "." && it != ".." && it.toByteArray().size <= 255
                }

        fun validRoot(path: String): Boolean {
            if (
                !path.startsWith('/') ||
                    path.toByteArray().size > 4096 ||
                    !validRelative(path.drop(1)) ||
                    path.split('/').any { it.equals(".cleanupwatcher", ignoreCase = true) }
            )
                return false
            if (
                listOf("/sdcard", "/storage/emulated/0", "/data/media/0").any {
                    path == it || path.startsWith("$it/")
                }
            )
                return true
            return Regex("/data/user/0/[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/cache(?:/.*)?")
                .matches(path)
        }

        fun encode(rules: List<Rule>): String = buildString {
            append("CW\n")
            rules.forEach { rule ->
                append(
                    listOf(
                            hex(rule.id),
                            hex(rule.root),
                            hex(rule.pattern),
                            rule.action,
                            if (rule.recursive) 1 else 0,
                            if (rule.enabled) 1 else 0,
                            if (rule.literal) 1 else 0,
                            hex(rule.whitelist.joinToString("\n")),
                        )
                        .joinToString(" ")
                )
                append('\n')
            }
        }

        fun decode(text: String): List<Rule> {
            require(text.length <= 128 * 1024) { "limit" }
            val lines = text.removeSuffix("\n").split('\n')
            require(lines.firstOrNull() == "CW")
            return lines
                .drop(1)
                .map { line ->
                    val parts = line.split(' ')
                    require(
                        parts.size == 8 &&
                            parts[4] in listOf("0", "1") &&
                            parts[5] in listOf("0", "1") &&
                            parts[6] in listOf("0", "1")
                    )
                    Rule(
                            unhex(parts[0]),
                            unhex(parts[1]),
                            unhex(parts[2]),
                            parts[3].toInt(),
                            parts[4] == "1",
                            parts[5] == "1",
                            unhex(parts[7], 64 * 1024).removeSuffix("\n").let {
                                if (it.isEmpty()) emptyList() else it.split('\n')
                            },
                            parts[6] == "1",
                        )
                        .also { require(it.error() == null) }
                }
                .also { rules ->
                    require(rules.size <= 128 && rules.map { it.id }.distinct().size == rules.size)
                }
        }

        internal fun hex(value: String): String = buildString {
            val digits = "0123456789abcdef"
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 255
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 15])
            }
        }
            .ifEmpty { "-" }

        internal fun unhex(value: String, maxBytes: Int = 4096): String {
            if (value == "-") return ""
            require(
                value.length % 2 == 0 &&
                    value.length / 2 <= maxBytes &&
                    value.all { it in "0123456789abcdef" }
            )
            val bytes =
                ByteArray(value.length / 2) {
                    value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }
    }
}
