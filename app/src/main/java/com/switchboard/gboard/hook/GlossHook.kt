package com.switchboard.gboard.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.switchboard.gboard.Config
import com.switchboard.gboard.SwitchboardModule
import com.switchboard.gboard.hook.gloss.Glossary
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.zip.ZipFile

/**
 * 候选词译词（移植自 ziyuw-Plank/gboard-gloss，LGPL-2.1）。
 *
 * 在候选词 SoftKeyView 底部叠加一行目标语言释义（纯绘制，不改选词/上屏）：
 * - 候选词获取：hook SoftKeyView 的 SoftKeyDef 绑定方法（按参数类型签名匹配，抗混淆）
 * - 离线词典：assets/dicts 下的 tsv.gz（含 zh↔en/ja/de/fr/es/ru），字词精确 + 分词组合
 * - 纯本地：不联网、无 ML Kit/在线翻译依赖（外部引擎功能已按要求移除）
 */
object GlossHook {
    const val GROUP = "Gloss"

    private const val SOFTKEY = "com.google.android.libraries.inputmethod.widgets.SoftKeyView"
    private const val SOFTKEY_DEF = "com.google.android.libraries.inputmethod.metadata.SoftKeyDef"

    private val state = WeakHashMap<View, GlossState>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var paintInit = false
    private var lastNight = false
    private var lastDensity = -1f
    private var openerReady = false
    private var zip: ZipFile? = null

    class GlossState {
        var word: String? = null
        var gloss: String? = null
        var isCandidate: Boolean? = null
    }

