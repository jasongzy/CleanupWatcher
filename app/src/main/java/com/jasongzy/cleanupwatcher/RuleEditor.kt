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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

private class RuleEditorState : ViewModel() {
    var pendingSave by mutableStateOf<List<Rule>?>(null)
}

@Composable
internal fun SaveChangesDialog(
    busy: Boolean,
    saveEnabled: Boolean = true,
    keepEditing: () -> Unit,
    discard: () -> Unit,
    save: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) keepEditing() },
        title = { Text(stringResource(R.string.save_changes_title)) },
        text = { Text(stringResource(R.string.save_changes_message)) },
        confirmButton = {
            TextButton(save, enabled = !busy && saveEnabled) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(keepEditing, enabled = !busy) {
                    Text(stringResource(R.string.keep_editing))
                }
                TextButton(
                    discard,
                    enabled = !busy,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.discard))
                }
            }
        },
    )
}

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
    var action by rememberSaveable { mutableStateOf(original.action) }
    var literal by rememberSaveable { mutableStateOf(original.literal) }
    var recursive by rememberSaveable { mutableStateOf(original.recursive) }
    var whitelist by rememberSaveable { mutableStateOf(original.whitelist.joinToString("\n")) }
    var advanced by rememberSaveable {
        mutableStateOf(
            original.recursive ||
                original.whitelist.isNotEmpty() ||
                original.action == RuleAction.EmptyTree
        )
    }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    var browser by rememberSaveable { mutableStateOf(false) }
    var whitelistHelp by rememberSaveable { mutableStateOf(false) }
    val editor = viewModel(viewModelStoreOwner = rememberViewModelStoreOwner()) { RuleEditorState() }
    var pendingSave by editor::pendingSave
    var confirmSave by rememberSaveable { mutableStateOf(false) }
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
    fun chooseAction(value: RuleAction) {
        val updated = rule.withAction(value)
        action = updated.action
        literal = updated.literal
        pattern = updated.pattern
        recursive = updated.recursive
    }
    fun submit(rules: List<Rule>) {
        val updated = rules.firstOrNull { it.id == original.id }?.copy(enabled = original.enabled)
        if (original.enabled && updated != original) pendingSave = rules else save(rules)
    }
    fun requestSave() {
        confirmSave = false
        attempted = true
        if (rule.error() == null && availableRules > 0) submit(listOf(rule))
    }
    fun requestDismiss() {
        if (!busy) {
            if (rule != original) confirmSave = true else dismiss()
        }
    }
    Dialog(
        onDismissRequest = ::requestDismiss,
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
                            action = ::requestDismiss,
                        )
                    },
                    actions = {
                        TextButton(::requestSave, enabled = !busy) {
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
                    listOf(
                            RuleAction.File to R.string.target_files,
                            RuleAction.FolderTree to R.string.target_folders,
                            RuleAction.EmptyChildren to R.string.target_empty_children,
                        )
                        .forEachIndexed { index, (target, label) ->
                            val selected =
                                action == target ||
                                    (target == RuleAction.FolderTree &&
                                        action == RuleAction.EmptyTree)
                            SegmentedButton(
                                enabled = !busy,
                                selected = selected,
                                onClick = { if (!selected) chooseAction(target) },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                            ) {
                                Text(stringResource(label))
                            }
                        }
                }
                if (action != RuleAction.EmptyChildren)
                    OutlinedTextField(
                        pattern,
                        { pattern = it },
                        Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    if (action == RuleAction.File) R.string.pattern
                                    else R.string.directory_name
                                )
                            )
                        },
                        supportingText = {
                            Text(
                                stringResource(
                                    when {
                                        action == RuleAction.FolderTree -> R.string.tree_name_hint
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
                if (action != RuleAction.File)
                    Text(
                        stringResource(
                            when (action) {
                                RuleAction.FolderTree -> R.string.tree_hint
                                RuleAction.EmptyChildren -> R.string.empty_children_hint
                                else -> R.string.empty_tree_hint
                            }
                        ),
                        color =
                            if (action == RuleAction.FolderTree) MaterialTheme.colorScheme.error
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
                    if (action == RuleAction.FolderTree || action == RuleAction.EmptyTree)
                        OptionRow(
                            R.string.empty_tree_only,
                            action == RuleAction.EmptyTree,
                            enabled = !busy,
                        ) {
                            chooseAction(if (it) RuleAction.EmptyTree else RuleAction.FolderTree)
                        }
                    if (action != RuleAction.EmptyChildren) {
                        OptionRow(
                            R.string.use_pattern,
                            action != RuleAction.FolderTree && !literal,
                            enabled = !busy && action != RuleAction.FolderTree,
                        ) {
                            literal = !it
                        }
                        OptionRow(R.string.include_subfolders, recursive, enabled = !busy) {
                            recursive = it
                        }
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
    if (confirmSave)
        SaveChangesDialog(
            busy = busy,
            keepEditing = { confirmSave = false },
            discard = dismiss,
            save = ::requestSave,
        )
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
            directoriesOnly = action == RuleAction.EmptyChildren,
            chooseFolder = { path ->
                browser = false
                root = path
                pickerError = if (Rule.validRoot(path)) null else "scope"
            },
            chooseEntries = { entries ->
                browser = false
                if (entries.size > 1) batch = entries
                else {
                    val selected = runCatching {
                        rulesForEntries(
                                entries,
                                if (action == RuleAction.File) RuleAction.FolderTree else action,
                            )
                            .single()
                    }
                    selected
                        .onSuccess {
                            root = it.root
                            pattern = it.pattern
                            action = it.action
                            literal = it.literal
                            recursive = false
                            whitelist = ""
                            pickerError = null
                        }
                        .onFailure { pickerError = it.message ?: "config" }
                }
            },
        )
    if (batch.isNotEmpty())
        BatchRuleEditor(
            batch,
            { batch = it },
            { batch = emptyList() },
            busy = busy,
            initialFolderAction = if (action == RuleAction.File) RuleAction.FolderTree else action,
        ) { rules ->
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
                    Text(ruleName(updated), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(actionLabel(updated.action)))
                    Text(
                        stringResource(
                            if (updated.recursive) R.string.scope_recursive
                            else R.string.scope_direct
                        )
                    )
                    if (updated.action == RuleAction.FolderTree)
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
