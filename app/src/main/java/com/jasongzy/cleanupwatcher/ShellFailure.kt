package com.jasongzy.cleanupwatcher

internal fun shellFailure(exitCode: Int, output: String): String {
    val code = if (exitCode == 127) "offline" else "execution"
    return "$code: exit=$exitCode\n${output.trim().takeLast(800)}".trimEnd()
}
