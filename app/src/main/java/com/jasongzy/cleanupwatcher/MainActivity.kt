package com.jasongzy.cleanupwatcher

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        setContent {
            val model: RuleViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val dark =
                when (state.colorMode) {
                    "light" -> false
                    "dark" -> true
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                }
            val scheme =
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { WatcherScreen(model) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolButton(icon: Int, label: Int, enabled: Boolean = true, action: () -> Unit) {
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(label)) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = action, enabled = enabled) {
            Icon(painterResource(icon), stringResource(label))
        }
    }
}

@Composable
private fun StatusIcon(
    active: Boolean,
    paused: Boolean,
    checking: Boolean,
    error: Boolean,
    removing: Boolean = false,
    absent: Boolean = false,
    stopped: Boolean = false,
    warning: Boolean = false,
) {
    if (checking && !paused && !warning) {
        CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
        return
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color =
        when {
            warning -> if (dark) Color(0xFFE8BF6A) else Color(0xFF946100)
            error -> MaterialTheme.colorScheme.error
            paused || stopped -> if (dark) Color(0xFFE8BF6A) else Color(0xFF946100)
            active -> if (dark) Color(0xFF68DBA4) else Color(0xFF13794A)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Icon(
        painterResource(
            when {
                warning -> R.drawable.ic_warning
                error -> R.drawable.ic_warning
                removing -> R.drawable.ic_delete
                absent -> R.drawable.ic_install
                stopped -> R.drawable.ic_stop
                paused -> R.drawable.ic_pause
                active -> R.drawable.ic_check
                else -> R.drawable.ic_stop
            }
        ),
        null,
        Modifier.size(40.dp),
        tint = color,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatcherScreen(model: RuleViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(model, context, resources, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.notices.collect { notice ->
                val text =
                    if (notice.error == null) resources.getString(notice.message)
                    else
                        resources.getString(
                            notice.message,
                            resources.getString(errorResource(notice.error)),
                        )
                Toast.makeText(
                        context,
                        if (notice.synced) resources.getString(R.string.result_synced, text)
                        else text,
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, onEvent = model::refresh)
    val editorSaver = remember {
        Saver<Rule?, List<Any>>(
            save = { rule ->
                rule?.let {
                    listOf(
                        it.id,
                        it.root,
                        it.pattern,
                        it.action,
                        it.recursive,
                        it.enabled,
                        it.whitelist.joinToString("\n"),
                        it.literal,
                    )
                }
            },
            restore = {
                Rule(
                    it[0] as String,
                    it[1] as String,
                    it[2] as String,
                    it[3] as Int,
                    it[4] as Boolean,
                    it[5] as Boolean,
                    (it[6] as String).lines().filter(String::isNotEmpty),
                    it[7] as Boolean,
                )
            },
        )
    }
    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<Rule?>(null) }
    var removing by remember { mutableStateOf<Rule?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removingBatch by rememberSaveable { mutableStateOf(false) }
    var clearingRecords by rememberSaveable { mutableStateOf(false) }
    var enabling by rememberSaveable { mutableStateOf<String?>(null) }
    var cleaning by rememberSaveable { mutableStateOf<String?>(null) }
    var componentRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggleSelection(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }
    BackHandler(selected.isNotEmpty()) { selected = emptyList() }
    LaunchedEffect(state.rules.map { it.id }) {
        selected = selected.filter { id -> state.rules.any { it.id == id } }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) stringResource(R.string.app_name)
                        else
                            pluralStringResource(
                                R.plurals.selected_count,
                                selected.size,
                                selected.size,
                            )
                    )
                },
                navigationIcon = {
                    if (selected.isNotEmpty())
                        ToolButton(R.drawable.ic_close, R.string.cancel) { selected = emptyList() }
                },
                actions = {
                    if (selected.isEmpty()) {
                        ToolButton(
                            R.drawable.ic_refresh,
                            R.string.refresh,
                            !state.busy,
                            model::refresh,
                        )
                        ToolButton(R.drawable.ic_settings, R.string.settings) { settings = true }
                    } else {
                        ToolButton(
                            R.drawable.ic_select_all,
                            R.string.select_all,
                            !state.busy && selected.size < state.rules.size,
                        ) {
                            selected = state.rules.map { it.id }
                        }
                        ToolButton(
                            R.drawable.ic_check,
                            R.string.enable_selected,
                            state.connected && !state.busy,
                        ) {
                            model.previewBatch(selected.toSet())
                        }
                        ToolButton(R.drawable.ic_block, R.string.disable_selected, !state.busy) {
                            model.enable(selected.toSet(), false)
                            selected = emptyList()
                        }
                        ToolButton(R.drawable.ic_delete, R.string.delete_selected, !state.busy) {
                            removingBatch = true
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected.isEmpty())
                FloatingActionButton(onClick = { if (!state.busy) editor = Rule() }) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_rule))
                }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(
                                active = state.monitoring,
                                paused = false,
                                checking =
                                    (state.monitoring && state.checking) ||
                                        (!state.checked && state.error == null),
                                error =
                                    (!state.connected && state.error != null) ||
                                        (state.monitoring && state.ruleErrorCount > 0),
                                absent = !state.installed,
                                stopped = state.installed && !state.monitoring,
                                warning = state.checked && state.backendNeedsUpdate,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.backend) +
                                        if (state.backendPresent)
                                            " · ${componentVersionText(state.backendVersion)}"
                                        else "",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(
                                        backendStatusResource(state),
                                        state.ruleErrorCount,
                                    ),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                        if (state.connected) {
                            Text(
                                pluralStringResource(
                                    R.plurals.watch_count,
                                    state.watches,
                                    state.watches,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (state.installed) {
                            Row(
                                if (state.moduleActive)
                                    Modifier.clickable(role = Role.Button) { model.boot(false) }
                                else Modifier,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.boot), Modifier.weight(1f))
                                Switch(
                                    state.startsAtBoot,
                                    if (state.moduleActive) null else model::boot,
                                    enabled =
                                        state.connected &&
                                            !state.busy &&
                                            !state.bootBusy &&
                                            !state.moduleActive,
                                    thumbContent =
                                        if (state.moduleActive) {
                                            {
                                                Icon(
                                                    painterResource(R.drawable.ic_lock),
                                                    null,
                                                    Modifier.size(16.dp),
                                                )
                                            }
                                        } else null,
                                    colors =
                                        SwitchDefaults.colors(
                                            disabledCheckedTrackColor =
                                                MaterialTheme.colorScheme.primary,
                                            disabledCheckedThumbColor =
                                                MaterialTheme.colorScheme.onPrimary,
                                            disabledCheckedBorderColor =
                                                MaterialTheme.colorScheme.primary,
                                            disabledCheckedIconColor =
                                                MaterialTheme.colorScheme.primary,
                                        ),
                                )
                            }
                        }
                        if (state.monitoring) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(model::stop, enabled = !state.busy) {
                                    Text(stringResource(R.string.stop_backend))
                                }
                            }
                        } else {
                            if (!state.installed)
                                Text(
                                    stringResource(R.string.setup_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    if (state.installed) model::start else model::install,
                                    enabled = !state.busy,
                                ) {
                                    Text(
                                        stringResource(
                                            if (state.installed) R.string.start_backend
                                            else R.string.install
                                        )
                                    )
                                }
                            }
                        }
                        if (state.backendPresent)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(model::install, enabled = !state.busy) {
                                    Text(stringResource(R.string.reinstall_backend))
                                }
                                TextButton(
                                    { componentRemoval = "backend" },
                                    enabled = !state.busy,
                                ) {
                                    Text(
                                        stringResource(R.string.uninstall_backend),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(
                                state.moduleActive,
                                state.module == "disabled",
                                !state.checked && state.error == null,
                                state.module == "invalid",
                                state.module == "removing",
                                absent = state.module == "absent",
                                warning =
                                    state.checked &&
                                        state.moduleNeedsUpdate &&
                                        state.module != "removing",
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    stringResource(R.string.magisk_module) +
                                        if (state.module != "absent")
                                            " · ${componentVersionText(state.moduleVersion)}"
                                        else "",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(moduleStatusResource(state)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.module_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.magisk)
                                TextButton({ model.module(true) }, enabled = !state.busy) {
                                    Text(
                                        stringResource(
                                            if (state.module == "absent") R.string.install_module
                                            else R.string.reinstall_module
                                        )
                                    )
                                }
                            if (state.module != "absent")
                                TextButton({ componentRemoval = "module" }, enabled = !state.busy) {
                                    Text(
                                        stringResource(R.string.uninstall_module),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                        }
                    }
                }
            }
            if (state.cleaning)
                item {
                    TextButton(model::cancel) {
                        Text(stringResource(R.string.stop_cleanup))
                    }
                }
            item {
                Text(
                    stringResource(R.string.rules),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.rules.isEmpty())
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.empty_rules),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = { editor = Rule() },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.add_rule))
                        }
                    }
                }
            items(state.rules, key = { it.id }) { rule ->
                RuleCard(
                    rule,
                    !state.busy,
                    state.connected && !state.busy,
                    state.health[rule.id].orEmpty(),
                    selected = rule.id in selected,
                    selecting = selected.isNotEmpty(),
                    select = { toggleSelection(rule.id) },
                    edit = { editor = rule },
                    remove = { removing = rule },
                    preview = {
                        enabling = null
                        model.preview(rule.id)
                    },
                    enable = { checked ->
                        if (checked) {
                            enabling = rule.id
                            model.preview(rule.id)
                        } else model.enable(setOf(rule.id), false)
                    },
                )
            }
            if (state.records.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.recent_records),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ToolButton(R.drawable.ic_delete, R.string.clear_records, !state.busy) {
                            clearingRecords = true
                        }
                    }
                    Text(
                        stringResource(R.string.records_retention),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.records) { record ->
                    val error = record.error
                    ListItem(
                        headlineContent = {
                            Text(record.name)
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    if (error.isEmpty())
                                        pluralStringResource(
                                            R.plurals.cleaned,
                                            record.deleted,
                                            record.deleted,
                                        )
                                    else errorText(error)
                                )
                                Text(
                                    formatTimestamp(record.time),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
    editor?.let { rule ->
        RuleEditor(
            rule,
            state.rules.any { it.id == rule.id },
            128 - state.rules.size + if (state.rules.any { it.id == rule.id }) 1 else 0,
            state.busy,
            { editor = null },
        ) { updated ->
            model.save(updated, rule.id) { editor = null }
        }
    }
    removing?.let { rule ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title)) },
            text = { Text(stringResource(R.string.remove_message, rule.pattern)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.remove(setOf(rule.id))
                        removing = null
                    }
                ) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    componentRemoval?.let { component ->
        val backend = component == "backend"
        AlertDialog(
            onDismissRequest = { componentRemoval = null },
            title = {
                Text(
                    stringResource(
                        if (backend) R.string.uninstall_backend_title
                        else R.string.uninstall_module_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (backend) R.string.uninstall_backend_message
                        else R.string.uninstall_module_message
                    )
                )
            },
            confirmButton = {
                TextButton(
                    {
                        componentRemoval = null
                        if (backend) model.uninstall() else model.module(false)
                    },
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(R.string.uninstall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton({ componentRemoval = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    state.preview?.let { preview ->
        PreviewDialog(
            preview,
            enabling != null,
            state.busy,
            dismiss = {
                model.dismissPreview()
                enabling = null
            },
            enable = {
                enabling?.let { model.enable(setOf(it), true) }
                enabling = null
            },
            clean = { cleaning = preview.id },
            recover = { model.recover(preview.id) },
        )
    }
    cleaning?.let { id ->
        AlertDialog(
            onDismissRequest = { cleaning = null },
            title = { Text(stringResource(R.string.clean)) },
            text = { Text(stringResource(R.string.clean_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.clean(id)
                        cleaning = null
                    }
                ) {
                    Text(stringResource(R.string.clean), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { cleaning = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (settings) SettingsScreen(state, model) { settings = false }
    if (removingBatch)
        AlertDialog(
            onDismissRequest = { removingBatch = false },
            title = { Text(stringResource(R.string.delete_selected)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(state.rules.filter { it.id in selected }, key = { it.id }) { rule ->
                        Text(rule.pattern, Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    model.remove(selected.toSet())
                    selected = emptyList()
                    removingBatch = false
                }) {
                    Text(
                        stringResource(R.string.delete_selected),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton({ removingBatch = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    if (
        state.batchPreview != null ||
            state.newRuleIds.isNotEmpty() ||
            state.importedRuleIds.isNotEmpty()
    ) {
        val previews = state.batchPreview
        val added = state.newRuleIds.isNotEmpty()
        val imported = state.importedRuleIds.isNotEmpty()
        val pendingIds = if (imported) state.importedRuleIds else state.newRuleIds
        AlertDialog(
            onDismissRequest = model::dismissBatchPreview,
            title = {
                Text(
                    stringResource(
                        when {
                            imported -> R.string.enable_imported_rules
                            added -> R.string.enable_new_rules
                            else -> R.string.enable_selected
                        }
                    )
                )
            },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (imported) item { Text(stringResource(R.string.enable_imported_hint)) }
                    item { Text(stringResource(R.string.preview_enable)) }
                    if (previews == null) {
                        item {
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else
                                Text(
                                    stringResource(
                                        if (state.connected) R.string.preview_required
                                        else R.string.start_preview_hint
                                    )
                                )
                        }
                    }
                    previews.orEmpty().forEach { preview ->
                        val paths = preview.paths
                        item {
                            state.rules
                                .firstOrNull { it.id == preview.id }
                                ?.let {
                                    Text(it.pattern, style = MaterialTheme.typography.titleSmall)
                                }
                            Text(
                                preview.root,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.preview_count,
                                    paths.size,
                                    paths.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        items(paths.size) { index ->
                            Text(paths[index], style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        if (previews == null) model.previewBatch(pendingIds)
                        else {
                            model.enable(previews.map { it.id }.toSet(), true)
                            selected = emptyList()
                        }
                    },
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(
                            if (previews != null && imported) R.string.enable_now
                            else if (previews != null) R.string.enable_selected
                            else if (state.connected) R.string.preview
                            else R.string.start_and_preview
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(model::dismissBatchPreview, enabled = !state.busy) {
                    Text(stringResource(if (added || imported) R.string.later else R.string.cancel))
                }
            },
        )
    }
    if (clearingRecords)
        AlertDialog(
            onDismissRequest = { clearingRecords = false },
            title = { Text(stringResource(R.string.clear_records)) },
            text = { Text(stringResource(R.string.clear_records_confirm)) },
            confirmButton = {
                TextButton({
                    model.clearRecords()
                    clearingRecords = false
                }) {
                    Text(stringResource(R.string.clear_records))
                }
            },
            dismissButton = {
                TextButton({ clearingRecords = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.error_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(errorText(error))
                    Text(
                        error.take(1000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = model::dismissError) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    available: Boolean,
    canClean: Boolean,
    health: String,
    selected: Boolean,
    selecting: Boolean,
    select: () -> Unit,
    edit: () -> Unit,
    remove: () -> Unit,
    preview: () -> Unit,
    enable: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(
        Modifier.fillMaxWidth()
            .combinedClickable(
                enabled = available,
                onClick = if (selecting) select else edit,
                onLongClick = select,
            ),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface
            ),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rule.pattern,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        rule.root,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selecting) Checkbox(selected, { select() }, enabled = available)
                else {
                    Switch(rule.enabled, enable, enabled = available && (rule.enabled || canClean))
                    Box {
                        IconButton(onClick = { menu = true }, enabled = available) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_rule)) },
                                onClick = {
                                    menu = false
                                    edit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.preview)) },
                                enabled = canClean,
                                onClick = {
                                    menu = false
                                    preview()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.remove),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    remove()
                                },
                            )
                        }
                    }
                }
            }
            if (health.isNotEmpty())
                Text(
                    errorText(health),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(actionLabel(rule.action)),
                    Modifier.padding(vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (rule.action != 2 && !rule.literal)
                    RuleBadge(stringResource(R.string.rule_wildcards))
                if (rule.recursive) RuleBadge(stringResource(R.string.rule_subfolders))
                if (rule.whitelist.isNotEmpty())
                    RuleBadge(stringResource(R.string.rule_whitelist_count, rule.whitelist.size))
            }
        }
    }
}

@Composable
private fun RuleBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

internal fun actionLabel(action: Int): Int =
    when (action) {
        1 -> R.string.action_empty
        2 -> R.string.action_tree
        else -> R.string.action_files
    }
