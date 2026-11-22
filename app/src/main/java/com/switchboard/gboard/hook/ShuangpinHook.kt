package com.switchboard.gboard.hook

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodSubtype
import com.switchboard.gboard.Config
import com.switchboard.gboard.hook.shuangpin.HintEngine
import com.switchboard.gboard.hook.shuangpin.KeyFace
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 双拼键帽提示（简化移植自 rikumi/gboard-shuangpin-hints，LGPL-2.1）。
 *
 * - 方案来源：hook SharedPreferencesImpl.getString("pinyin_scheme") + IME attach 时读默认 prefs
 * - 拼音/中文激活：hook InputMethodService.onCurrentInputMethodSubtypeChanged（框架级，零混淆依赖）
 *   subtype 名称含 双拼/shuangpin 即视为拼音激活，语言含 zh 视为中文
 * - composing 跟踪：hook InputConnection.setComposingText / commitText / finishComposingText
 * - 绘制：SoftKeyView.getOverlay() 叠加 HintDrawable（不覆盖 Gboard 自绘主字母）
 *
 * 未移植（原模块的进阶特性）：分号键、emoji 联想替换、行内大写、主字母隐藏、
 * 解码器引擎内部 preedit 反射（用 composing 文本近似）。
 */
object ShuangpinHook {
    const val GROUP = "Shuangpin"

    private val keys = WeakHashMap<View, KeyState>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var scheme: String? = null

    @Volatile
    private var pinyinActive = false

    @Volatile
    private var visible = false

    @Volatile
    private var password = false

    @Volatile
    private var composingInitial = ""

    /** composing 原文（emoji 联想用） */
    @Volatile
    private var composingRaw = ""

    private var scheduled = false
    private var imeRef: WeakReference<InputMethodService>? = null

    private var emojiRowRef: WeakReference<View>? = null
    private var semicolonBtnRef: WeakReference<View>? = null

    private class KeyState(val metadata: java.lang.reflect.Field?) {
        val hint = KeyFace.HintDrawable()
        var added = false
        var primaryLabel: android.widget.TextView? = null
        var originalAlpha: Int = -1
        var originalTextSize: Float = -1f
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        hookSchemeRead(module, cl)
        hookSubtype(module)
        hookInputView(module)
        hookComposition(module)
        trackSoftKeyViews(module, cl)
        MLog.i(GROUP, "installed")
    }

    // ---------- 方案读取 ----------
    private fun hookSchemeRead(module: XposedModule, cl: ClassLoader) {
        // 1) SharedPreferencesImpl.getString("pinyin_scheme")
        runCatching {
            val impl = cl.loadClass("android.app.SharedPreferencesImpl")
            for (m in impl.declaredMethods) {
                if (m.name != "getString") continue
                if (m.parameterTypes.size != 2 || m.parameterTypes[0] != String::class.java) continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    if (chain.getArg(0) == "pinyin_scheme") {
                        val mapped = HintEngine.scheme(result as? String)
                        if (mapped != null && mapped != scheme) {
                            scheme = mapped
                            MLog.i(GROUP, "scheme from prefs: $mapped")
                            refreshLater()
                        }
                    }
                    result
                }
            }
        }.onFailure { MLog.w(GROUP, "prefs hook failed", it) }

