package com.switchboard.gboard.hook

import android.content.res.Configuration
import android.content.res.TypedArray
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * AMOLED 纯黑（移植自 hxreborn/gboard-material-expressive-black）：
 * 深色模式下把 system_surface_container*（非 high 层级）解析结果改写为纯黑。
 * Hook 的是框架 TypedArray.getColor，不依赖 Gboard 混淆名，兼容性最好。
 */
object AmoledHook {
    const val GROUP = "Amoled"
    private const val BLACK = 0xFF000000.toInt()

    fun install(module: XposedModule) {
        val getColor = runCatching {
            TypedArray::class.java.getDeclaredMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrElse {
            MLog.e(GROUP, "resolve TypedArray.getColor failed", it)
            return
        }
        module.hook(getColor).intercept { chain ->
            val result = chain.proceed()
            if (result !is Int) return@intercept result
            val ta = chain.thisObject as? TypedArray ?: return@intercept result
            val night = (ta.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            if (night != Configuration.UI_MODE_NIGHT_YES) return@intercept result
            val index = chain.getArg(0) as? Int ?: return@intercept result
            val resId = runCatching { ta.getResourceId(index, 0) }.getOrDefault(0)
            if (resId == 0) return@intercept result
            val name = runCatching { ta.resources.getResourceEntryName(resId) }
                .getOrNull() ?: return@intercept result
            if (name.startsWith("system_surface_container") && !name.contains("high")) {
                BLACK
            } else {
                result
            }
        }
        MLog.i(GROUP, "TypedArray.getColor hooked")
    }
}
