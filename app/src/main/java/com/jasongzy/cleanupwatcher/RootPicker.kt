package com.jasongzy.cleanupwatcher

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

internal val RootSelectionSaver =
    listSaver<List<RootEntry>, String>(
        save = { entries -> entries.flatMap { listOf(it.path, it.name, it.type) } },
        restore = { fields -> fields.chunked(3).map { RootEntry(it[0], it[1], it[2]) } },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RootPicker(
    initialPath: String,
    maxSelection: Int,
    dismiss: () -> Unit,
    chooseFolder: (String) -> Unit,
    chooseEntries: (List<RootEntry>) -> Unit,
    directoriesOnly: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val focus = LocalFocusManager.current
    val backend = remember(context) { Backend(context) }
    val listState = rememberLazyListState()
    var location by rememberSaveable { mutableStateOf(initialPath.ifEmpty { "/" }) }
    var address by rememberSaveable { mutableStateOf(location) }
    var history by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selected by
        rememberSaveable(stateSaver = RootSelectionSaver) { mutableStateOf(emptyList<RootEntry>()) }
    var page by remember { mutableStateOf<RootPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var limitReached by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf("-") }
    fun navigate(path: String) {
        if (loading || path == location) return
        focus.clearFocus()
        history = history + location
        cursor = "-"
        location = path
    }
    fun toggle(entry: RootEntry) {
        if (!entry.selectable || (directoriesOnly && entry.type != "directory")) return
        limitReached = false
        if (selected.any { it.path == entry.path })
            selected = selected.filterNot { it.path == entry.path }
        else if (selected.size < maxSelection) selected = selected + entry else limitReached = true
    }
    LaunchedEffect(location, refresh, cursor) {
        loading = true
        val current = page
        if (cursor == "-") {
            page = null
            listState.scrollToItem(0)
        }
        error = null
        try {
            val next = backend.browse(if (cursor == "-") location else current!!.path, cursor)
            page =
                if (cursor == "-") next
                else next.copy(entries = (current!!.entries + next.entries).distinctBy { it.key })
            address = page!!.path
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "path"
        } finally {
            if (isActive) loading = false
        }
    }
    Dialog(
        dismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler {
            if (selected.isNotEmpty()) selected = emptyList()
            else if (history.isNotEmpty()) {
                location = history.last()
                cursor = "-"
                history = history.dropLast(1)
            } else dismiss()
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (selected.isEmpty()) stringResource(R.string.root_browser)
                            else
                                pluralStringResource(
                                    R.plurals.selected_count,
                                    selected.size,
                                    selected.size,
                                )
                        )
                    },
                    navigationIcon = {
                        ToolButton(R.drawable.ic_back, R.string.cancel, action = dismiss)
                    },
                    actions = {
                        ToolButton(
                            R.drawable.ic_up,
                            R.string.parent_folder,
                            !loading && page?.path != "/" && page != null,
                        ) {
                            navigate(page!!.path.substringBeforeLast('/').ifEmpty { "/" })
                        }
                        ToolButton(R.drawable.ic_refresh, R.string.refresh, !loading) {
                            cursor = "-"
                            refresh++
                        }
                    },
                )
            },
            bottomBar = {
                Surface {
                    Button(
                        {
                            if (selected.isEmpty()) chooseFolder(page!!.path)
                            else chooseEntries(selected)
                        },
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        enabled = !loading && (page != null || selected.isNotEmpty()),
                    ) {
                        Text(
                            if (selected.isEmpty()) stringResource(R.string.use_folder)
                            else
                                pluralStringResource(
                                    R.plurals.choose_count,
                                    selected.size,
                                    selected.size,
                                )
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                OutlinedTextField(
                    address,
                    { address = it },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text(stringResource(R.string.path)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                    trailingIcon = {
                        ToolButton(R.drawable.ic_forward, R.string.go_to_folder, !loading) {
                            navigate(address)
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let {
                    Text(
                        errorText(it),
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (limitReached)
                    Text(
                        pluralStringResource(R.plurals.selection_limit, maxSelection, maxSelection),
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                Box(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.selection_hint),
                        if (selected.isEmpty()) Modifier
                        else Modifier.alpha(0f).clearAndSetSemantics {},
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (selected.isNotEmpty())
                        Text(
                            pluralStringResource(
                                R.plurals.selected_count,
                                selected.size,
                                selected.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(page?.entries.orEmpty(), key = { it.key }) { entry ->
                        val checked = selected.any { it.path == entry.path }
                        val selectable =
                            entry.selectable && (!directoriesOnly || entry.type == "directory")
                        ListItem(
                            headlineContent = {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val modified = formatTimestamp(entry.modified, seconds = false)
                                val detail =
                                    when (entry.type) {
                                        "file" ->
                                            Formatter.formatShortFileSize(
                                                LocalContext.current,
                                                entry.size,
                                            )
                                        "link" -> stringResource(R.string.symbolic_link)
                                        "other" -> stringResource(R.string.special_file)
                                        else -> null
                                    }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        modified,
                                        Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (detail != null) Text(detail, maxLines = 1)
                                }
                            },
                            leadingContent = {
                                Icon(
                                    painterResource(
                                        if (entry.directory) R.drawable.ic_folder
                                        else R.drawable.ic_file
                                    ),
                                    null,
                                )
                            },
                            trailingContent = {
                                if (selected.isNotEmpty())
                                    Checkbox(checked, { toggle(entry) }, enabled = selectable)
                            },
                            modifier =
                                Modifier.combinedClickable(
                                    enabled = !loading && (entry.directory || selectable),
                                    onClick = {
                                        if (selected.isNotEmpty() && entry.selectable) toggle(entry)
                                        else if (entry.directory) navigate(entry.path)
                                        else toggle(entry)
                                    },
                                    onLongClick = { toggle(entry) },
                                ),
                        )
                    }
                    if (page?.cursor != null && page?.cursor != "-")
                        item {
                            TextButton(
                                {
                                    cursor = page!!.cursor
                                    refresh++
                                },
                                Modifier.fillMaxWidth(),
                                enabled = !loading,
                            ) {
                                Text(stringResource(R.string.load_more))
                            }
                        }
                    if (page != null && page!!.entries.isEmpty() && !loading)
                        item {
                            Text(stringResource(R.string.folder_empty), Modifier.padding(20.dp))
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchRuleEditor(
    targets: List<RootEntry>,
    changeTargets: (List<RootEntry>) -> Unit,
    dismiss: () -> Unit,
    busy: Boolean = false,
    initialFolderAction: RuleAction = RuleAction.FolderTree,
    save: (List<Rule>) -> Unit,
) {
    var folderAction by rememberSaveable { mutableStateOf(initialFolderAction) }
    var confirmSave by rememberSaveable { mutableStateOf(false) }
    fun requestDismiss() {
        if (!busy) confirmSave = true
    }
    val emptyChildren = folderAction == RuleAction.EmptyChildren
    val emptyOnly = folderAction == RuleAction.EmptyTree
    val result =
        remember(targets, folderAction) { runCatching { rulesForEntries(targets, folderAction) } }
    val rules = result.getOrNull().orEmpty()
    fun requestSave() {
        confirmSave = false
        save(rules)
    }
    Dialog(
        ::requestDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.add_rules)) },
                    navigationIcon = {
                        ToolButton(
                            R.drawable.ic_back,
                            R.string.cancel,
                            enabled = !busy,
                            action = ::requestDismiss,
                        )
                    },
                    actions = {
                        TextButton(::requestSave, enabled = !busy && rules.isNotEmpty()) {
                            Text(stringResource(R.string.save))
                        }
                    },
                )
            }
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        pluralStringResource(R.plurals.rules_to_create, rules.size, rules.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (targets.any { it.type == "directory" })
                    item {
                        if (!emptyChildren)
                            OptionRow(R.string.empty_tree_only, emptyOnly, enabled = !busy) {
                                folderAction =
                                    if (it) RuleAction.EmptyTree else RuleAction.FolderTree
                            }
                        Text(
                            stringResource(
                                when {
                                    emptyChildren -> R.string.empty_children_hint
                                    emptyOnly -> R.string.batch_empty_tree_hint
                                    else -> R.string.batch_tree_hint
                                }
                            ),
                            Modifier.animateContentSize(),
                            color =
                                if (emptyOnly || emptyChildren)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            minLines = 3,
                        )
                    }
                result.exceptionOrNull()?.let { problem ->
                    item {
                        Text(
                            errorText(problem.message ?: "config"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(targets, key = { it.path }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(entry.path) },
                        leadingContent = {
                            Icon(
                                painterResource(
                                    if (entry.type == "directory") R.drawable.ic_folder
                                    else R.drawable.ic_file
                                ),
                                null,
                            )
                        },
                        trailingContent = {
                            ToolButton(
                                R.drawable.ic_close,
                                R.string.remove_selection,
                                enabled = !busy,
                            ) {
                                changeTargets(targets.filterNot { it.path == entry.path })
                            }
                        },
                    )
                }
            }
        }
    }
    if (confirmSave)
        SaveChangesDialog(
            busy = busy,
            saveEnabled = rules.isNotEmpty(),
            keepEditing = { confirmSave = false },
            discard = dismiss,
            save = ::requestSave,
        )
}
