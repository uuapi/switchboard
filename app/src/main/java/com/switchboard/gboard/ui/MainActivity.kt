package com.switchboard.gboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.switchboard.gboard.BuildConfig
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.switchboard.gboard.Config
import com.switchboard.gboard.R
import com.switchboard.gboard.hook.ThemeInstaller
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow

/**
 * Switchboard 设置 App（Jetpack Compose · Material 3）。
 *
 * 底部双 Tab：功能开关 / 状态诊断。
 * 配置经 libxposed service 的 remote preferences（group "switchboard"）写入，
 * 由 LSPosed 同步到 Gboard 进程内的 Config。
 */
class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<XposedService?>(null)
    private val values = mutableStateMapOf<String, Any?>()

    private val glossaryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readGlossary(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService()
        setContent {
            SwitchboardTheme {
                SwitchboardApp(
                    service = service,
                    values = values,
                    onBindValue = { key, value ->
                        write(key, value)
                        values[key] = value
                    },
                    onExport = { exportConfig() },
                    onImport = { importConfig() },
                    onRequestScope = { requestScope() },
                    onForceStop = { forceStop() },
                    onRestoreTheme = { restoreTheme() },
                    onImportGlossary = { importGlossary() },
                )
            }
        }
    }

    // ---------- 服务绑定与配置读写 ----------

    private fun bindService() {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(s: XposedService) {
                    service = s
                    runOnUiThread { loadAll() }
                }

                override fun onServiceDied(s: XposedService) {
                    service = null
                }
            })
        }
    }

    // ---------- App 侧配置镜像 ----------
    // Vector 的 remote preferences 是内存态（设备重启后 daemon 丢失全部键），
    // App 把每次写入镜像到私有文件；daemon 缺数据（pref_sync_time 归零）时
    // UI / 导出 从镜像恢复，与 Hook 侧缓存互为独立副本。

    private val cacheFile by lazy { java.io.File(filesDir, "switchboard_cache.json") }
    private var cacheObj = org.json.JSONObject()

    private fun daemonHasData(): Boolean = runCatching {
        service?.getRemotePreferences(Config.PREF_GROUP)?.getLong("pref_sync_time", 0L)
    }.getOrDefault(0L) ?: 0L > 0L

    private fun loadAppCache() {
        cacheObj = runCatching {
            org.json.JSONObject(cacheFile.takeIf { it.exists() }?.readText() ?: "{}")
        }.getOrDefault(org.json.JSONObject())
    }

    private fun cacheWrite(key: String, value: Any?) {
        runCatching {
            cacheObj.putOpt(key, value ?: return)
            cacheFile.writeText(cacheObj.toString())
        }
    }

    private fun remoteOrDefault(item: Item, prefs: android.content.SharedPreferences): Any = when (item) {
        is Item.Switch -> if (prefs.contains(item.key)) prefs.getBoolean(item.key, item.def)
        else cacheObj.opt(item.key) as? Boolean ?: item.def
        is Item.Num -> if (prefs.contains(item.key)) prefs.getInt(item.key, item.def)
        else cacheObj.opt(item.key) as? Int ?: item.def
        is Item.Text -> if (prefs.contains(item.key)) prefs.getString(item.key, item.def) ?: item.def
        else cacheObj.opt(item.key) as? String ?: item.def
        is Item.Choice -> if (prefs.contains(item.key)) prefs.getString(item.key, item.def) ?: item.def
        else cacheObj.opt(item.key) as? String ?: item.def
    }

    private fun loadAll() {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: return
        loadAppCache()
        values.clear()
        for (item in SettingsModel.allItems) {
            values[item.key] = remoteOrDefault(item, prefs)
        }
    }

    private fun write(key: String, value: Any?) {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: return
        val edit = prefs.edit()
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
        }
        // 同步戳：daemon 重启丢失后归零，Hook/App 两侧据此回退本地镜像
        edit.putLong("pref_sync_time", System.currentTimeMillis())
        edit.apply()
        cacheWrite(key, value)
    }

    private fun exportConfig() {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: run {
            toast(getString(R.string.service_bound_fail)); return
        }
        loadAppCache()
        val obj = org.json.JSONObject()
        for (item in SettingsModel.allItems) {
            when (val v = remoteOrDefault(item, prefs)) {
                is Boolean -> obj.put(item.key, v)
                is Int -> obj.put(item.key, v)
                is String -> obj.put(item.key, v)
            }
        }
        clipboard(obj.toString())
        toast(getString(R.string.act_export_ok, SettingsModel.allItems.size))
    }

    private fun importConfig() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            ?: run { toast(getString(R.string.clipboard_empty)); return }
        runCatching {
            val obj = org.json.JSONObject(text)
            var n = 0
            for (item in SettingsModel.allItems) {
                if (!obj.has(item.key)) continue
                when (item) {
                    is Item.Switch -> write(item.key, obj.getBoolean(item.key))
                    is Item.Num -> write(item.key, obj.getInt(item.key))
                    is Item.Text -> write(item.key, obj.getString(item.key))
                    is Item.Choice -> write(item.key, obj.getString(item.key))
                }
                n++
            }
            loadAll()
            toast(getString(R.string.act_import_ok, n))
        }.onFailure { toast(getString(R.string.act_import_fail, it.message ?: "error")) }
    }

    private fun requestScope() {
        val s = service ?: run { toast(getString(R.string.service_bound_fail)); return }
        runCatching {
            s.requestScope(listOf(Config.GBOARD_PACKAGE), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: MutableList<String>) {
                    runOnUiThread { toast(getString(R.string.scope_request_ok)) }
                }

                override fun onScopeRequestFailed(message: String) {
                    runOnUiThread { toast(getString(R.string.scope_request_fail, message)) }
                }
            })
        }.onFailure { toast(getString(R.string.scope_request_fail, it.message ?: "error")) }
    }

    private fun forceStop() {
        Thread {
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "am force-stop ${Config.GBOARD_PACKAGE}")
                )
                p.waitFor() == 0
            }.getOrDefault(false)
            runOnUiThread { toast(getString(if (ok) R.string.force_stop_ok else R.string.force_stop_fail)) }
        }.start()
    }

    private fun restoreTheme() {
        Thread {
            val err = ThemeInstaller.restoreDefaultTheme()
            runOnUiThread { toast(err ?: getString(R.string.restore_theme_ok)) }
        }.start()
    }

    private fun importGlossary() {
        runCatching {
            glossaryPicker.launch(arrayOf("text/*", "application/octet-stream"))
        }.onFailure { toast(it.message ?: "error") }
    }

    private fun readGlossary(uri: android.net.Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { text ->
            write("user_glossary", text)
            values["user_glossary"] = text
            toast(getString(R.string.import_glossary_ok, text.lines().size))
        } ?: toast(getString(R.string.import_glossary_fail))
    }

    private fun clipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("switchboard_config", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchboardApp(
    service: XposedService?,
    values: Map<String, Any?>,
    onBindValue: (String, Any?) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRequestScope: () -> Unit,
    onForceStop: () -> Unit,
    onRestoreTheme: () -> Unit,
    onImportGlossary: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val serviceOk = service != null
    val inScope = runCatching {
        service?.scope?.contains(Config.GBOARD_PACKAGE)
    }.getOrDefault(null) == true

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_diagnostics)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- Hero 头部 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.restart_note, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.act_export)) },
                            onClick = { onExport(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.act_import)) },
                            onClick = { onImport(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_glossary)) },
                            onClick = { onImportGlossary(); menuOpen = false },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.scope_request)) },
                            onClick = { onRequestScope(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.force_stop_gboard)) },
                            onClick = { onForceStop(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.restore_theme)) },
                            onClick = { onRestoreTheme(); menuOpen = false },
                        )
                    }
                }
            }
            // ---- 状态徽章 ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(stringResource(R.string.chip_service), serviceOk)
                StatusChip(stringResource(R.string.chip_scope), inScope)
            }
            // ---- 内容 ----
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> SettingsBody(
                        query = query,
                        onQueryChange = { query = it },
                        chip = chip,
                        onChipChange = { chip = it },
                        values = values,
                        onWrite = onBindValue,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> DiagnosticsScreen(
                        serviceBound = serviceOk,
                        inScope = inScope,
                        values = values,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (ok) Color(0xFF2EA043) else MaterialTheme.colorScheme.error,
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    )
}
