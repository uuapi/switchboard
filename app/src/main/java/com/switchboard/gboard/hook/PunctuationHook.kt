package com.switchboard.gboard.hook

import android.inputmethodservice.InputMethodService
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 中文九键数字页半角标点（移植自 RibosomeK/gboardtweak）。
 *
 * 行为：
 *  - 普通九宫格不干预（保持全角 ，。）
 *  - 进入数字页后：逗号/句号输出半角 , . 且键面同步
 *  - 数字页左侧标点栏前 4 项变为 + − × ÷
 *
 * 提交拦截：主路径 hook InputMethodService.getCurrentInputConnection 后
 * 运行时发现 InputConnection 实现类的 commitText（不依赖混淆名）；
 * 兜底 hook Gboard 18.5.4 的 InputConnectionWrapper（混淆类 aekp）的 q 方法。
 */
object PunctuationHook {
    const val GROUP = "Punct"

    /** Gboard 18.5.4 InputConnectionWrapper.commitText 兜底类/方法 */
    private const val COMMIT_WRAPPER_CLASS = "aekp"
    private const val COMMIT_WRAPPER_METHOD = "q"

    private val hookedClasses = ConcurrentHashMap.newKeySet<String>()

    fun install(module: XposedModule, cl: ClassLoader) {
        KeyboardStateTracker.install(module, cl)
        KeyLabelHooks.install(module, cl)
        KeyboardStateTracker.onDigitModeChanged { digit ->
            KeyLabelHooks.onDigitMode(digit)
        }
        hookCommitTextDiscovery(module)
        hookCommitWrapperFallback(module, cl)
        MLog.i(GROUP, "installed")
    }

    // ---------- 主路径：运行时发现 commitText ----------
    private fun hookCommitTextDiscovery(module: XposedModule) {
        val m = runCatching {
            InputMethodService::class.java.getDeclaredMethod("getCurrentInputConnection")
        }.getOrNull() ?: return
        module.hook(m).intercept { chain ->
            val result = chain.proceed()
            if (result != null) {
                ensureCommitTextHooked(module, result.javaClass)
            }
            result
        }
        MLog.i(GROUP, "getCurrentInputConnection discovery hook installed")
    }

    private fun ensureCommitTextHooked(module: XposedModule, clazz: Class<*>) {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            val name = c.name
            if (hookedClasses.add(name)) {
                for (m in c.declaredMethods) {
                    if (m.name != "commitText") continue
                    if (m.parameterTypes.isEmpty()) continue
                    if (!CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])) continue
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val newArgs = chain.getArgs().toTypedArray()
                        transformCommit(newArgs)
                        chain.proceedWith(newArgs)
                    }
                    MLog.d(GROUP, "commitText hooked on $name")
                }
            }
            c = c.superclass
        }
    }

    // ---------- 兜底路径：Gboard InputConnectionWrapper ----------
    private fun hookCommitWrapperFallback(module: XposedModule, cl: ClassLoader) {
        val clazz = runCatching { cl.loadClass(COMMIT_WRAPPER_CLASS) }.getOrNull() ?: return
        var count = 0
        for (m in clazz.declaredMethods) {
            if (m.name != COMMIT_WRAPPER_METHOD) continue
            if (m.parameterTypes.none { CharSequence::class.java.isAssignableFrom(it) }) continue
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val newArgs = chain.getArgs().toTypedArray()
                transformCommit(newArgs)
                chain.proceedWith(newArgs)
            }
            count++
        }
        if (count > 0) MLog.i(GROUP, "fallback commit wrapper hooked ($count methods)")
    }

    // ---------- 改写逻辑 ----------
    private fun transformCommit(args: Array<Any?>): Boolean {
        val index = args.indexOfFirst { it is CharSequence }
        if (index < 0) return false
        val text = args[index] as CharSequence
        if (!KeyboardStateTracker.isDigitModeActive() || !ImeState.isChinese()) return false

        // 运算符优先（键面 + − × ÷ 及其原标点变体）
        MathSymbols.transform(text)?.let {
            args[index] = it
            return true
        }
        // 逗号/句号半角
        when (text.toString()) {
            "，" -> args[index] = ","
            "。" -> args[index] = "."
        }
        return false
    }
}

/**
 * 键面标签同步（移植自 gboardtweak KeyLabelHooks）：
 * 跟踪所有 SoftKeyView，进入数字页时把 ，。 键面改为 , .，
 * 并把 ReadingTextCandidateHolderView 前 4 键改为 + − × ÷。
 */
object KeyLabelHooks {
    private const val GROUP = "KeyLabel"
    private const val SOFT_KEY_VIEW_CLASS =
        "com.google.android.libraries.inputmethod.widgets.SoftKeyView"
    private const val STRIP_HOLDER_SUFFIX = "ReadingTextCandidateHolderView"

