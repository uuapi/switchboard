package com.switchboard.gboard.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethod
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.switchboard.gboard.Config
import com.switchboard.gboard.hook.toolbar.ToolbarView
import com.switchboard.gboard.hook.toolbar.UndoManager
import com.switchboard.gboard.hook.toolbar.VaultDb
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * 键盘工具栏（简化移植自 kvmy666/Gboard GboardPlusHook 的文本功能）。
 *
 * - setInputView after：反射 mInputFrame，把键盘视图包进自带容器（工具栏在顶部），
 *   tag 防重复注入。after-hook 避免替换 arg0 导致 Gboard 内部强转 CCE。
 * - InputConnection 包装：记录 commitText/deleteSurroundingText 供撤销。
 * - ClipboardManager.setPrimaryClip：复制内容自动入库保险库。
 * - 摇一摇撤销：加速度传感器阈值触发。
 */
object ToolbarHook {
    const val GROUP = "Toolbar"

    private val main = Handler(Looper.getMainLooper())
    private var db: VaultDb? = null
    private val undo = UndoManager()
    private var toolbarRef: java.lang.ref.WeakReference<ToolbarView>? = null

    fun install(module: XposedModule, cl: ClassLoader) {
        AppHooks.onContextReady { ctx -> db = VaultDb(ctx) }
        hookInputView(module)
        hookClipboardRecording(module)
        MLog.i(GROUP, "installed")
    }

