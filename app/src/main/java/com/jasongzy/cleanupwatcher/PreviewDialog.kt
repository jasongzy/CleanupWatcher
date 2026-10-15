package com.jasongzy.cleanupwatcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun PreviewDialog(
    result: CleanupResult,
    enabling: Boolean,
    busy: Boolean,
    dismiss: () -> Unit,
    enable: () -> Unit,
    clean: () -> Unit,
    recover: () -> Unit,
) {
    val paths = result.paths
    val error = result.error
    val deleted = result.deleted
    val count = result.restored ?: if (deleted > 0) deleted else paths.size
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                pluralStringResource(
                    when {
                        result.restored != null -> R.plurals.restored
                        deleted > 0 -> R.plurals.cleaned
                        else -> R.plurals.preview_count
                    },
                    count,
                    count,
                )
            )
        },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(result.root, style = MaterialTheme.typography.bodyMedium) }
                if (error.isNotEmpty())
                    item { Text(errorText(error), color = MaterialTheme.colorScheme.error) }
                else if (enabling) item { Text(stringResource(R.string.preview_enable)) }
                items(paths.size) { index ->
                    Text(
                        paths[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            if (error.startsWith("retained"))
                TextButton(onClick = recover, enabled = !busy) {
                    Text(stringResource(R.string.recover))
                }
            else if (error.isEmpty() && deleted == 0 && result.restored == null)
                TextButton(
                    onClick = if (enabling) enable else clean,
                    enabled = !busy && (enabling || paths.isNotEmpty()),
                ) {
                    Text(stringResource(if (enabling) R.string.enable else R.string.clean))
                }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
    )
}
