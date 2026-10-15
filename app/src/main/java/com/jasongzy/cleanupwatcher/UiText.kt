package com.jasongzy.cleanupwatcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun componentVersionText(version: ComponentVersion): String =
    when {
        version.name != null && version.code != null -> "${version.name} (${version.code})"
        version.name != null -> version.name
        version.code != null -> stringResource(R.string.version_code, version.code)
        else -> stringResource(R.string.version_unknown)
    }

internal fun backendStatusResource(state: UiState): Int =
    when {
        !state.checked ->
            if (state.error == null) R.string.status_unknown else R.string.status_failed
        state.backendNeedsUpdate -> R.string.needs_update
        !state.connected && state.installed ->
            if (state.error == null) R.string.stopped else R.string.not_connected
        !state.connected -> R.string.not_installed
        state.paused -> R.string.stopped
        state.checking -> R.string.checking
        state.ruleErrorCount > 0 -> R.string.rule_errors
        state.rules.any { it.enabled } -> R.string.ready
        else -> R.string.backend_ready
    }

internal fun moduleStatusResource(state: UiState): Int =
    when {
        !state.checked ->
            if (state.error == null) R.string.status_unknown else R.string.status_failed
        state.module == "removing" -> R.string.module_removing
        state.moduleNeedsUpdate -> R.string.needs_update
        state.module == "enabled" -> R.string.module_enabled
        state.module == "disabled" -> R.string.module_disabled
        state.module == "invalid" -> R.string.module_invalid
        !state.magisk -> R.string.magisk_unavailable
        else -> R.string.not_installed
    }

@Composable
internal fun errorText(error: String): String {
    return stringResource(errorResource(error))
}

internal fun errorResource(error: String): Int {
    val code = error.substringBefore(':')
    val resource =
        when (code) {
            "root" -> R.string.error_root
            "document" -> R.string.error_document
            "execution" -> R.string.error_execution
            "install" -> R.string.error_install
            "management",
            "module" -> R.string.error_management
            "magisk" -> R.string.magisk_unavailable
            "uninstall_unknown" -> R.string.error_uninstall_unknown
            "offline" -> R.string.error_offline
            "scope" -> R.string.error_scope
            "pattern" -> R.string.error_pattern
            "directory_pattern" -> R.string.error_directory_pattern
            "whitelist" -> R.string.error_whitelist
            "config" -> R.string.error_config
            "storage" -> R.string.error_storage
            "path",
            "stat",
            "list" -> R.string.error_path
            "changed" -> R.string.error_changed
            "protected" -> R.string.error_protected
            "symlink" -> R.string.error_symlink
            "type" -> R.string.error_type
            "limit" -> R.string.error_limit
            "staging" -> R.string.error_staging
            "capture" -> R.string.error_capture
            "delete" -> R.string.error_delete
            "retained" -> R.string.error_retained
            "unsupported" -> R.string.error_unsupported
            "watch" -> R.string.error_watch
            "timeout" -> R.string.error_timeout
            "busy" -> R.string.error_busy
            "cancelled" -> R.string.error_cancelled
            "rate" -> R.string.error_rate
            else -> R.string.error_backend
        }
    return resource
}
