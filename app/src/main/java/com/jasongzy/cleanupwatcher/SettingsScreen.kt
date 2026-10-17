package com.jasongzy.cleanupwatcher

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: UiState, model: RuleViewModel, dismiss: () -> Unit) {
    var language by rememberSaveable { mutableStateOf(false) }
    var colors by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(model::readImport)
        }
    val exporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let(model::exportRules)
        }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    ToolButton(R.drawable.ic_back, R.string.close, action = dismiss)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val locale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            SettingsItem(
                R.drawable.ic_language,
                R.string.language,
                stringResource(
                    when {
                        locale.startsWith("zh") -> R.string.chinese
                        locale.startsWith("en") -> R.string.english
                        else -> R.string.system_default
                    }
                ),
            ) {
                language = true
            }
            SettingsItem(
                R.drawable.ic_theme,
                R.string.color_mode,
                stringResource(
                    when (state.colorMode) {
                        "light" -> R.string.theme_light
                        "dark" -> R.string.theme_dark
                        else -> R.string.system_default
                    }
                ),
            ) {
                colors = true
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            SettingsItem(R.drawable.ic_import, R.string.import_rules, enabled = !state.busy) {
                importer.launch(arrayOf("application/json"))
            }
            SettingsItem(R.drawable.ic_export, R.string.export_rules, enabled = !state.busy) {
                exporter.launch("cleanupwatcher-rules.json")
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            SettingsItem(
                R.drawable.ic_open_in_new,
                R.string.github,
                stringResource(R.string.github_description),
            ) {
                try {
                    uriHandler.openUri("https://github.com/jasongzy/CleanupWatcher")
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, R.string.error_open_link, Toast.LENGTH_SHORT).show()
                }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.version)) },
                supportingContent = {
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_info), null) },
            )
        }
    }
    if (language) {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        ChoiceDialog(
            R.string.language,
            listOf(
                "" to R.string.system_default,
                "en" to R.string.english,
                "zh-CN" to R.string.chinese,
            ),
            current,
            { language = false },
        ) { value ->
            language = false
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
        }
    }
    if (colors)
        ChoiceDialog(
            R.string.color_mode,
            listOf(
                "system" to R.string.system_default,
                "light" to R.string.theme_light,
                "dark" to R.string.theme_dark,
            ),
            state.colorMode,
            { colors = false },
        ) { value ->
            model.colorMode(value)
            colors = false
        }
    state.pendingImport?.let { rules ->
        var replace by remember(rules) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.busy) model.dismissImport() },
            title = { Text(stringResource(R.string.import_rules)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.import_confirm, rules.size, rules.size))
                    listOf(false to R.string.import_append, true to R.string.import_replace)
                        .forEach { (value, label) ->
                            TextButton(
                                { replace = value },
                                Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                            ) {
                                RadioButton(replace == value, onClick = null)
                                Text(
                                    stringResource(label),
                                    Modifier.weight(1f).padding(start = 12.dp),
                                )
                            }
                        }
                    if (replace)
                        Text(
                            stringResource(R.string.import_replace_warning),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton({ model.importRules(replace) }, enabled = !state.busy) {
                    Text(stringResource(R.string.import_rules))
                }
            },
            dismissButton = {
                TextButton(model::dismissImport, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsItem(
    icon: Int,
    title: Int,
    value: String? = null,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = action),
    )
}

@Composable
private fun ChoiceDialog(
    title: Int,
    choices: List<Pair<String, Int>>,
    current: String,
    dismiss: () -> Unit,
    choose: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                choices.forEach { (value, label) ->
                    TextButton({ choose(value) }, Modifier.fillMaxWidth()) {
                        RadioButton(current == value, onClick = null)
                        Text(stringResource(label), Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text(stringResource(R.string.close)) } },
    )
}