    // ---------- 注入工具栏 ----------
    private fun hookInputView(module: XposedModule) {
        val ims = InputMethodService::class.java
        val setInputView = runCatching {
            ims.getDeclaredMethod("setInputView", View::class.java)
        }.getOrNull() ?: run {
            MLog.w(GROUP, "setInputView not found")
            return
        }
        module.hook(setInputView).intercept { chain ->
            val result = chain.proceed()
            val service = chain.thisObject as? InputMethodService ?: return@intercept result
            val inputView = chain.getArg(0) as? View ?: return@intercept result
            main.post {
                runCatching { injectToolbar(service, inputView) }
                    .onFailure { MLog.e(GROUP, "inject failed", it) }
            }
            result
        }

        // 当前 IC 捕获（撤销记录 + 工具栏操作目标）
        val getCurrentIC = runCatching {
            ims.getDeclaredMethod("getCurrentInputConnection")
        }.getOrNull()
        if (getCurrentIC != null) {
            module.hook(getCurrentIC).intercept { chain ->
                val result = chain.proceed()
                if (result != null) {
                    toolbarRef?.get()?.let { tb ->
                        tb.inputConnection = UndoManager.RecordingConnection(
                            result as InputConnection, undo
                        )
                    }
                    undoCaptureIC = result as? InputConnection
                }
                result
            }
        }

        // 撤销栈随输入会话重置
        for (name in arrayOf("onStartInput")) {
            val m = runCatching {
                ims.getDeclaredMethod(
                    name,
                    android.view.inputmethod.EditorInfo::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            }.getOrNull() ?: continue
            module.hook(m).intercept { chain ->
                undo.clear()
                chain.proceed()
            }
        }
        MLog.i(GROUP, "input view hooks installed")
    }

    @Volatile
    private var undoCaptureIC: InputConnection? = null

    private fun injectToolbar(service: InputMethodService, inputView: View) {
        if (inputView.findViewWithTag<ViewGroup>(ToolbarView.TAG) != null) return
        // Auto Expand 互操作：检测并移除其工具栏（tag ae_kb_toolbar），由 Switchboard 接管
        runCatching {
            inputView.findViewWithTag<View>("ae_kb_toolbar")?.let { ae ->
                (ae.parent as? ViewGroup)?.removeView(ae)
                MLog.i(GROUP, "Auto Expand toolbar removed")
            }
        }
        val frame = reflectField(service, "mInputFrame") as? ViewGroup ?: run {
            MLog.w(GROUP, "mInputFrame not found")
            return
        }
        val ctx = service
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toolbar = ToolbarView(ctx).apply {
            vault = db
            undoManager = undo
        }
        toolbarRef = java.lang.ref.WeakReference(toolbar)
        container.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ),
        )
        // 保险库弹层需要锚定窗口
        // 必须先从 mInputFrame 移除，否则 addView 抛 "child already has a parent"
        frame.removeView(inputView)
        container.addView(
            inputView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ),
        )
        frame.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ),
        )
        setupShakeUndo(service, toolbar)
        MLog.i(GROUP, "toolbar injected")
    }

    private fun reflectField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            }
            c = c.superclass
        }
        return null
    }

    // ---------- 复制入库（文本 + 图片） ----------
    private fun hookClipboardRecording(module: XposedModule) {
        val cm = ClipboardManager::class.java
        val set = runCatching {
            cm.getDeclaredMethod(
                "setPrimaryClip", ClipData::class.java,
            )
        }.getOrNull() ?: return
        module.hook(set).intercept { chain ->
            runCatching {
                val clip = chain.getArg(0) as? ClipData
                val item = clip?.getItemAt(0)
                val text = item?.text
                if (!text.isNullOrBlank() && !ClipboardHook.isSensitiveText(text.toString())) {
                    db?.insert(text.toString())
                    MLog.d(GROUP, "clip recorded (${text.length} chars)")
                } else if (clip != null && item?.text == null) {
                    // 图片：描述含 image 或 uri 为 image 类型
                    val mime = clip.description?.getMimeType(0)
                    if (mime != null && mime.startsWith("image/")) {
                        val uri = item?.uri
                        if (uri != null) {
                            val ctx = AppHooks.appContext
                            if (ctx != null) {
                                val bytes = ctx.contentResolver.openInputStream(uri)?.use {
                                    it.readBytes()
                                }
                                if (bytes != null && db?.insertImage(ctx, bytes) != -1L) {
                                    MLog.d(GROUP, "image clip recorded (${bytes.size} bytes)")
                                }
                            }
                        }
                    }
                }
            }
            chain.proceed()
        }
        MLog.i(GROUP, "clipboard recording hooked")
        AppHooks.onContextReady { ctx -> watchScreenshots(ctx) }
    }

    // ---------- 截图自动入库 ----------
    private var shotObserverStarted = false

    private fun watchScreenshots(ctx: Context) {
        if (shotObserverStarted) return
        shotObserverStarted = true
        val dirs = listOf(
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "DCIM/Screenshots"),
        )
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (dir in dirs) {
            if (!dir.exists()) continue
            val observer = object : android.os.FileObserver(dir, android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (!path.endsWith(".png") && !path.endsWith(".jpg")) return
                    if (!Config.shotCaptureEnabled) return
                    handler.post {
                        runCatching {
                            val f = java.io.File(dir, path)
                            if (!f.exists() || f.length() == 0L) return@post
                            // 等待写入完成
                            val size = f.length()
                            Thread.sleep(300)
                            if (f.length() != size) return@post
                            val bytes = f.readBytes()
                            val id = db?.insertImage(ctx, bytes)
                            if (id != null && id != -1L) {
                                MLog.d(GROUP, "screenshot captured: $path")
                                if (Config.shotToClipboard) {
                                    val cm = ctx.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        ctx, ctx.packageName + ".fileprovider", f
                                    )
                                    runCatching {
                                        cm.setPrimaryClip(
                                            ClipData.newUri(ctx.contentResolver, "Switchboard shot", uri)
                                        )
                                    }
                                }
                            }
                        }.onFailure { MLog.w(GROUP, "shot capture failed: ${it.message}") }
                    }
                }
            }
            runCatching { observer.startWatching() }
        }
        MLog.i(GROUP, "screenshot watcher started (${dirs.count { it.exists() }} dirs)")
    }

    // ---------- 摇一摇撤销 ----------
    private fun setupShakeUndo(service: InputMethodService, toolbar: ToolbarView) {
        val threshold = 12f * (Config.int("shake_sensitivity", 5).coerceIn(1, 10) / 5f)
        val sm = service.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var lastShake = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val g = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val now = System.currentTimeMillis()
                if (g > 9.81f * (1f + threshold / 10f) && now - lastShake > 1500) {
                    lastShake = now
                    val ic = toolbar.inputConnection ?: undoCaptureIC
                    if (ic != null && !undo.isEmpty()) {
                        main.post {
                            android.app.AlertDialog.Builder(service)
                                .setTitle("撤销")
                                .setMessage("撤销最近一次输入/删除？")
                                .setPositiveButton("撤销") { d, _ ->
                                    undo.undo(ic)
                                    d.dismiss()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        // 输入视图显示期间注册
        toolbar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                runCatching { sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                runCatching { sm.unregisterListener(listener) }
            }
        })
    }
}
