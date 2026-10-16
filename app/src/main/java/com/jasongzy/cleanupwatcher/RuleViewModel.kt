package com.jasongzy.cleanupwatcher

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class UiState(
    val rules: List<Rule> = emptyList(),
    val connected: Boolean = false,
    val backendPresent: Boolean = false,
    val installed: Boolean = false,
    val checked: Boolean = false,
    val requiredBackendVersionCode: Int = 0,
    val backendNeedsUpdate: Boolean = false,
    val moduleNeedsUpdate: Boolean = false,
    val backendVersion: ComponentVersion = ComponentVersion(),
    val moduleVersion: ComponentVersion = ComponentVersion(),
    val magisk: Boolean = false,
    val module: String = "absent",
    val paused: Boolean = false,
    val boot: Boolean = false,
    val watches: Int = 0,
    val checking: Boolean = false,
    val health: Map<String, String> = emptyMap(),
    val records: List<RecentRecord> = emptyList(),
    val busy: Boolean = false,
    val cleaning: Boolean = false,
    val bootBusy: Boolean = false,
    val colorMode: String = "system",
    val pendingImport: List<Rule>? = null,
    val batchPreview: List<CleanupResult>? = null,
    val newRuleIds: Set<String> = emptySet(),
    val importedRuleIds: Set<String> = emptySet(),
    val error: String? = null,
    val preview: CleanupResult? = null,
) {
    val moduleActive: Boolean
        get() = magisk && module == "enabled"

    val startsAtBoot: Boolean
        get() = boot || moduleActive

    val monitoring: Boolean
        get() = connected && !paused

    val ruleErrorCount: Int
        get() = rules.count { it.enabled && !health[it.id].isNullOrEmpty() }
}

data class Notice(val message: Int, val error: String? = null, val synced: Boolean = false)

class RuleViewModel(app: Application) : AndroidViewModel(app) {
    private val backend = Backend(app)
    private val file = AtomicFile(File(app.filesDir, "rules"))
    private val settings = app.getSharedPreferences("settings", 0)
    private val mutable =
        MutableStateFlow(
            UiState(busy = true, colorMode = settings.getString("colorMode", "system") ?: "system")
        )
    private var rulesLoaded = false
    private var generation = 0
    private var settling: Job? = null
    private var refreshRecordsPending = false
    private var bootChange: Job? = null
    private val messages = Channel<Notice>(Channel.BUFFERED)
    val state = mutable.asStateFlow()
    val notices = messages.receiveAsFlow()

    private fun notify(message: Int) {
        messages.trySend(Notice(message))
    }

    private fun fail(error: String) {
        if (error.substringBefore(':') == "offline") disconnect()
        mutable.value = mutable.value.copy(error = error)
        messages.trySend(Notice(R.string.operation_failed, error))
    }

    private fun disconnect() {
        refreshRecordsPending = false
        mutable.value =
            mutable.value.copy(
                connected = false,
                paused = false,
                checking = false,
                watches = 0,
                health = emptyMap(),
                records = emptyList(),
            )
    }

    private fun resultNotice(result: CleanupResult, success: Int? = null) {
        val error = result.error
        if (error == "cancelled") notify(R.string.error_cancelled)
        else if (error.isNotEmpty()) messages.trySend(Notice(R.string.operation_failed, error))
        else if (success != null) notify(success)
    }

    init {
        viewModelScope.launch {
            try {
                val local =
                    withContext(Dispatchers.IO) {
                        if (file.baseFile.exists())
                            file.openRead().use { stream ->
                                val bytes = stream.readBounded(128 * 1024)
                                Rule.decode(bytes.toString(Charsets.UTF_8))
                            }
                        else emptyList()
                    }
                mutable.value = mutable.value.copy(rules = local.sortedRules())
                rulesLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(busy = false)
                fail(e.message ?: "config")
                return@launch
            }
            mutable.value = mutable.value.copy(busy = false)
            task { refreshState(syncRules = true) }
        }
    }