        // 2) IME attach 时直接读 Gboard 默认 prefs
        AppHooks.onContextReady { ctx ->
            runCatching {
                val prefs = ctx.getSharedPreferences(
                    "${Config.GBOARD_PACKAGE}_preferences", android.content.Context.MODE_PRIVATE
                )
                val value = prefs.getString("pinyin_scheme", "") ?: ""
                HintEngine.scheme(value)?.let {
                    scheme = it
                    MLog.i(GROUP, "scheme from default prefs: $it")
                    refreshLater()
                }
            }
        }
    }

    // ---------- 拼音/中文激活（subtype 名称判定） ----------
    private fun hookSubtype(module: XposedModule) {
        val m = runCatching {
            InputMethodService::class.java.getDeclaredMethod(
                "onCurrentInputMethodSubtypeChanged", InputMethodSubtype::class.java,
            )
        }.getOrNull() ?: return
        module.hook(m).intercept { chain ->
            val result = chain.proceed()
            val subtype = chain.getArg(0) as? InputMethodSubtype
            val ime = chain.thisObject as? InputMethodService
            if (subtype != null) {
                val name = runCatching {
                    if (ime != null) {
                        subtype.getDisplayName(ime, ime.packageName, ime.applicationInfo)
                            ?.toString() ?: ""
                    } else ""
                }.getOrDefault("")
                val lower = name.lowercase()
                val locale = runCatching { subtype.languageTag }.getOrNull()
                    ?: subtype.locale
                val isPinyin = lower.contains("双拼") || lower.contains("shuangpin") ||
                    lower.contains("pinyin") || lower.contains("拼音")
                val isChinese = isPinyin || locale?.startsWith("zh") == true ||
                    lower.contains("中文") || lower.contains("简体") || lower.contains("繁")
                val changed = isPinyin != pinyinActive
                pinyinActive = isPinyin
                composingInitial = ""
                if (changed || isPinyin) {
                    MLog.d(GROUP, "subtype: pinyin=$isPinyin name=$name")
                    refreshLater()
                }
            }
            result
        }
    }

    // ---------- 输入视图生命周期 ----------
    private fun hookInputView(module: XposedModule) {
        val ims = InputMethodService::class.java
        for ((name, enter) in arrayOf(
            "onStartInputView" to true,
            "onFinishInputView" to false,
        )) {
            val m = runCatching {
                ims.getDeclaredMethod(
                    name,
                    android.view.inputmethod.EditorInfo::class.java,
                    Boolean::class.javaPrimitiveType,
                )
            }.getOrNull() ?: continue
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                if (enter) {
                    visible = true
                    val info = chain.getArg(0) as? android.view.inputmethod.EditorInfo
                    password = isPassword(info?.inputType ?: 0)
                    (chain.thisObject as? InputMethodService)?.let {
                        imeRef = WeakReference(it)
                        observeTree(it.window?.window?.decorView)
                    }
                } else {
                    visible = false
                }
                composingInitial = ""
                refreshLater()
                result
            }
        }
        // onWindowShown：键盘重建后重新扫描
        runCatching {
            val m = ims.getDeclaredMethod("onWindowShown")
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? InputMethodService)?.let {
                    observeTree(it.window?.window?.decorView)
                }
                refreshLater()
                result
            }
        }
    }

    private fun isPassword(type: Int): Boolean {
        val kind = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (kind == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (kind == InputType.TYPE_CLASS_TEXT && variation in intArrayOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ))
    }

    // ---------- composing 跟踪 ----------
    private fun hookComposition(module: XposedModule) {
        val m = runCatching {
            InputMethodService::class.java.getDeclaredMethod("getCurrentInputConnection")
        }.getOrNull() ?: return
        val hooked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        module.hook(m).intercept { chain ->
            val result = chain.proceed()
            if (result != null) {
                lastIc = result as? android.view.inputmethod.InputConnection
                var c: Class<*>? = result.javaClass
                while (c != null && c != Any::class.java) {
                    if (hooked.add(c.name)) {
                        for (method in c.declaredMethods) {
                            when (method.name) {
                                "setComposingText" -> {
                                    if (method.parameterTypes.isNotEmpty() &&
                                        CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
                                    ) {
                                        method.isAccessible = true
                                        module.hook(method).intercept { inner ->
                                            val r = inner.proceed()
                                            (inner.getArg(0) as? CharSequence)?.let {
                                                onComposition(it)
                                            }
                                            r
                                        }
                                    }
                                }
                                "commitText", "finishComposingText" -> {
                                    method.isAccessible = true
                                    module.hook(method).intercept { inner ->
                                        val r = inner.proceed()
                                        onComposition("")
                                        r
                                    }
                                }
                            }
                        }
                    }
                    c = c.superclass
                }
            }
            result
        }
    }

    private fun onComposition(text: CharSequence) {
        val next = HintEngine.independentInitial(text, scheme)
        val changed = next != composingInitial || text.toString() != composingRaw
        composingInitial = next
        composingRaw = text.toString()
        if (changed) {
            refreshLater()
            updateEmojiRow()
        }
    }

    // ---------- 键位观察 ----------
    private fun trackSoftKeyViews(module: XposedModule, cl: ClassLoader) {
        val clazz = runCatching { cl.loadClass(KeyFace.KEY_CLASS) }.getOrNull() ?: run {
            MLog.w(GROUP, "${KeyFace.KEY_CLASS} not found")
            return
        }
        val metadataField = KeyFace.metadataField(clazz)
        for (ctor in clazz.declaredConstructors) {
            runCatching {
                ctor.isAccessible = true
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { observe(it, metadataField) }
                    result
                }
            }
        }
    }

    private fun observe(view: View, metadata: java.lang.reflect.Field?) {
        if (keys.containsKey(view)) return
        keys[view] = KeyState(metadata)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refreshLater()
            override fun onViewDetachedFromWindow(v: View) {
                keys.remove(v)
                val state = keys[v]
                if (state != null && state.added) {
                    runCatching { v.overlay.remove(state.hint) }
                    state.added = false
                }
            }
        })
        refreshLater()
    }

    private fun observeTree(root: View?) {
        if (root == null) return
        val clazz = root.javaClass
        val metadata = if (clazz.name == KeyFace.KEY_CLASS) {
            KeyFace.metadataField(clazz)
        } else null
        if (metadata != null) observe(root, metadata)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) observeTree(root.getChildAt(i))
        }
    }

    // ---------- 渲染 ----------
    private fun refreshLater() {
        if (android.os.Looper.myLooper() != mainHandler.looper) {
            mainHandler.post { refreshLater() }
            return
        }
        if (!scheduled) {
            scheduled = true
            mainHandler.post {
                scheduled = false
                render()
            }
        }
    }

    private fun render() {
        val activeScheme = scheme
        val show = visible && !password && pinyinActive && activeScheme != null
        val waiting = composingInitial.isNotEmpty()
        val size = Config.shuangpinSize
        val dynamic = Config.shuangpinDynamic
        val singleVowels = !dynamic || !waiting
        val blankLetters = Config.shuangpinBlankLetters
        val letterSize = Config.shuangpinLetterSize

        for ((view, state) in keys.entries.toList()) {
            if (view == null) continue
            try {
                if (!view.isShown) {
                    if (state.added) {
                        runCatching { view.overlay.remove(state.hint) }
                        state.added = false
                    }
                    restorePrimaryLabel(state)
                    continue
                }
                val key = KeyFace.key(view, state.metadata)
                val hint = if (!show || key == 0.toChar()) HintEngine.KeyHint.EMPTY else {
                    HintEngine.hint(activeScheme, key, if (dynamic) composingInitial else "", singleVowels)
                }
                if (hint.isEmpty || hint.top.isEmpty() && hint.bottom.isEmpty()) {
                    if (state.added) {
                        runCatching { view.overlay.remove(state.hint) }
                        state.added = false
                    }
                } else {
                    state.hint.update(view, HintEngine.KeyHint(hint.top, hint.center, hint.bottom), size)
                    if (!state.added) {
                        runCatching { view.overlay.add(state.hint) }
                        state.added = true
                    }
                }
                // 主字母隐藏 / 字号
                val primary = primaryLabel(view, state)
                if (primary != null && key in 'a'..'z') {
                    if (show && blankLetters) hidePrimaryLabel(primary, state)
                    else restorePrimaryLabel(state)
                    if (letterSize > 0 && show) {
                        if (state.originalTextSize < 0) state.originalTextSize = primary.textSize
                        val target = letterSize * view.resources.displayMetrics.scaledDensity
                        if (primary.textSize != target) primary.setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_SP, letterSize.toFloat()
                        )
                    } else if (state.originalTextSize > 0 && primary.textSize != state.originalTextSize) {
                        primary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, state.originalTextSize)
                        state.originalTextSize = -1f
                    }
                }
            } catch (t: Throwable) {
                MLog.w(GROUP, "render key failed: ${t.message}")
            }
        }
        updateSemicolonButton(show)
    }

    private fun primaryLabel(view: View, state: KeyState): android.widget.TextView? {
        state.primaryLabel?.let { if (it.isAttachedToWindow) return it }
        val label = KeyFace.textLabel(view) ?: return null
        state.primaryLabel = label
        return label
    }

    private fun hidePrimaryLabel(tv: android.widget.TextView, state: KeyState) {
        if (state.originalAlpha < 0) state.originalAlpha = tv.alpha.toInt()
        if (tv.alpha > 0f) tv.alpha = 0f
    }

    private fun restorePrimaryLabel(state: KeyState) {
        if (state.originalAlpha >= 0) {
            state.primaryLabel?.let { runCatching { it.alpha = state.originalAlpha.toFloat() } }
            state.originalAlpha = -1
        }
        if (state.originalTextSize > 0) {
            state.primaryLabel?.let {
                runCatching { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, state.originalTextSize) }
            }
            state.originalTextSize = -1f
        }
    }

    // ---------- 分号键（浮动按钮，MS 等方案的 ; 需求） ----------
    private fun updateSemicolonButton(show: Boolean) {
        val want = show && Config.shuangpinSemicolon
        val existing = semicolonBtnRef?.get()
        if (!want) {
            existing?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
            semicolonBtnRef = null
            return
        }
        if (existing != null && existing.isAttachedToWindow) return
        val ime = imeRef?.get() ?: return
        val root = ime.window?.window?.decorView as? ViewGroup ?: return
        mainHandler.post {
            runCatching {
                val btn = android.widget.TextView(ime).apply {
                    text = ";"
                    textSize = 18f
                    setTextColor(0xFFE8EAED.toInt())
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xE0202124.toInt())
                        cornerRadius = 12f * ime.resources.displayMetrics.density
                    }
                    val dp = ime.resources.displayMetrics.density
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        (34 * dp).toInt(), (34 * dp).toInt(),
                        android.view.Gravity.BOTTOM or android.view.Gravity.START,
                    ).apply {
                        setMargins((10 * dp).toInt(), 0, 0, (150 * dp).toInt())
                    }
                    setOnClickListener {
                        val ic = currentIc()
                        ic?.sendKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_SEMICOLON,
                            )
                        )
                        ic?.sendKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_SEMICOLON,
                            )
                        )
                    }
                }
                root.addView(btn)
                semicolonBtnRef = WeakReference(btn)
            }
        }
    }

    @Volatile
    private var lastIc: android.view.inputmethod.InputConnection? = null

    private fun currentIc(): android.view.inputmethod.InputConnection? = lastIc

    // ---------- 灵活 emoji 联想（轻量词库版） ----------
    private fun updateEmojiRow() {
        val want = visible && !password && pinyinActive &&
            Config.emojiSuggestEnabled && composingRaw.isNotEmpty()
        if (!want) {
            emojiRowRef?.get()?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
            emojiRowRef = null
            return
        }
        val matches = EmojiLexicon.lookup(composingRaw)
        if (matches.isEmpty()) {
            emojiRowRef?.get()?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) } }
            emojiRowRef = null
            return
        }
        val ime = imeRef?.get() ?: return
        val root = ime.window?.window?.decorView as? ViewGroup ?: return
        mainHandler.post {
            runCatching {
                (emojiRowRef?.get())?.let { old ->
                    runCatching { (old.parent as? ViewGroup)?.removeView(old) }
                }
                val dp = ime.resources.displayMetrics.density
                val row = android.widget.LinearLayout(ime).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xF0202124.toInt())
                        cornerRadius = 14f * dp
                    }
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    for (emoji in matches.take(6)) {
                        addView(
                            android.widget.TextView(ime).apply {
                                text = emoji
                                textSize = 24f
                                setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
                                setOnClickListener {
                                    lastIc?.commitText(emoji, 1)
                                }
                            }
                        )
                    }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = (240 * dp).toInt()
                    }
                }
                root.addView(row)
                emojiRowRef = WeakReference(row)
            }
        }
    }
}

