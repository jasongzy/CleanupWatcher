package com.jasongzy.cleanupwatcher

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun OptionRow(
    label: Int,
    checked: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditor(
    original: Rule,
    editing: Boolean,
    availableRules: Int,
    busy: Boolean,
    dismiss: () -> Unit,
    save: (List<Rule>) -> Unit,
) {
    var root by rememberSaveable { mutableStateOf(original.root) }
    var pattern by rememberSaveable { mutableStateOf(original.pattern) }
    var action by rememberSaveable { mutableIntStateOf(original.action) }
    var literal by rememberSaveable { mutableStateOf(original.literal) }
    var recursive by rememberSaveable { mutableStateOf(original.recursive) }
    var whitelist by rememberSaveable { mutableStateOf(original.whitelist.joinToString("\n")) }
    var advanced by rememberSaveable {
        mutableStateOf(
            original.recursive || original.whitelist.isNotEmpty() || original.action == 1
        )
    }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    var browser by rememberSaveable { mutableStateOf(false) }
    var whitelistHelp by rememberSaveable { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<List<Rule>?>(null) }
    var batch by
        rememberSaveable(stateSaver = RootSelectionSaver) { mutableStateOf(emptyList<RootEntry>()) }
    val rule =
        original.copy(
            root = root,
            pattern = pattern,
            action = action,
            recursive = recursive,
            literal = literal,
            whitelist = whitelist.lines().filter { it.isNotEmpty() },
        )
    val error = if (attempted) rule.error() else null
    fun chooseAction(value: Int) {
        val updated = rule.withAction(value)
        action = updated.action
        literal = updated.literal
    }
    fun submit(rules: List<Rule>) {
        val updated = rules.firstOrNull { it.id == original.id }?.copy(enabled = original.enabled)
        if (original.enabled && updated != original) pendingSave = rules else save(rules)
    }
    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(if (editing) R.string.edit_rule else R.string.add_rule))
                    },
                    navigationIcon = {
                        ToolButton(
                            R.drawable.ic_back,
                            R.string.cancel,
                            enabled = !busy,
                            action = dismiss,
                        )
                    },
                    actions = {
                        TextButton(
                            {
                                attempted = true
                                if (rule.error() == null && availableRules > 0) submit(listOf(rule))
                            },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    },
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .animateContentSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    root,
                    {
                        root = it
                        pickerError = null
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.folder)) },
                    placeholder = { Text(stringResource(R.string.folder_hint)) },
                    trailingIcon = {
                        ToolButton(R.drawable.ic_folder, R.string.choose_folder, enabled = !busy) {
                            browser = true
                        }
                    },
                    singleLine = true,
                    enabled = !busy,
                    isError = error == "scope" || pickerError == "scope",
                )
                pickerError?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                if (availableRules < 1)
                    Text(errorText("limit"), color = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.clean_target),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(R.string.target_files, R.string.target_folders).forEachIndexed {
                        index,
                        label ->
                        SegmentedButton(
                            enabled = !busy,
                            selected = if (index == 0) action == 0 else action != 0,
                            onClick = {
                                if (index == 0) chooseAction(0)
                                else if (action == 0) chooseAction(2)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) {
                            Text(stringResource(label))
                        }
                    }
                }
                OutlinedTextField(
                    pattern,
                    { pattern = it },
                    Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            stringResource(
                                if (action == 0) R.string.pattern else R.string.directory_name
                            )
                        )
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                when {
                                    action == 2 -> R.string.tree_name_hint
                                    literal -> R.string.directory_hint
                                    else -> R.string.pattern_hint
                                }
                            )
                        )
                    },
                    singleLine = true,
                    enabled = !busy,
                    isError = error in setOf("pattern", "directory_pattern"),
                )
                if (action != 0)
                    Text(
                        stringResource(
                            if (action == 2) R.string.tree_hint else R.string.empty_hint_rule
                        ),
                        color =
                            if (action == 2) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        minLines = 2,
                    )
                val expandRotation by animateFloatAsState(if (advanced) 180f else 0f)
                TextButton({ advanced = !advanced }, contentPadding = PaddingValues(0.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        null,
                        Modifier.rotate(expandRotation),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.more_options))
                }
                if (advanced) {
                    if (action != 0)
                        OptionRow(R.string.empty_only, action == 1, enabled = !busy) {
                            chooseAction(if (it) 1 else 2)
                        }
                    OptionRow(
                        R.string.use_pattern,
                        action != 2 && !literal,
                        enabled = !busy && action != 2,
                    ) {
                        literal = !it
                    }
                    OptionRow(R.string.include_subfolders, recursive, enabled = !busy) {
                        recursive = it
                    }
                    OutlinedTextField(
                        whitelist,
                        { whitelist = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.whitelist)) },
                        trailingIcon = {
                            ToolButton(R.drawable.ic_info, R.string.whitelist_help_title) {
                                whitelistHelp = true
                            }
                        },
                        supportingText = { Text(stringResource(R.string.whitelist_hint)) },
                        enabled = !busy,
                        isError = error == "whitelist",
                    )
                }
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (whitelistHelp) {
        val exampleRoot = if (Rule.validRoot(root)) root else original.root
        AlertDialog(
            onDismissRequest = { whitelistHelp = false },
            title = { Text(stringResource(R.string.whitelist_help_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.whitelist_help,
                        exampleRoot,
                        "$exampleRoot/keep",
                        "$exampleRoot/images/photo.jpg",
                    ),
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton({ whitelistHelp = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
    if (browser)
        RootPicker(
            root,
            availableRules.coerceAtLeast(0),
            { browser = false },
            chooseFolder = { path ->
                browser = false
                root = path
                pickerError = if (Rule.validRoot(path)) null else "scope"
            },
            chooseEntries = { entries ->
                browser = false
                if (entries.size > 1) batch = entries
                else {
                    val selected = runCatching { rulesForEntries(entries, false).single() }
                    selected
                        .onSuccess {
                            root = it.root
                            pattern = it.pattern
                            action = it.action
                            literal = true
                            recursive = false
                            whitelist = ""
                            pickerError = null
                        }
                        .onFailure { pickerError = it.message ?: "config" }
                }
            },
        )
    if (batch.isNotEmpty())
        BatchRuleEditor(batch, { batch = it }, { batch = emptyList() }, busy = busy) { rules ->
            submit(
                rules.mapIndexed { index, rule ->
                    if (index == 0) rule.copy(id = original.id) else rule
                }
            )
        }
    pendingSave?.let { rules ->
        val updated = rules.first { it.id == original.id }
        AlertDialog(
            onDismissRequest = { if (!busy) pendingSave = null },
            title = { Text(stringResource(R.string.save_enabled_rule)) },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.preview_enable))
                    Text(updated.root)
                    Text(updated.pattern, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(actionLabel(updated.action)))
                    Text(
                        stringResource(
                            if (updated.recursive) R.string.scope_recursive
                            else R.string.scope_direct
                        )
                    )
                    if (updated.action == 2)
                        Text(
                            stringResource(R.string.tree_hint),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        pendingSave = null
                        save(rules)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton({ pendingSave = null }, enabled = !busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
