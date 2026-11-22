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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.switchboard.gboard.Config
import com.switchboard.gboard.R
import com.switchboard.gboard.hook.ThemeInstaller
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

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

    private fun loadAll() {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: return
        values.clear()
        for (item in SettingsModel.allItems) {
            when (item) {
                is Item.Switch -> values[item.key] = prefs.getBoolean(item.key, item.def)
                is Item.Num -> values[item.key] = prefs.getInt(item.key, item.def)
                is Item.Text -> values[item.key] = prefs.getString(item.key, item.def) ?: item.def
                is Item.Choice -> values[item.key] = prefs.getString(item.key, item.def) ?: item.def
            }
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
        edit.apply()
    }

    private fun exportConfig() {
        val prefs = service?.getRemotePreferences(Config.PREF_GROUP) ?: run {
            toast(getString(R.string.service_bound_fail)); return
        }
        val obj = org.json.JSONObject()
        for (item in SettingsModel.allItems) {
            when (item) {
                is Item.Switch -> obj.put(item.key, prefs.getBoolean(item.key, item.def))
                is Item.Num -> obj.put(item.key, prefs.getInt(item.key, item.def))
                is Item.Text -> obj.put(item.key, prefs.getString(item.key, item.def) ?: item.def)
                is Item.Choice -> obj.put(item.key, prefs.getString(item.key, item.def) ?: item.def)
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

    val inScope = runCatching {
        service?.scope?.contains(Config.GBOARD_PACKAGE)
    }.getOrDefault(null) == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onExport) { Text(stringResource(R.string.act_export)) }
                    TextButton(onClick = onImport) { Text(stringResource(R.string.act_import)) }
                },
            )
        },
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
        when (tab) {
            0 -> Column {
                StatusHeader(service != null, inScope, onRequestScope, onForceStop, onRestoreTheme)
                SearchField(query) { query = it }
                SettingsScreen(
                    query = query,
                    values = values,
                    onWrite = onBindValue,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            else -> DiagnosticsScreen(
                serviceBound = service != null,
                inScope = inScope,
                values = values,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
