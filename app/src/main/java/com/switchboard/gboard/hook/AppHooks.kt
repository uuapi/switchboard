package com.switchboard.gboard.hook

import android.content.Context
import android.widget.Toast
import com.switchboard.gboard.Config
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * 挂 Application.attach 拿到 Gboard 进程的 Context。
 * GboardHook/KeyFlux 均验证过该点稳定可用。
 */
object AppHooks {
    const val GROUP = "App"

    @Volatile
    var appContext: Context? = null
        private set

    private val listeners = mutableListOf<(Context) -> Unit>()
    private var toastShown = false

    /** 注册回调：context 已就绪则立即执行，否则在 attach 后执行 */
    fun onContextReady(listener: (Context) -> Unit) {
        val ctx = appContext
        if (ctx != null) {
            runCatching { listener(ctx) }
        }
        synchronized(listeners) { listeners.add(listener) }
    }

    fun install(module: XposedModule, cl: ClassLoader, isFirstPackage: Boolean = true) {
        val attach = cl.loadClass("android.app.Application")
            .getDeclaredMethod("attach", Context::class.java)
        module.hook(attach).intercept { chain ->
            val result = chain.proceed()
            val ctx = chain.getArg(0) as? Context
            if (ctx != null && ctx.packageName == Config.GBOARD_PACKAGE) {
                if (appContext == null) {
                    appContext = ctx
                    val ls = synchronized(listeners) { listeners.toList() }
                    for (l in ls) {
                        runCatching { l(ctx) }
                            .onFailure { MLog.e(GROUP, "context listener failed", it) }
                    }
                    if (Config.startupToast && isFirstPackage && !toastShown) {
                        toastShown = true
                        runCatching {
                            Toast.makeText(ctx, "Switchboard 已注入 ✓", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MLog.i(GROUP, "context ready (firstPackage=$isFirstPackage)")
                }
            }
            result
        }
        MLog.i(GROUP, "attach hook installed")
    }
}