    private fun task(
        cleanup: Boolean = false,
        queued: Boolean = false,
        action: suspend () -> Unit,
    ) {
        if (mutable.value.busy && !queued) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (queued) mutable.first { !it.busy }
            mutable.value = mutable.value.copy(busy = true, cleaning = cleanup, error = null)
            try {
                bootChange?.join()
                generation++
                settling?.cancel()
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(if (e is JSONException) "config: ${e.message}" else e.message ?: "backend")
            } finally {
                mutable.value = mutable.value.copy(busy = false, cleaning = false)
                if (
                    mutable.value.connected &&
                        (mutable.value.checking || refreshRecordsPending) &&
                        mutable.value.error == null &&
                        settling?.isActive != true
                )
                    awaitReady()
            }
        }
    }

    private suspend fun persist(rules: List<Rule>) =
        withContext(Dispatchers.IO) {
            val stream = file.startWrite()
            try {
                stream.write(Rule.encode(rules).toByteArray())
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
        }

    private suspend fun accept(response: JSONObject) {
        settings.edit {
            putBoolean("boot", response.getBoolean("boot"))
        }
        val list = response.getJSONArray("records")
        val records =
            (0 until list.length())
                .map { index ->
                    RecentRecord.decode(list.getJSONObject(index))
                }
                .reversed()
        val health = response.optJSONObject("health") ?: JSONObject()
        mutable.value =
            mutable.value.copy(
                connected = true,
                backendVersion =
                    ComponentVersion.decode(
                        response.opt("versionName"),
                        response.opt("versionCode"),
                    ),
                backendNeedsUpdate =
                    mutable.value.backendNeedsUpdate ||
                        needsUpdate(
                            response.opt("versionCode"),
                            mutable.value.requiredBackendVersionCode,
                        ),
                paused = response.getBoolean("paused"),
                boot = response.getBoolean("boot"),
                watches = response.getInt("watches"),
                checking = response.optBoolean("checking"),
                health = health.keys().asSequence().associateWith { health.getString(it) },
                records = records,
            )
        if (mutable.value.checking) awaitReady()
    }

    private fun awaitReady() {
        val current = generation
        settling?.cancel()
        settling = viewModelScope.launch {
            try {
                val ready = backend.call("settled")
                if (generation != current) return@launch
                accept(ready)
                if (ready.getBoolean("checking")) return@launch
                if (refreshRecordsPending) {
                    mutable.first { !it.busy && !it.bootBusy }
                    delay(2000)
                    val recent = backend.call("status")
                    if (generation == current) {
                        refreshRecordsPending = false
                        accept(recent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == current) fail(e.message ?: "backend")
            }
        }
    }

    private suspend fun synchronize(response: JSONObject) {
        check(!needsUpdate(response.opt("versionCode"), mutable.value.requiredBackendVersionCode)) {
            "update"
        }
        val rules = mutable.value.rules
        val update =
            withContext(Dispatchers.Default) {
                if (Rule.decode(response.getString("rules")) == rules) null else Rule.encode(rules)
            }
        if (update != null) accept(backend.call("rules", update)) else accept(response)
    }

    private suspend fun refreshState(syncRules: Boolean = false) {
        check(rulesLoaded) { "config" }
        val system = backend.manage("inspect")
        val installed = system.getBoolean("installed")
        val boot = system.getBoolean("boot")
        settings.edit {
            if (installed) putBoolean("boot", boot) else remove("boot")
        }
        mutable.value =
            mutable.value.copy(
                backendPresent = system.getBoolean("present"),
                installed = installed,
                requiredBackendVersionCode = system.getInt("requiredBackendVersionCode"),
                backendNeedsUpdate = system.getBoolean("backendNeedsUpdate"),
                moduleNeedsUpdate = system.getBoolean("moduleNeedsUpdate"),
                backendVersion =
                    ComponentVersion.decode(
                        system.opt("backendVersionName"),
                        system.opt("backendVersionCode"),
                    ),
                moduleVersion =
                    ComponentVersion.decode(
                        system.opt("moduleVersionName"),
                        system.opt("moduleVersionCode"),
                    ),
                magisk = system.getBoolean("magisk"),
                module = system.getString("module"),
                boot = boot,
            )
        if (!installed) {
            disconnect()
            mutable.value = mutable.value.copy(checked = true)
            return
        }
        try {
            val status = backend.call("status")
            if (syncRules && !mutable.value.backendNeedsUpdate) synchronize(status)
            else accept(status)
        } catch (e: Exception) {
            disconnect()
            if (e.message?.substringBefore(':') != "offline") throw e
            if (syncRules && !mutable.value.backendNeedsUpdate) {
                val encoded = withContext(Dispatchers.Default) { Rule.encode(mutable.value.rules) }
                val configured = backend.manage("configure", encoded)
                if (configured.optBoolean("running")) accept(configured)
            }
        } finally {
            mutable.value = mutable.value.copy(checked = true)
        }
        if (syncRules && !mutable.value.backendNeedsUpdate) notify(R.string.rules_synced)
    }

    fun refresh() = task { refreshState() }

    private suspend fun startBackend() {
        backend.start()
        // Startup is asynchronous; retry only this bounded control handshake.
        var response: JSONObject? = null
        repeat(8) {
            if (response == null) {
                kotlinx.coroutines.delay(150)
                try {
                    response = backend.call("status")
                } catch (e: Exception) {
                    if (e.message?.substringBefore(':') != "offline") throw e
                }
            }
        }
        val live = response ?: error("offline")
        synchronize(live)
    }

    fun install() = task {
        val updating = mutable.value.backendNeedsUpdate
        backend.manage("install")
        startBackend()
        refreshState()
        notify(if (updating) R.string.backend_updated_synced else R.string.backend_installed_synced)
    }

    fun start() = task {
        startBackend()
        if (mutable.value.paused) accept(backend.call("resume"))
        refreshState()
        notify(R.string.backend_started_synced)
    }

    fun uninstall() = task {
        try {
            backend.manage("uninstall")
            mutable.value = mutable.value.copy(preview = null, connected = false)
        } catch (e: Exception) {
            try {
                refreshState()
            } catch (refreshError: Exception) {
                e.addSuppressed(refreshError)
            }
            throw e
        }
        refreshState()
        notify(R.string.backend_removed)
    }

    fun module(install: Boolean) = task {
        if (install) check(mutable.value.magisk) { "magisk" }
        backend.manage(if (install) "module-install" else "module-uninstall")
        refreshState()
        notify(if (install) R.string.module_installed else R.string.module_removed)
    }

    fun stop() = task {
        backend.call("stop")
        disconnect()
        notify(R.string.backend_stopped)
    }

    fun boot(enabled: Boolean) {
        if (mutable.value.moduleActive) {
            notify(R.string.boot_managed_by_magisk)
            return
        }
        if (mutable.value.busy || mutable.value.bootBusy) return
        generation++
        settling?.cancel()
        mutable.value = mutable.value.copy(bootBusy = true, error = null)
        bootChange = viewModelScope.launch {
            try {
                val response = backend.call("boot", if (enabled) "1" else "0")
                accept(response)
                notify(if (enabled) R.string.boot_enabled else R.string.boot_disabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "backend")
            } finally {
                mutable.value = mutable.value.copy(bootBusy = false)
                if (
                    refreshRecordsPending &&
                        mutable.value.connected &&
                        mutable.value.error == null &&
                        settling?.isActive != true
                )
                    awaitReady()
            }
        }
    }

    private suspend fun updateRules(next: List<Rule>) {
        check(!mutable.value.backendNeedsUpdate) { "update" }
        val rules = next.sortedRules()
        val encoded =
            withContext(Dispatchers.Default) {
                rules.forEach { require(it.error() == null) { it.error() ?: "config" } }
                require(rules.size <= 128) { "limit" }
                Rule.encode(rules).also {
                    require(it.length <= 128 * 1024) { "limit" }
                    require(Rule.decode(it) == rules) { "config" }
                }
            }
        withContext(NonCancellable) {
            var resume = false
            if (mutable.value.installed) {
                try {
                    val status = backend.call("status")
                    resume = !status.getBoolean("paused")
                    if (resume) accept(backend.call("pause"))
                } catch (e: Exception) {
                    if (e.message?.substringBefore(':') != "offline") throw e
                }
            }
            val response =
                if (mutable.value.installed) backend.manage("configure", encoded) else null
            persist(rules)
            mutable.value =
                mutable.value.copy(
                    rules = rules,
                    preview = null,
                    batchPreview = null,
                    newRuleIds = emptySet(),
                    importedRuleIds = emptySet(),
                )
            if (response?.optBoolean("running") == true)
                accept(if (resume) backend.call("resume") else response)
            else if (response != null) disconnect()
        }
    }

    private fun ruleNotice(message: Int) {
        messages.trySend(Notice(message, synced = mutable.value.installed))
    }

    fun save(next: List<Rule>, replacedId: String, saved: () -> Unit) = task {
        val added = next.map { it.id }.toSet() - mutable.value.rules.map { it.id }.toSet()
        val rules = replaceRules(mutable.value.rules, next, replacedId)
        updateRules(rules)
        refreshRecordsPending = mutable.value.connected
        saved()
        ruleNotice(R.string.rules_saved)
        if (added.isNotEmpty()) {
            mutable.value = mutable.value.copy(newRuleIds = added)
            if (mutable.value.connected) preparePreview(added)
        }
    }

    fun remove(ids: Set<String>) = task {
        updateRules(mutable.value.rules.filterNot { it.id in ids })
        ruleNotice(R.string.rules_removed)
    }

    fun enable(ids: Set<String>, enabled: Boolean) = task {
        check(mutable.value.connected || !enabled) { "offline" }
        updateRules(
            mutable.value.rules.map { if (it.id in ids) it.copy(enabled = enabled) else it }
        )
        if (enabled) refreshRecordsPending = mutable.value.connected
        ruleNotice(if (enabled) R.string.rules_enabled else R.string.rules_disabled)
    }

    fun previewBatch(ids: Set<String>) = task {
        if (!mutable.value.connected) {
            startBackend()
            refreshState()
            notify(R.string.backend_started_synced)
        }
        preparePreview(ids)
    }

    private suspend fun preparePreview(ids: Set<String>) {
        val selected = mutable.value.rules.filter { it.id in ids }
        check(selected.isNotEmpty() && selected.size == ids.size) { "config" }
        val previews = mutableListOf<CleanupResult>()
        var count = 0
        var bytes = 0
        for (rule in selected) {
            val result = CleanupResult.decode(backend.call("preview", rule.id))
            check(result.error.isEmpty()) { result.error }
            count += result.paths.size
            bytes += JSONArray(result.paths).toString().toByteArray().size
            check(count <= 512 && bytes <= 128 * 1024) { "limit" }
            previews += result
        }
        mutable.value = mutable.value.copy(batchPreview = previews)
    }

    fun dismissBatchPreview() {
        if (!mutable.value.busy)
            mutable.value =
                mutable.value.copy(
                    batchPreview = null,
                    newRuleIds = emptySet(),
                    importedRuleIds = emptySet(),
                )
    }

    fun clearRecords() = task {
        accept(backend.call("clear-records"))
        notify(R.string.records_cleared)
    }

    fun colorMode(value: String) {
        require(value in setOf("system", "light", "dark"))
        settings.edit { putString("colorMode", value) }
        mutable.value = mutable.value.copy(colorMode = value)
    }

    fun readImport(uri: Uri) =
        task(queued = true) {
            val rules =
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri).use { stream
                        ->
                        checkNotNull(stream) { "document" }
                        val bytes = stream.readBounded(RuleJson.MAX_BYTES)
                        val text =
                            Charsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString()
                        RuleJson.decode(text)
                    }
                }
            mutable.value = mutable.value.copy(pendingImport = rules)
        }

    fun importRules(replace: Boolean) = task {
        val pending = mutable.value.pendingImport ?: return@task
        val enabledIds = pending.filter { it.enabled }.map { it.id }.toSet()
        val rules =
            withContext(Dispatchers.Default) {
                RuleJson.combine(mutable.value.rules, pending, replace)
            }
        updateRules(rules)
        mutable.value = mutable.value.copy(pendingImport = null, importedRuleIds = enabledIds)
        ruleNotice(R.string.import_success)
        if (enabledIds.isNotEmpty() && mutable.value.connected) preparePreview(enabledIds)
    }

    fun dismissImport() {
        mutable.value = mutable.value.copy(pendingImport = null)
    }

    fun exportRules(uri: Uri) =
        task(queued = true) {
            val rules = mutable.value.rules
            withContext(Dispatchers.IO) {
                val data = RuleJson.encode(rules).toByteArray()
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use {
                    stream ->
                    checkNotNull(stream) { "document" }
                    stream.write(data)
                }
            }
            notify(R.string.export_success)
        }

    fun preview(id: String) = task {
        val result = CleanupResult.decode(backend.call("preview", id))
        mutable.value = mutable.value.copy(preview = result)
        resultNotice(result)
    }

    fun dismissPreview() {
        mutable.value = mutable.value.copy(preview = null)
    }

    fun dismissError() {
        mutable.value = mutable.value.copy(error = null)
    }

    fun cancel() {
        generation++
        settling?.cancel()
        viewModelScope.launch {
            try {
                accept(backend.call("pause"))
                notify(R.string.monitoring_stopped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "backend")
            }
        }
    }

    fun clean(id: String) =
        task(cleanup = true) {
            val result = CleanupResult.decode(backend.call("clean", id))
            accept(backend.call("status"))
            mutable.value = mutable.value.copy(preview = result)
            resultNotice(result, R.string.cleanup_finished)
        }

    fun recover(id: String) = task {
        val result = CleanupResult.decode(backend.call("recover", id))
        accept(backend.call("status"))
        mutable.value = mutable.value.copy(preview = result)
        resultNotice(result, R.string.recovery_finished)
    }
}