    private val opener = object : Glossary.Opener {
        @Throws(FileNotFoundException::class)
        override fun open(name: String): InputStream {
            val z = zip ?: SwitchboardModule.moduleApkPath?.let { ZipFile(it) }?.also { zip = it }
            val entry = z?.getEntry("assets/$name") ?: throw FileNotFoundException(name)
            return z.getInputStream(entry)
        }
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        // 词典预热（首候选窗口到来前完成）
        Thread {
            runCatching {
                Glossary.loadGlossary(opener, Config.str("user_glossary", ""))
                openerReady = Glossary.glossaryBuiltin > 0
                MLog.i(
                    GROUP,
                    "dict ready builtin=${Glossary.glossaryBuiltin} user=${Glossary.glossaryUser}"
                )
            }.onFailure { MLog.e(GROUP, "dict load failed", it) }
        }.start()

        val softKeyCls = runCatching { cl.loadClass(SOFTKEY) }.getOrNull() ?: run {
            MLog.w(GROUP, "$SOFTKEY not found")
            return
        }
        val defCls = runCatching { cl.loadClass(SOFTKEY_DEF) }.getOrNull()
        if (defCls != null) {
            val n = hookSoftKeyBindMethods(module, softKeyCls, defCls)
            MLog.i(GROUP, "SoftKeyView bind hooks installed=$n")
        }

        // 绘制兜底：dispatchDraw after（仅 SoftKeyView 实例），补 bind hook 未覆盖的键
        val dispatchDraw = runCatching {
            ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        }.getOrNull() ?: return
        module.hook(dispatchDraw).intercept { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self != null && softKeyCls.isInstance(self)) {
                val v = self as View
                val st = state[v]
                if (st == null || st.word == null) {
                    // bind hook 未命中：直接从 SoftKeyDef 字段读
                    if (st == null && defCls != null) {
                        val f = findSoftKeyDefField(softKeyCls)
                        if (f != null) {
                            val def = runCatching { f.get(v) }.getOrNull()
                            if (def != null) onSoftKeyBound(module, v, def, softKeyCls)
                        }
                    }
                }
                val s = state[v]
                if (s?.gloss != null && (chain.getArg(0) as Canvas) != null) {
                    drawCachedGloss(v, chain.getArg(0) as Canvas)
                }
            }
            result
        }
        MLog.i(GROUP, "dispatchDraw hook installed")
    }

    private var softKeyDefField: Field? = null

    private fun findSoftKeyDefField(softKey: Class<*>): Field? {
        softKeyDefField?.let { return it }
        for (f in softKey.declaredFields) {
            if (SOFTKEY_DEF == f.type.name) {
                f.isAccessible = true
                softKeyDefField = f
                return f
            }
        }
        return null
    }

    /** Hook SoftKeyView 的 SoftKeyDef 绑定方法（18.4=r、18.5=s，签名匹配不依赖名字） */
    private fun hookSoftKeyBindMethods(
        module: XposedModule,
        softKeyCls: Class<*>,
        defCls: Class<*>,
    ): Int {
        var n = 0
        for (m in softKeyCls.declaredMethods) {
            val pt = m.parameterTypes
            if (pt.isEmpty() || pt.size > 2) continue
            if (!defCls.isAssignableFrom(pt[0])) continue
            if (pt.size == 2 && pt[1] != java.lang.Long.TYPE && pt[1] != java.lang.Long::class.java) continue
            if (m.modifiers and Modifier.STATIC != 0) continue
            m.isAccessible = true
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                val def = chain.args.firstOrNull { it != null && defCls.isInstance(it) }
                (chain.thisObject as? View)?.let {
                    runCatching { onSoftKeyBound(module, it, def, softKeyCls) }
                }
                result
            }
            n++
        }
        return n
    }

    private fun findLabelField(defCls: Class<*>): Field? {
        var listField: Field? = null
        for (f in defCls.declaredFields) {
            val t = f.type
            if (t == Array<CharSequence>::class.java || t == Array<String>::class.java) {
                f.isAccessible = true
                return f
            }
            if (!t.isPrimitive && !t.name.startsWith("java.lang.") &&
                !t.name.startsWith("android.") && !t.isArray &&
                (t.name.contains("wns") || hasGet0(t))
            ) {
                if (listField == null && f.name != "h" && f.name != "k") listField = f
                if (f.name == "i") {
                    f.isAccessible = true
                    return f
                }
            }
        }
        listField?.isAccessible = true
        return listField
    }

    private fun hasGet0(t: Class<*>): Boolean = try {
        val m = t.getMethod("get", Int::class.javaPrimitiveType)
        val r = m.returnType
        r == Any::class.java || CharSequence::class.java.isAssignableFrom(r) || r == String::class.java
    } catch (ignored: Throwable) {
        false
    }

    private var softKeyDefLabels: Field? = null

    private fun extractLabel(softKey: View, softKeyDef: Any?): String {
        runCatching {
            if (softKeyDef != null) {
                val f = softKeyDefLabels ?: findLabelField(softKeyDef.javaClass).also {
                    softKeyDefLabels = it
                }
                if (f != null) {
                    val labels = f.get(softKeyDef)
                    val s = firstLabel(labels)
                    if (!s.isNullOrEmpty()) return s
                }
            }
        }
        if (softKey is ViewGroup) {
            val sb = StringBuilder()
            collectText(softKey, sb)
            if (sb.isNotEmpty()) return sb.toString().trim()
        }
        return softKey.contentDescription?.toString()?.trim() ?: ""
    }

    private fun firstLabel(labels: Any?): String? {
        if (labels == null) return null
        if (labels is Array<*>) {
            if (labels.isNotEmpty() && labels[0] != null) {
                val s = labels[0].toString().trim()
                return s.ifEmpty { null }
            }
            return null
        }
        runCatching {
            val isEmpty = labels.javaClass.getMethod("isEmpty").invoke(labels)
            if (isEmpty is Boolean && isEmpty) return null
        }
        return runCatching {
            val first = labels.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                .invoke(labels, 0)
            first?.toString()?.trim()?.ifEmpty { null }
        }.getOrNull()
    }

    private fun collectText(v: View, sb: StringBuilder) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView) {
            val t = v.text
            if (!t.isNullOrEmpty() && sb.isEmpty()) sb.append(t)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectText(v.getChildAt(i), sb)
        }
    }

    /** 行内最左可见 SoftKeyView = Gboard 首候选 */
    private fun isFirstCandidate(v: View): Boolean {
        val p = v.parent as? ViewGroup ?: return false
        for (i in 0 until p.childCount) {
            val c = p.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (!c.javaClass.name.endsWith("SoftKeyView")) continue
            return c == v
        }
        return false
    }

    private fun resolveIsCandidate(v: View): Boolean {
        state[v]?.isCandidate?.let { return it }
        var yes = false
        var p = v.parent
        while (p is View) {
            val n = p.javaClass.name
            if (n.startsWith("android.") || n.startsWith("androidx.")) break
            if (n.contains("Candidate") || n.contains("Suggestion") ||
                n.contains("candidate")
            ) {
                yes = true
                break
            }
            if (n.contains("SoftKeyboardView")) break
            p = p.parent
        }
        state.getOrPut(v) { GlossState() }.isCandidate = yes
        return yes
    }

    private fun onSoftKeyBound(module: XposedModule, v: View, softKeyDef: Any?, softKeyCls: Class<*>) {
        val st = state.getOrPut(v) { GlossState() }
        if (!Config.glossEnabled) {
            st.gloss = null; st.word = null
            return
        }
        if (!resolveIsCandidate(v)) {
            st.gloss = null
            return
        }
        val word = extractLabel(v, softKeyDef)
        if (word.isEmpty() || word.length > 24) {
            st.gloss = null
            return
        }
        if (word == st.word) return
        st.word = word

        // 纯离线词典管线（不联网、不依赖 Play services）
        val lang = Config.glossTargetLang
        val gloss = Glossary.translate(opener, word, lang)
        st.gloss = Glossary.fitGlossToken(gloss, 22)

        showBannerOnce(v)
        runCatching { v.invalidate() }
    }

    private fun clearGloss(v: View) {
        state[v]?.let {
            if (it.gloss != null || it.word != null) {
                it.word = null
                it.gloss = null
                v.invalidate()
            }
        }
    }

    /** 60% alpha 灰色次级标签画在键底部 */
    private fun drawCachedGloss(v: View, c: Canvas) {
        val st = state[v] ?: return
        val gloss = st.gloss ?: return
        if (gloss.isEmpty()) return

        val d = v.resources.displayMetrics.density
        val sd = v.resources.displayMetrics.scaledDensity
        val h = v.height
        if (h < 22 * d) return

        var size = Config.glossSize
        if (size < 8) size = 8
        if (size > 14) size = 14
        var textPx = size * sd * 0.92f
        if (h < 32 * d) textPx = minOf(textPx, 9f * sd)

        val night = (v.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!paintInit || night != lastNight || d != lastDensity) {
            paintInit = true
            lastNight = night
            lastDensity = d
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            paint.letterSpacing = 0.04f
            paint.isFakeBoldText = false
            paint.color = if (night) 0x99E8EAED.toInt() else 0x99607078.toInt()
        }
        paint.textSize = textPx

        val maxW = maxOf(8f, v.width - 6f * d)
        var draw = gloss
        if (paint.measureText(draw) > maxW) {
            draw = fitToWidth(draw, paint, maxW) ?: return
            if (draw.isEmpty()) return
        }
        val x = v.width / 2f
        var y = h - paint.descent() - 1.5f * d
        val top = y + paint.ascent()
        if (top < h * 0.42f) {
            paint.textSize = textPx * 0.85f
            y = h - paint.descent() - 1.2f * d
            if (y + paint.ascent() < h * 0.38f) return
        }
        c.drawText(draw, x, y, paint)
    }

    private fun fitToWidth(g: String, p: Paint, maxW: Float): String? {
        if (p.measureText(g) <= maxW) return g
        val parts = g.split(Regex(" (?=\\S)|(?<=·) "))
        if (parts.size == 1) {
            val saved = p.textSize
            var lo = p.textSize * 0.7f
            var hi = p.textSize
            for (i in 0 until 6) {
                val mid = (lo + hi) / 2f
                p.textSize = mid
                if (p.measureText(g) <= maxW) lo = mid else hi = mid
            }
            p.textSize = lo
            if (p.measureText(g) <= maxW) return g
            p.textSize = saved
            return null
        }
        val sb = StringBuilder()
        for ((i, part) in parts.withIndex()) {
            val trial = if (i == 0) parts[0] else "$sb ${parts[i]}"
            if (p.measureText("$trial…") > maxW) {
                var base = sb.toString().trim()
                if (base.isEmpty()) return null
                if (base.endsWith("·")) base = base.substring(0, base.length - 1).trim()
                return "$base…"
            }
            if (i == 0) sb.append(parts[0]) else sb.append(' ').append(parts[i])
        }
        return sb.toString()
    }

    // ---------- 注入成功横幅（移植自 gboard-gloss showBanner） ----------
    @Volatile
    private var bannerShown = false

    private fun showBannerOnce(keyView: View) {
        if (bannerShown || !Config.glossBanner) return
        bannerShown = true
        val decor = keyView.rootView as? ViewGroup ?: return
        val ctx = keyView.context
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val d = ctx.resources.displayMetrics.density
                val night = (ctx.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val drawable = object : android.graphics.drawable.Drawable() {
                    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
                    private val tx = Paint(Paint.ANTI_ALIAS_FLAG)
                    init {
                        bg.color = if (night) 0xE03C4043.toInt() else 0xE0E8F0FE.toInt()
                        tx.color = if (night) 0xFFE8EAED.toInt() else 0xFF174EA6.toInt()
                        tx.textSize = 13 * d
                        tx.textAlign = Paint.Align.CENTER
                    }

                    override fun draw(c: Canvas) {
                        val b = bounds
                        val s = "✓ Switchboard 译词 · ${Config.glossTargetLang}"
                        val w = tx.measureText(s) + 28 * d
                        val h = 28 * d
                        val cx = b.width() / 2f
                        val y = b.height() - h - 4 * d
                        c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, h / 2, h / 2, bg)
                        c.drawText(s, cx, y + h / 2 - (tx.ascent() + tx.descent()) / 2, tx)
                    }

                    override fun setAlpha(alpha: Int) {
                        bg.alpha = alpha * 224 / 255
                        tx.alpha = alpha
                        invalidateSelf()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

                    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
                    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                }
                drawable.setBounds(0, 0, decor.width, decor.height)
                decor.overlay.add(drawable)
                decor.invalidate()
                val anim = android.animation.ObjectAnimator.ofInt(drawable, "alpha", 255, 0)
                anim.startDelay = 4500
                anim.duration = 700
                anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        runCatching { decor.overlay.remove(drawable) }
                    }
                })
                anim.start()
            }.onFailure { MLog.w(GROUP, "banner failed: ${it.message}") }
        }
    }
}
