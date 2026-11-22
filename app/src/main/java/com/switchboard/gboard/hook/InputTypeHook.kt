package com.switchboard.gboard.hook

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * 密码框完整键盘（移植自 kooritea/ForceGboardInputType）：
 * 在 Gboard 看到输入框类型之前把 EditorInfo.inputType 改写为普通文本，
 * 使受限输入框（密码等）也能显示完整全键盘。
 *
 * ⚠ 安全权衡：启用后 Gboard 有机会记录密码框输入（隐身模式可能被绕过）。
 */
object InputTypeHook {
    const val GROUP = "InputType"

    fun install(module: XposedModule) {
        val ims = InputMethodService::class.java
        for (name in arrayOf("onStartInput", "onStartInputView")) {
            val m = runCatching {
                ims.getDeclaredMethod(
                    name,
                    EditorInfo::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            }.getOrNull() ?: continue
            module.hook(m).intercept { chain ->
                val info = chain.getArg(0) as? EditorInfo
                if (info != null) {
                    val rewritten = rewrite(info.inputType)
                    if (rewritten != info.inputType) {
                        MLog.d(GROUP, "$name: ${info.inputType} -> $rewritten")
                        info.inputType = rewritten
                        val newArgs = chain.getArgs().toTypedArray()
                        newArgs[0] = info
                        return@intercept chain.proceedWith(chain.thisObject, newArgs)
                    }
                }
                chain.proceed()
            }
        }
        MLog.i(GROUP, "onStartInput/onStartInputView hooked")
    }

    private fun rewrite(inputType: Int): Int {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        var result = inputType
        when (variation) {
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ->
                result = (inputType and InputType.TYPE_MASK_VARIATION.inv()) or
                    InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            ->
                result = inputType and InputType.TYPE_MASK_VARIATION.inv()
        }
        // 非文本类（数字/电话/日期等受限键盘）一律改为文本
        if ((result and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            result = InputType.TYPE_CLASS_TEXT
        }
        return result
    }
}
