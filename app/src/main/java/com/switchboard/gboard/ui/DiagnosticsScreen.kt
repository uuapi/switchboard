package com.switchboard.gboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.switchboard.gboard.Config
import com.switchboard.gboard.R

/**
 * 诊断页：LSPosed 服务状态、作用域、Gboard 版本、开关概览、取日志指引。
 * （hook 侧无法反向写 remote preferences，故注入成败以 LSPosed 日志/logcat 为准。）
 */
@Composable
fun DiagnosticsScreen(
    serviceBound: Boolean,
    inScope: Boolean,
    values: Map<String, Any?>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val gboardVersion = rememberGboardVersion()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DiagCard(stringResource(R.string.diag_service_t)) {
            Text(
                if (serviceBound) stringResource(R.string.service_bound_ok)
                else stringResource(R.string.service_bound_fail),
                style = MaterialTheme.typography.bodyMedium,
            )
        }



        DiagCard(stringResource(R.string.diag_scope_t)) {
            Text(
                if (!serviceBound) stringResource(R.string.service_bound_fail)
                else if (inScope) stringResource(R.string.scope_included).removePrefix("，").removePrefix(", ")
                else stringResource(R.string.scope_missing).removePrefix("，").removePrefix(", "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DiagCard(stringResource(R.string.diag_gboard_t)) {
            Text(
                gboardVersion ?: stringResource(R.string.diag_gboard_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DiagCard(stringResource(R.string.diag_switches_t)) {
            val enabled = SettingsModel.allItems.count { item ->
                when (item) {
                    is Item.Switch -> (values[item.key] as? Boolean) ?: item.def
                    else -> false
                }
            }
            Text(
                stringResource(R.string.diag_switches_summary, enabled, SettingsModel.switchCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DiagCard(stringResource(R.string.diag_compat_t)) {
            Text(
                stringResource(R.string.diag_compat_d),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DiagCard(stringResource(R.string.diag_logs_t)) {
            Text(
                stringResource(R.string.diag_logs_d),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.diag_logs_cmd),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText(
                            "switchboard",
                            context.getString(R.string.diag_logs_cmd),
                        )
                    )
                }) { Text(stringResource(R.string.act_copy)) }
            }
        }

        Text(
            stringResource(R.string.credit_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun rememberGboardVersion(): String? {
    val context = LocalContext.current
    return androidx.compose.runtime.remember {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(Config.GBOARD_PACKAGE, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrNull()
    }
}
