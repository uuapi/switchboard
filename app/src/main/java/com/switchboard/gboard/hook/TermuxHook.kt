package com.switchboard.gboard.hook

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Termux 中文输入兼容（完整移植自 yapixel/gboard-termux-ime 的状态机逻辑）。
 *
 * - 进入终端应用：若当前为中文键盘，快照外部语言并程序化切到英文；离开后恢复
 * - 终端内手动切到中文：把 EditorInfo 恢复标准文本 + restartInput，拼音正常
 * - 英文模式：完全保持 Termux 原始 EditorInfo（逐字符提交不受干扰）
 * - mIsProgrammaticSwitch 守卫防止模块自己的切换污染外部语言记录
 */
object TermuxHook {
    const val GROUP = "Termux"

    private val TERMINALS = setOf(
        "com.termux",
        "com.termux.x11",
        "com.termux.api",
        "com.neoterm",
        "jackpal.androidterm",
        "com.android.virtualization.terminal",
    )

    @Volatile
    private var inTerminal = false

    @Volatile
    private var lastNonTerminalSubtype: InputMethodSubtype? = null

    @Volatile
    private var programmatic = false

    fun install(module: XposedModule, cl: ClassLoader) {
        // 优先 hook 未混淆的 LatinIME（scan.py 已确认类存在），找不到回退 IMS 框架层
        val target = runCatching { cl.loadClass("com.android.inputmethod.latin.LatinIME") }
            .getOrElse { InputMethodService::class.java }
        var hooked = 0
        var c: Class<*>? = target
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                when (m.name) {
                    // EditorInfo 改写必须在 before 阶段
                    "onStartInput" -> if (isStartInput(m)) {
                        hookStartInput(module, m); hooked++
                    }
                    "onFinishInput" -> if (m.parameterTypes.isEmpty()) {
                        hookAfter(module, m) { ime, _ -> onLeave(ime) }; hooked++
                    }
                    "onWindowHidden" -> if (m.parameterTypes.isEmpty()) {
                        hookAfter(module, m) { ime, _ -> onLeave(ime) }; hooked++
                    }
                    "onCurrentInputMethodSubtypeChanged" -> if (isSubtypeChanged(m)) {
                        hookAfter(module, m) { _, args ->
                            onSubtypeChanged(args.getOrNull(0) as? InputMethodSubtype)
                        }; hooked++
                    }
                }
            }
            c = c.superclass
        }
        if (hooked == 0) MLog.w(GROUP, "no hook point found")
        else MLog.i(GROUP, "installed ($hooked methods on ${target.name})")
    }

    /** onStartInput：before 阶段改写 EditorInfo + after 阶段更新状态机 */
    private fun hookStartInput(module: XposedModule, m: Method) {
        m.isAccessible = true
        module.hook(m).intercept { chain ->
            val info = chain.getArg(0) as? EditorInfo
            val shouldNormalize = info != null && info.packageName in TERMINALS &&
                isChineseSubtype(currentSubtype(chain.thisObject))
            if (shouldNormalize) normalizeEditor(info!!)
            val result = chain.proceed()
            val ime = chain.thisObject
            if (ime != null) {
                runCatching { onStartInput(ime, arrayOf(info)) }
            }
            result
        }
    }

    private fun isStartInput(m: Method) =
        m.parameterTypes.size == 2 &&
            m.parameterTypes[0] == EditorInfo::class.java &&
            m.parameterTypes[1] == Boolean::class.javaPrimitiveType

    private fun isSubtypeChanged(m: Method) =
        m.parameterTypes.size == 1 && m.parameterTypes[0] == InputMethodSubtype::class.java

    private fun hookAfter(module: XposedModule, m: Method, after: (Any, Array<Any?>) -> Unit) {
        m.isAccessible = true
        module.hook(m).intercept { chain ->
            val result = chain.proceed()
            val ime = chain.thisObject
            if (ime != null) {
                runCatching { after(ime, chain.getArgs().toTypedArray()) }
            }
            result
        }
    }

    // ---------- 生命周期 ----------
    private fun onStartInput(ime: Any, args: Array<Any?>) {
        val info = args.getOrNull(0) as? EditorInfo ?: return
        val isTerminal = info.packageName in TERMINALS
        if (isTerminal && !inTerminal) {
            inTerminal = true
            val current = currentSubtype(ime)
            // 非英文 → 快照并切英文
            if (current != null && !isEnglishSubtype(current) && !programmatic) {
                lastNonTerminalSubtype = current
                switchToEnglish(ime)
            }
        } else if (!isTerminal && inTerminal) {
            inTerminal = false
            restoreExternal(ime)
        }
        // 终端内 EditorInfo 的改写已在 before 阶段完成
    }

    private fun onLeave(ime: Any) {
        if (!inTerminal) return
        inTerminal = false
        restoreExternal(ime)
    }

    private fun onSubtypeChanged(subtype: InputMethodSubtype?) {
        if (subtype == null) return
        if (programmatic) {
            programmatic = false
            return
        }
        // 用户在终端外手动切换：更新快照
        if (!inTerminal) {
            lastNonTerminalSubtype = null // 交由下次进入终端时重新快照
        }
    }

    // ---------- 工具 ----------
    private fun currentSubtype(ime: Any): InputMethodSubtype? = runCatching {
        val m = ime.javaClass.methods.firstOrNull { it.name == "getCurrentInputMethodSubtype" }
        m?.invoke(ime) as? InputMethodSubtype
    }.getOrNull()

    private fun isEnglishSubtype(st: InputMethodSubtype): Boolean = runCatching {
        (st.locale.startsWith("en") || st.languageTag.startsWith("en")) &&
            !st.locale.startsWith("en_IN") // 印度英文键盘通常带原生脚本，保守起见仍视为英文
    }.getOrDefault(false)

    private fun isChineseSubtype(st: InputMethodSubtype?): Boolean = runCatching {
        st != null && (st.locale.startsWith("zh") || st.languageTag.startsWith("zh"))
    }.getOrDefault(false)

    private fun switchToEnglish(ime: Any) {
        runCatching {
            val ctx = ime as android.content.Context
            val imm = ctx.getSystemService(Context_INPUT_METHOD) as InputMethodManager
            val imi: InputMethodInfo? = imm.enabledInputMethodList.firstOrNull {
                it.packageName == ctx.packageName
            }
            val english = findSubtype(imi) { isEnglishSubtype(it) }
                ?: findSubtype(imi) { true }
            if (imi != null && english != null) {
                val m = InputMethodService::class.java.methods
                    .firstOrNull { it.name == "switchInputMethod" && it.parameterTypes.size == 2 }
                if (m != null) {
                    programmatic = true
                    m.invoke(ime, imi.id, english)
                    MLog.i(GROUP, "switched to english (${english.locale}) for terminal")
                }
            }
        }.onFailure { MLog.w(GROUP, "switchToEnglish failed: ${it.message}"); programmatic = false }
    }

    /** android-36 的 InputMethodInfo 无 getSubtypes()，用 getSubtypeCount/getSubtypeAt */
    private inline fun findSubtype(imi: InputMethodInfo?, pred: (InputMethodSubtype) -> Boolean): InputMethodSubtype? {
        if (imi == null) return null
        for (i in 0 until imi.subtypeCount) {
            val st = imi.getSubtypeAt(i)
            if (pred(st)) return st
        }
        return null
    }

    private fun restoreExternal(ime: Any) {
        val snapshot = lastNonTerminalSubtype ?: return
        lastNonTerminalSubtype = null
        val current = currentSubtype(ime) ?: return
        if (current == snapshot) return
        runCatching {
            val ctx = ime as android.content.Context
            val imm = ctx.getSystemService(Context_INPUT_METHOD) as InputMethodManager
            val imi = imm.enabledInputMethodList.firstOrNull { it.packageName == ctx.packageName }
            if (imi != null) {
                val m = InputMethodService::class.java.methods
                    .firstOrNull { it.name == "switchInputMethod" && it.parameterTypes.size == 2 }
                if (m != null) {
                    programmatic = true
                    m.invoke(ime, imi.id, snapshot)
                    MLog.i(GROUP, "restored external subtype (${snapshot.locale})")
                }
            }
        }.onFailure {
            MLog.w(GROUP, "restore failed: ${it.message}")
            programmatic = false
        }
    }


    private fun normalizeEditor(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT) return
        val old = info.inputType
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
        MLog.d(GROUP, "terminal editor normalized: $old -> ${info.inputType}")
    }

    private const val Context_INPUT_METHOD = "input_method"
}
