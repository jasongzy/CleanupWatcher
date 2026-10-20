package com.jasongzy.cleanupwatcher

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun needsUpdate(version: Any?, required: Int): Boolean =
    version !is Int || version <= 0 || version < required

data class ComponentVersion(val name: String? = null, val code: Int? = null) {
    companion object {
        fun decode(name: Any? = null, code: Any?) =
            ComponentVersion(
                (name as? String)?.takeIf {
                    it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl)
                },
                (code as? Int)?.takeIf { it > 0 },
            )
    }
}

class Backend(private val context: Context) {
    fun observe() =
        streamProcess(listOf("su", "-c", "exec $BINARY observe")).map { line ->
            withContext(Dispatchers.Default) {
                check(line.startsWith('{') && line.endsWith('}')) { shellFailure(-1, line) }
                JSONObject(line).also {
                    check(it.optString("error").isEmpty()) { it.getString("error") }
                    require(it.getLong("sequence") > 0) { "config" }
                }
            }
        }

    suspend fun call(verb: String, input: String = ""): JSONObject =
        withContext(Dispatchers.IO) {
            require(
                verb in
                    setOf(
                        "status",
                        "rules",
                        "preview",
                        "clean",
                        "recover",
                        "pause",
                        "resume",
                        "refresh",
                        "stop",
                        "boot",
                        "clear-records",
                    )
            )
            runInterruptible { run("$BINARY ctl $verb", input) }
        }

    suspend fun manage(verb: String, input: String = ""): JSONObject =
        withContext(Dispatchers.IO) {
            require(
                verb in
                    setOf(
                        "inspect",
                        "install",
                        "uninstall",
                        "configure",
                        "module-install",
                        "module-uninstall",
                    )
            )
            val binary = payload()
            runInterruptible {
                val response =
                    run(
                        if (verb == "inspect")
                            "if magisk -V >/dev/null 2>&1; then $binary inspect 1; else $binary inspect 0; fi"
                        else "$binary $verb",
                        input,
                    )
                if (verb == "inspect") {
                    val version =
                        if (response.getBoolean("installed")) {
                            try {
                                run("$BINARY version", timeoutSeconds = 5)
                            } catch (e: Exception) {
                                if (e is CancellationException || e is InterruptedException) throw e
                                null
                            }
                        } else null
                    response.put("backendVersionCode", version?.opt("versionCode"))
                    response.put(
                        "backendNeedsUpdate",
                        response.getBoolean("present") &&
                            needsUpdate(
                                version?.opt("versionCode"),
                                response.getInt("requiredBackendVersionCode"),
                            ),
                    )
                    response.put(
                        "moduleNeedsUpdate",
                        response.getString("module") != "absent" &&
                            needsUpdate(
                                response.opt("moduleVersionCode"),
                                response.getInt("requiredModuleVersionCode"),
                            ),
                    )
                }
                response
            }
        }

    suspend fun browse(path: String, cursor: String = "-"): RootPage =
        withContext(Dispatchers.IO) {
            require(path.startsWith('/') && !path.contains('\u0000')) { "path" }
            val encoded = Rule.hex(path)
            val response = runInterruptible {
                run("${payload()} browse", "$encoded $cursor", timeoutSeconds = 10)
            }
            val actual = decodeName(response.getString("path")) ?: error("path")
            val entries = response.getJSONArray("entries")
            RootPage(
                actual,
                (0 until entries.length()).map { index ->
                    val item = entries.getJSONObject(index)
                    val hex = item.getString("name")
                    val decoded = decodeName(hex)
                    val name = decoded ?: "0x$hex"
                    val type = item.getString("type")
                    RootEntry(
                        path = actual.trimEnd('/') + "/" + name,
                        name = name,
                        type = type,
                        directory = item.getBoolean("directory") && decoded != null,
                        selectable = decoded != null && type in setOf("file", "directory"),
                        key = "$actual:$hex",
                        modified = item.getLong("modified"),
                        size = item.getLong("size"),
                    )
                },
                response.getString("cursor"),
            )
        }

    private fun payload(): String {
        val file = File(context.applicationInfo.nativeLibraryDir, "libcleanupwatcherd.so")
        check(file.isFile && file.canExecute()) { "install" }
        return quote(file.absolutePath)
    }

    private fun decodeName(hex: String): String? {
        return try {
            Rule.unhex(hex)
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    suspend fun start(): JSONObject = runInterruptible(Dispatchers.IO) { run("$BINARY start") }

    suspend fun bootStart(): JSONObject =
        runInterruptible(Dispatchers.IO) { run("$BINARY boot-start", timeoutSeconds = 6) }

    private fun run(command: String, input: String = "", timeoutSeconds: Long = 90): JSONObject {
        val (exit, output) = runProcess(listOf("su", "-c", command), input, timeoutSeconds * 1000)
        val response =
            output.trim().lineSequence().lastOrNull { it.startsWith('{') && it.endsWith('}') }
                ?: error(shellFailure(exit, output.trim()))
        return JSONObject(response).also {
            if (it.optString("error").isNotEmpty() && !it.has("paths")) error(it.getString("error"))
            check(exit == 0) { shellFailure(exit, output.trim()) }
        }
    }

    companion object {
        private const val BINARY = "/data/adb/cleanupwatcher/cleanupwatcherd"

        private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
