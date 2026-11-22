package com.switchboard.gboard.hook

import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule

/**
 * 键盘状态跟踪（移植自 gboardtweak KeyboardStateTracker）。
 *
 * 识别"数字页"：上箭头数字页不是新 Keyboard 对象，而是 LatinKeyboardWithNumberMode
 * 的状态位翻转。字母页 0x4201808400，数字页 0x64201808400 —— 差异位掩码 0x60000000000。
 *
 * 观测锚点：Gboard 18.5.4（Keyboard#e 激活 / #f 失活 / #cF 状态变更、字段 F、方法 cz）。
 */
object KeyboardStateTracker {
    const val GROUP = "KeyState"

    const val KEYBOARD_CLASS =
        "com.google.android.libraries.inputmethod.keyboard.impl.Keyboard"
    const val NUMBER_MODE_KEYBOARD_CLASS =
        "com.google.android.apps.inputmethod.latin.keyboard.LatinKeyboardWithNumberMode"
    const val DIGIT_MODE_STATE_MASK = 0x60000000000L

    @Volatile
    private var state: Long = 0L

    @Volatile
    private var activeName: String? = null

    @Volatile
    private var activeClass: String? = null

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun onDigitModeChanged(listener: (Boolean) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        val kbClass = runCatching { cl.loadClass(KEYBOARD_CLASS) }.getOrNull() ?: run {
            MLog.w(GROUP, "$KEYBOARD_CLASS not found")
            return
        }
        for (m in kbClass.declaredMethods) {
            when (m.name) {
                "e" -> hook(module, m) { keyboard ->
                    activeClass = keyboard?.javaClass?.name
                    activeName = readKeyboardName(keyboard)
                    state = readState(keyboard)
                    fire()
                }
                "f" -> hook(module, m) {
                    activeClass = null
                    activeName = null
                    state = 0L
                    fire()
                }
                "cF" -> {
                    if (m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == java.lang.Long.TYPE &&
                        m.parameterTypes[1] == java.lang.Long.TYPE
                    ) {
                        hookArgs(module, m) { args ->
                            (args.getOrNull(1) as? Long)?.let { state = it }
                            fire()
                        }
                    }
                }
            }
        }
        MLog.i(GROUP, "keyboard state tracking installed")
    }

    private fun hook(
        module: XposedModule,
        method: java.lang.reflect.Method,
        after: (Any?) -> Unit,
    ) {
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching { after(chain.thisObject) }
            result
        }
    }

    private fun hookArgs(
        module: XposedModule,
        method: java.lang.reflect.Method,
        after: (List<Any?>) -> Unit,
    ) {
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching { after(chain.args) }
            result
        }
    }

    private fun fire() {
        val digit = isDigitModeActive()
        val ls = synchronized(listeners) { listeners.toList() }
        for (l in ls) runCatching { l(digit) }
    }

    private fun readKeyboardName(keyboard: Any?): String? {
        if (keyboard == null) return null
        return runCatching {
            val m = keyboard.javaClass.getMethod("cz")
            m.isAccessible = true
            m.invoke(keyboard) as? String
        }.getOrNull()
    }

    private fun readState(keyboard: Any?): Long {
        if (keyboard == null) return 0L
        return runCatching {
            val f = keyboard.javaClass.getDeclaredField("F")
            f.isAccessible = true
            (f.get(keyboard) as? Long) ?: 0L
        }.getOrDefault(0L)
    }

    fun isDigitModeActive(): Boolean =
        activeClass == NUMBER_MODE_KEYBOARD_CLASS && (state and DIGIT_MODE_STATE_MASK) == DIGIT_MODE_STATE_MASK

    fun activeName(): String? = activeName
}
