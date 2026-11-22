package com.switchboard.gboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.switchboard.gboard.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 设置页：数据驱动渲染（SettingsModel.sections），支持搜索过滤。
 *
 * @param values   remote preferences 的快照（key → 当前值）
 * @param onWrite  写回回调（写入 remote preferences + 刷新快照）
 */
@Composable
fun SettingsScreen(
    query: String,
    values: Map<String, Any?>,
    onWrite: (String, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (section in SettingsModel.sections) {
            val items = section.items.filter { item ->
                if (query.isBlank()) true
                else {
                    val title = ctx.getString(item.title)
                    val desc = if (item.desc != 0) ctx.getString(item.desc) else ""
                    title.contains(query, true) || desc.contains(query, true) ||
                        item.key.contains(query, true)
                }
            }
            if (items.isEmpty()) continue
            item(key = "sec_${section.title}") {
                Text(
                    stringResource(section.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            for (item in items) {
                item(key = item.key) {
                    SettingRow(item, values, onWrite)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    item: Item,
    values: Map<String, Any?>,
    onWrite: (String, Any?) -> Unit,
) {
    when (item) {
        is Item.Switch -> SwitchRow(item, values, onWrite)
        is Item.Num -> NumRow(item, values, onWrite)
        is Item.Text -> TextRow(item, values, onWrite)
        is Item.Choice -> ChoiceRow(item, values, onWrite)
    }
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
    ) {
        content()
    }
}

@Composable
private fun SwitchRow(item: Item.Switch, values: Map<String, Any?>, onWrite: (String, Any?) -> Unit) {
    val checked = (values[item.key] as? Boolean) ?: item.def
    RowCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(item.title),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (item.desc != 0) {
                    Text(
                        stringResource(item.desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = { onWrite(item.key, it) },
            )
        }
    }
}

@Composable
private fun NumRow(item: Item.Num, values: Map<String, Any?>, onWrite: (String, Any?) -> Unit) {
    val current = (values[item.key] as? Int) ?: item.def
    var open by remember { mutableStateOf(false) }
    var text by remember(open) { mutableStateOf(current.toString()) }

    RowCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.title), style = MaterialTheme.typography.bodyLarge)
                if (item.desc != 0) {
                    Text(
                        stringResource(item.desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { open = true }) { Text(current.toString()) }
        }
    }
    if (open) {
        EditDialog(
            title = stringResource(item.title),
            value = text,
            onValue = { text = it },
            onDismiss = { open = false },
            onConfirm = {
                text.toIntOrNull()?.let { onWrite(item.key, it) }
                open = false
            },
        )
    }
}

@Composable
private fun TextRow(item: Item.Text, values: Map<String, Any?>, onWrite: (String, Any?) -> Unit) {
    val current = (values[item.key] as? String) ?: item.def
    var open by remember { mutableStateOf(false) }
    var text by remember(open) { mutableStateOf(current) }

    RowCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(item.title), style = MaterialTheme.typography.bodyLarge)
                    if (item.desc != 0) {
                        Text(
                            stringResource(item.desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { open = true }) {
                    Text(
                        if (current.isBlank()) stringResource(android.R.string.cancel).let { "…" } else {
                            if (current.length > 12) current.take(12) + "…" else current
                        }
                    )
                }
            }
            if (current.isNotBlank() && current.length <= 12) {
                Text(
                    current,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (open) {
        EditDialog(
            title = stringResource(item.title),
            value = text,
            onValue = { text = it },
            onDismiss = { open = false },
            onConfirm = {
                onWrite(item.key, text.trim())
                open = false
            },
            multiline = item.multiline,
        )
    }
}

@Composable
private fun ChoiceRow(item: Item.Choice, values: Map<String, Any?>, onWrite: (String, Any?) -> Unit) {
    val current = (values[item.key] as? String) ?: item.def
    var open by remember { mutableStateOf(false) }
    val label = item.options.firstOrNull { it.first == current }?.second ?: item.options.first().second

    RowCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.title), style = MaterialTheme.typography.bodyLarge)
                if (item.desc != 0) {
                    Text(
                        stringResource(item.desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { open = true }) { Text(stringResource(label)) }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(item.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    item.options.forEach { (value, labelRes) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = value == current,
                                onClick = {
                                    onWrite(item.key, value)
                                    open = false
                                },
                            )
                            Text(stringResource(labelRes))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EditDialog(
    title: String,
    value: String,
    onValue: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    multiline: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                singleLine = !multiline,
                minLines = if (multiline) 4 else 1,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}


@Composable
fun StatusHeader(
    serviceBound: Boolean,
    inScope: Boolean,
    onRequestScope: () -> Unit,
    onForceStop: () -> Unit,
    onRestoreTheme: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (serviceBound) stringResource(R.string.service_bound_ok)
                else stringResource(R.string.service_bound_fail),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when {
                    !serviceBound -> ""
                    inScope -> stringResource(R.string.scope_included).removePrefix("，").removePrefix(", ")
                    else -> stringResource(R.string.scope_missing).removePrefix("，").removePrefix(", ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onRequestScope) { Text(stringResource(R.string.scope_request)) }
                TextButton(onClick = onForceStop) { Text(stringResource(R.string.force_stop_gboard)) }
            }
            TextButton(onClick = onRestoreTheme) {
                Text(
                    stringResource(R.string.restore_theme),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
    )
}
