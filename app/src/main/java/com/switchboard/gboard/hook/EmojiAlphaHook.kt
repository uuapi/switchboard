package com.switchboard.gboard.hook

import android.graphics.Canvas
import android.widget.TextView
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * 候选栏 emoji 100% 不透明（移植自 FuckGboard CandidateEmojiHook）：
 * AutoCenterScaleTextView#onDraw 后把含 emoji 的文本画笔强制置为全不透明。
 */
object EmojiAlphaHook {
    const val GROUP = "EmojiAlpha"

    private const val TARGET_CLASS =
        "com.google.android.libraries.inputmethod.widgets.text.AutoCenterScaleTextView"

    fun install(module: XposedModule, cl: ClassLoader) {
        val clazz = runCatching { cl.loadClass(TARGET_CLASS) }.getOrNull() ?: run {
            MLog.w(GROUP, "$TARGET_CLASS not found")
            return
        }
        val onDraw = runCatching {
            clazz.getDeclaredMethod("onDraw", Canvas::class.java)
        }.getOrNull() ?: run {
            MLog.w(GROUP, "onDraw not found")
            return
        }
        module.hook(onDraw).intercept { chain ->
            val result = chain.proceed()
            val tv = chain.thisObject as? TextView ?: return@intercept result
            val text = tv.text
            if (text != null && containsEmoji(text)) {
                val paint = tv.paint
                if (paint.alpha != 255) paint.alpha = 255
                paint.color = paint.color or 0xFF000000.toInt()
            }
            result
        }
        MLog.i(GROUP, "hooked: $TARGET_CLASS")
    }

    private fun containsEmoji(text: CharSequence): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < len) {
                return true
            }
            if (ch in '‼'..'\u3299' || ch == '\uFE0F') return true
            i++
        }
        return false
    }
}
