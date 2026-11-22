package com.switchboard.gboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.switchboard.gboard.R

/**
 * 设置主列表（Material 3 重设计）：
 * 分类 FilterChip 横向过滤 + 圆角搜索框 + 分组圆角卡片（图标头 + 行 + 细分隔线），
 * LazyColumn 渲染；搜索词与分类 chip 同时生效。
 */
private fun matchItem(ctx: android.content.Context, item: Item, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val title = ctx.getString(item.title)
    val desc = if (item.desc != 0) ctx.getString(item.desc) else ""
    return title.contains(q, true) || desc.contains(q, true) || item.key.contains(q, true)
}

@Composable
fun SettingsBody(
    query: String,
    onQueryChange: (String) -> Unit,
    chip: Int?,
    onChipChange: (Int?) -> Unit,
    values: Map<String, Any?>,
    onWrite: (String, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val sections = SettingsModel.sections
    LazyColumn(modifier = modifier.fillMaxSize()) {
        // 搜索 + 分类 chips：随列表滚动，吸顶由内容量自然覆盖
        item(key = "search") {
            SearchField(query, onQueryChange)
        }
        item(key = "chips") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = chip == null,
                        onClick = { onChipChange(null) },
                        label = { Text(stringResource(R.string.chip_all)) },
                    )
                }
                itemsIndexed(sections) { si, sec ->
                    FilterChip(
                        selected = chip == si,
                        onClick = { onChipChange(if (chip == si) null else si) },
                        label = {
                            Text(
                                stringResource(sec.title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(sec.icon, null, Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
        val visible = sections.mapIndexed { si, sec ->
            si to sec.items.filter { matchItem(ctx, it, query) }
        }.filter { (si, items) ->
            items.isNotEmpty() && (chip == null || chip == si)
        }
        if (visible.isEmpty()) {
            item(key = "nomatch") {
                Text(
                    stringResource(R.string.clipboard_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        visible.forEach { (si, items) ->
            item(key = "sec$si") {
                SectionCard(sections[si], items, values, onWrite)
            }
        }
        item(key = "foot") {
            Text(
                stringResource(R.string.needs_restart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(
    section: Section,
    items: List<Item>,
    values: Map<String, Any?>,
    onWrite: (String, Any?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                section.icon, null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(section.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                items.forEachIndexed { i, item ->
                    SettingRow(item, values, onWrite)
                    if (i != items.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        shape = RoundedCornerShape(28.dp),
        singleLine = true,
    )
}

@Composable
private fun SettingRow(item: Item, values: Map<String, Any?>, onWrite: (String, Any?) -> Unit) {
    when (item) {
        is Item.Switch -> SwitchRow(item, values, onWrite)
        is Item.Num -> NumRow(item, values, onWrite)
        is Item.Text -> TextRow(item, values, onWrite)
        is Item.Choice -> ChoiceRow(item, values, onWrite)
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
private fun RowCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) { content() }
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
                        if (current.isBlank()) "…" else {
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