/**
 * 内置拼音→emoji 轻量词库（前缀匹配）。
 */
private object EmojiLexicon {
    private val MAP = linkedMapOf(
        "nihao" to listOf("👋", "😊"), "hai" to listOf("🙋"), "xieie" to listOf("🙏"),
        "xiexie" to listOf("🙏"), "buyao" to listOf("🙅"), "hao" to listOf("👍"),
        "zaoshanghao" to listOf("🌅"), "wanan" to listOf("🌙"), "ai" to listOf("❤️"),
        "aini" to listOf("❤️"), "xiangni" to listOf("🥰"), "haha" to listOf("😂"),
        "xixi" to listOf("🤭"), "wuliao" to listOf("😐"), "leile" to listOf("😴"),
        "shuijiao" to listOf("😴"), "chi" to listOf("🍚"), "chifan" to listOf("🍜"),
        "hecha" to listOf("🍵"), "kafei" to listOf("☕"), "pijiu" to listOf("🍺"),
        "danao" to listOf("🧠"), "gongzuo" to listOf("💼"), "kaishi" to listOf("🚀"),
        "wancheng" to listOf("✅"), "wenti" to listOf("❓"), "ok" to listOf("👌"),
        "hao de" to listOf("👌"), "buxing" to listOf("🚫"), "zhuyi" to listOf("⚠️"),
        "weixian" to listOf("☠️"), "kuaile" to listOf("😄"), "kaixin" to listOf("😄"),
        "shengqi" to listOf("😡"), "kuming" to listOf("😢"), "lihai" to listOf("🔥"),
        "zuiqiang" to listOf("💪"), "jiayou" to listOf("💪"), "dianhua" to listOf("📞"),
        "shouji" to listOf("📱"), "diannao" to listOf("💻"), "youxi" to listOf("🎮"),
        "yinyue" to listOf("🎵"), "dianying" to listOf("🎬"), "lvxing" to listOf("✈️"),
        "qiche" to listOf("🚗"), "huoche" to listOf("🚄"), "tianqi" to listOf("🌤"),
        "xiayu" to listOf("🌧"), "xiaxue" to listOf("❄️"), "taiyang" to listOf("☀️"),
        "yueliang" to listOf("🌙"), "xingxing" to listOf("⭐"), "hua" to listOf("🌸"),
        "gou" to listOf("🐶"), "mao" to listOf("🐱"), "xiong" to listOf("🐻"),
        "laoshu" to listOf("🐭"), "tuzi" to listOf("🐰"), "yu" to listOf("🐟"),
        "niao" to listOf("🐦"), "pingguo" to listOf("🍎"), "xigua" to listOf("🍉"),
        "caomei" to listOf("🍓"), "dangao" to listOf("🎂"), "tang" to listOf("🍬"),
        "shengri" to listOf("🎂"), "zhufu" to listOf("🎉"), "xinqing" to listOf("🫶"),
    )

    fun lookup(composing: String): List<String> {
        val key = composing.lowercase().replace("'", "").replace("’", "").trim()
        if (key.isEmpty()) return emptyList()
        MAP[key]?.let { return it }
        // 前缀匹配（拼音未打完）
        return MAP.entries.filter { it.key.startsWith(key) && it.key != key }
            .take(3).map { it.value.firstOrNull() ?: "" }.filter { it.isNotEmpty() && !it.first().isLetter() }
    }
}