    private val softKeyViews: MutableSet<android.view.View> =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<android.view.View, Boolean>())
        )

    /** TextView → 原全角标签（离开数字页恢复用） */
    private val convertedLabels = java.util.WeakHashMap<android.widget.TextView, String>()

    private var handler: android.os.Handler? = null

    fun install(module: XposedModule, cl: ClassLoader) {
        handler = android.os.Handler(android.os.Looper.getMainLooper())
        val clazz = runCatching { cl.loadClass(SOFT_KEY_VIEW_CLASS) }.getOrNull() ?: run {
            MLog.w(GROUP, "$SOFT_KEY_VIEW_CLASS not found")
            return
        }
        for (ctor in clazz.declaredConstructors) {
            runCatching {
                ctor.isAccessible = true
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? android.view.View)?.let { softKeyViews.add(it) }
                    result
                }
            }
        }
        MLog.i(GROUP, "SoftKeyView tracking installed")
    }

    fun onDigitMode(digit: Boolean) {
        val h = handler ?: return
        if (digit) {
            h.post { relabelAll() }
        } else {
            h.post { restoreAll() }
        }
    }

    private fun relabelAll() {
        MathSymbols.clearReplaceMappings()
        val mappings = mutableMapOf<String, String>()
        val views = synchronized(softKeyViews) { softKeyViews.toList() }
        for (v in views) {
            if (!v.isShown) continue
            // 1) 逗号/句号键面
            labelTextView(v)?.let { tv ->
                val t = tv.text?.toString()
                when (t) {
                    "，" -> { convertedLabels[tv] = t; tv.text = "," }
                    "。" -> { convertedLabels[tv] = t; tv.text = "." }
                }
            }
            // 2) 标点栏前 4 键 → 运算符
            if (stripHolder(v) != null) {
                relabelOperatorStrip(v, mappings)
            }
        }
        if (mappings.isNotEmpty()) MathSymbols.setReplaceMappings(mappings)
    }

    private fun relabelOperatorStrip(view: android.view.View, mappings: MutableMap<String, String>) {
        val holder = stripHolder(view) ?: return
        val keys = orderedSoftKeys(holder).take(MathSymbols.SLOT_COUNT)
        if (keys.size < MathSymbols.SLOT_COUNT) {
            scheduleRetry()
            return
        }
        keys.forEachIndexed { i, keyView ->
            val tv = labelTextView(keyView) ?: return@forEachIndexed
            val original = tv.text?.toString() ?: return@forEachIndexed
            val symbol = MathSymbols.displaySymbols[i]
            if (original != symbol) {
                mappings[original] = symbol
                mappings[fullWidthVariant(original)] = symbol
                tv.text = symbol
            }
        }
    }

    private fun fullWidthVariant(s: String): String = when (s) {
        "?" -> "？"; "!" -> "！"; "…" -> "……"; "~" -> "～"
        else -> s
    }

    private var retryCount = 0

    private fun scheduleRetry() {
        val h = handler ?: return
        if (retryCount >= 8) return
        retryCount++
        h.postDelayed({ if (KeyboardStateTracker.isDigitModeActive()) relabelAll() }, 50L)
    }

    private fun restoreAll() {
        MathSymbols.clearReplaceMappings()
        for ((tv, original) in convertedLabels.entries.toList()) {
            runCatching { tv.text = original }
        }
        convertedLabels.clear()
    }

    /** 沿父链找类名以 ReadingTextCandidateHolderView 结尾的容器 */
    private fun stripHolder(view: android.view.View): android.view.ViewGroup? {
        var p = view.parent
        while (p != null) {
            if (p is android.view.ViewGroup &&
                p.javaClass.name.endsWith(STRIP_HOLDER_SUFFIX)
            ) return p
            p = p.parent
        }
        return null
    }

    /** holder 下按父序排列的可见 SoftKeyView 列表 */
    private fun orderedSoftKeys(holder: android.view.ViewGroup): List<android.view.View> {
        val out = mutableListOf<android.view.View>()
        collectSoftKeys(holder, out)
        return out.filter { it.isShown }
    }

    private fun collectSoftKeys(group: android.view.ViewGroup, out: MutableList<android.view.View>) {
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            if (softKeyViews.contains(c)) out.add(c)
            if (c is android.view.ViewGroup) collectSoftKeys(c, out)
        }
    }

    /** SoftKeyView 的标签 TextView：第一个文本长度 ≤ 3 的直接子 TextView */
    private fun labelTextView(view: android.view.View): android.widget.TextView? {
        if (view is android.widget.TextView && (view.text?.length ?: 0) in 1..3) return view
        if (view !is android.view.ViewGroup) return null
        for (i in 0 until view.childCount) {
            val c = view.getChildAt(i)
            if (c is android.widget.TextView && (c.text?.length ?: 0) in 1..3) return c
            if (c is android.view.ViewGroup) {
                labelTextView(c)?.let { return it }
            }
        }
        return null
    }
}
