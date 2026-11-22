package com.switchboard.gboard.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.switchboard.gboard.Config
import com.switchboard.gboard.util.MLog
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RGB 灯效（移植自 m4mental/gboard-RGB-studio，按功能精简重写）：
 * 9 种特效（波纹/水滴/霓虹闪电/超新星/熔岩/声波/黑洞/雨滴/雷蛇彩虹）+
 * 键帽流光（可选仅描边）+ 滑行霓虹轨迹 + WPM 过热 + perimeter 底光。
 * 全部 Canvas overlay，不吞触摸事件，各自独立开关。
 */
object RgbHook {
    const val GROUP = "Rgb"

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlayRef: WeakReference<RgbOverlayView>? = null

    fun install(module: XposedModule, cl: ClassLoader) {
        val setInputView = runCatching {
            android.inputmethodservice.InputMethodService::class.java.getDeclaredMethod(
                "setInputView", View::class.java,
            )
        }.getOrNull()
        if (setInputView != null) {
            module.hook(setInputView).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? View)?.let { ime ->
                    main.post {
                        runCatching { mountOverlay(ime) }
                            .onFailure { MLog.e(GROUP, "mount failed", it) }
                    }
                }
                result
            }
        }
        val dispatch = runCatching {
            ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull() ?: return
        module.hook(dispatch).intercept { chain ->
            val result = chain.proceed()
            val ev = chain.getArg(0) as? MotionEvent
            val overlay = overlayRef?.get()
            if (ev != null && overlay != null &&
                (chain.thisObject as? View)?.context?.packageName == Config.GBOARD_PACKAGE
            ) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val idx = ev.actionIndex
                        overlay.onTouchDown(ev.getX(idx), ev.getY(idx))
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Config.rgbTrailEnabled) {
                            val idx = if (ev.pointerCount > 0) 0 else 0
                            overlay.onTouchMove(ev.getX(idx), ev.getY(idx))
                        }
                    }
                }
            }
            result
        }
        MLog.i(GROUP, "installed")
    }

    private fun mountOverlay(ime: View) {
        val root = ime.rootView as? ViewGroup ?: return
        if (root.findViewWithTag<View>("switchboard_rgb_overlay") != null) return
        val overlay = RgbOverlayView(ime.context)
        overlay.tag = "switchboard_rgb_overlay"
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ),
        )
        overlay.attachRoot(root)
        overlayRef = WeakReference(overlay)
        MLog.i(GROUP, "overlay mounted")
    }
}

/**
 * 特效渲染 overlay。
 */
class RgbOverlayView(context: android.content.Context) : View(context) {

    // ---------- 状态 ----------
    private class Ripple(var x: Float, var y: Float, var radius: Float, var alpha: Int)
    private class TrailPoint(var x: Float, var y: Float, var life: Int)
    private class RainDrop(var x: Float, var y: Float, var speed: Float, var len: Float)
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Int)

    private val ripples = CopyOnWriteArrayList<Ripple>()
    private val trail = CopyOnWriteArrayList<TrailPoint>()
    private val rain = CopyOnWriteArrayList<RainDrop>()
    private val particles = CopyOnWriteArrayList<Particle>()
    private val keycaps = CopyOnWriteArrayList<android.graphics.RectF>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var hue = 0f
    private var animating = false
    private var phase = 0f

    // WPM 过热：点击间隔滚动窗口
    private val tapTimes = ArrayDeque<Long>()
    @Volatile
    private var turbo = 1f

    private var rootRef: WeakReference<ViewGroup>? = null
    private var lastKeyScan = 0L

    fun attachRoot(root: ViewGroup) {
        rootRef = WeakReference(root)
        scanKeycaps(root)
    }

    init {
        setOnTouchListener { _, _ -> false }
        isClickable = false
        isFocusable = false
    }

    fun onTouchDown(x: Float, y: Float) {
        main.post {
            recordTap()
            val boost = if (Config.rgbTurboEnabled) turbo else 1f
            ripples.add(Ripple(x, y, 0f, 160))
            if (Config.rgbEffect == "supernova" || Config.rgbEffect == "blackhole") {
                spawnBurst(x, y, Config.rgbEffect == "supernova", boost)
            }
            scheduleAnim()
        }
    }

    fun onTouchMove(x: Float, y: Float) {
        main.post {
            trail.add(TrailPoint(x, y, 22))
            if (trail.size > 40) trail.removeFirst()
            scheduleAnim()
        }
    }

    private fun recordTap() {
        val now = System.currentTimeMillis()
        tapTimes.addLast(now)
        while (tapTimes.size > 6) tapTimes.removeFirst()
        if (tapTimes.size >= 3) {
            val gaps = tapTimes.zipWithNext { a, b -> b - a }
            val avg = gaps.average()
            turbo = when {
                avg < 150 -> 1.65f
                avg < 220 -> 1.35f
                avg < 300 -> 1.15f
                else -> 1f
            }
        }
    }

    private fun spawnBurst(x: Float, y: Float, outward: Boolean, boost: Float) {
        val n = 18
        for (i in 0 until n) {
            val angle = Math.PI * 2 * i / n + Math.random() * 0.3
            val speed = (2.5 + Math.random() * 3) * boost
            particles.add(
                Particle(
                    x, y,
                    (Math.cos(angle) * speed * (if (outward) 1 else -1)).toFloat(),
                    (Math.sin(angle) * speed * (if (outward) 1 else -1)).toFloat(),
                    40,
                )
            )
        }
    }

    private fun colorPrimary(): Int =
        runCatching { Color.parseColor(Config.rgbColorPrimary) }.getOrDefault(0x00FFF5)

    private fun colorSecondary(): Int =
        runCatching { Color.parseColor(Config.rgbColorSecondary) }.getOrDefault(0xFF00AA)

    private fun speedFactor(): Float = (Config.rgbWaveSpeed / 100f).coerceIn(0.3f, 2f)

    private fun scheduleAnim() {
        if (animating) return
        animating = true
        main.post(object : Runnable {
            override fun run() {
                var alive = false
                val sp = speedFactor()
                hue = (hue + 2f * sp) % 360f
                phase += 0.05f * sp
                val it = ripples.iterator()
                while (it.hasNext()) {
                    val r = it.next()
                    r.radius += 9f * sp
                    r.alpha -= (6 / sp).toInt().coerceAtLeast(2)
                    if (r.alpha <= 0) it.remove() else alive = true
                }
                val tit = trail.iterator()
                while (tit.hasNext()) {
                    val t = tit.next()
                    t.life -= 2
                    if (t.life <= 0) tit.remove() else alive = true
                }
                val pit = particles.iterator()
                while (pit.hasNext()) {
                    val p = pit.next()
                    p.x += p.vx * sp
                    p.y += p.vy * sp
                    p.life -= 2
                    if (p.life <= 0) pit.remove() else alive = true
                }
                if (Config.rgbEffect == "rain" || Config.rgbEffect == "magma") {
                    maintainAmbient()
                    if (rain.isNotEmpty() || particles.isNotEmpty()) alive = true
                }
                if (ripples.isNotEmpty() || trail.isNotEmpty() ||
                    Config.bool("rgb_glow_enabled", true) || Config.rgbKeyFlowEnabled
                ) alive = true
                if (alive) {
                    invalidate()
                    main.postDelayed(this, 16)
                } else {
                    animating = false
                }
            }
        })
    }

    /** 雨滴/熔岩环境粒子 */
    private fun maintainAmbient() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0) return
        if (Config.rgbEffect == "rain" && rain.size < 40) {
            repeat(3) {
                rain.add(RainDrop(Math.random().toFloat() * w, -20f, 8f + Math.random().toFloat() * 8f, 20f + Math.random().toFloat() * 30f))
            }
        }
        if (Config.rgbEffect == "magma" && particles.size < 30) {
            repeat(2) {
                particles.add(
                    Particle(
                        Math.random().toFloat() * w, h, (Math.random().toFloat() - 0.5f) * 1.2f,
                        -(1.5f + Math.random().toFloat() * 2f), 60,
                    )
                )
            }
        }
        val rit = rain.iterator()
        while (rit.hasNext()) {
            val r = rit.next()
            r.y += r.speed * speedFactor()
            if (r.y > h) rit.remove()
        }
    }

    // ---------- 键帽发现 ----------
    private fun scanKeycaps(root: ViewGroup) {
        val now = System.currentTimeMillis()
        if (now - lastKeyScan < 1000) return
        lastKeyScan = now
        keycaps.clear()
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val self = IntArray(2)
        getLocationOnScreen(self)
        collectKeys(root, loc, self)
    }

    private fun collectKeys(v: View, rootLoc: IntArray, selfLoc: IntArray) {
        if (v.javaClass.name.endsWith("SoftKeyView") && v.isShown && v.width > 0) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            keycaps.add(
                android.graphics.RectF(
                    (loc[0] - selfLoc[0]).toFloat(), (loc[1] - selfLoc[1]).toFloat(),
                    (loc[0] - selfLoc[0] + v.width).toFloat(), (loc[1] - selfLoc[1] + v.height).toFloat(),
                )
            )
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectKeys(v.getChildAt(i), rootLoc, selfLoc)
    }

    // ---------- 绘制 ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scanKeycapsRoot()
        val w = width.toFloat()
        val h = height.toFloat()
        val c1 = colorPrimary()
        val c2 = colorSecondary()

        // perimeter 底光
        if (Config.bool("rgb_glow_enabled", true)) {
            val glowColor = android.graphics.Color.HSVToColor(0x55, floatArrayOf(hue, 0.6f, 1f))
            glow.shader = RadialGradient(
                w / 2f, h, w / 1.5f, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.55f, w, h, glow)
        }

        // 键帽流光
        if (Config.rgbKeyFlowEnabled && keycaps.isNotEmpty()) {
            drawKeyFlow(canvas, c1, c2)
        }

        when (Config.rgbEffect) {
            "water" -> drawWater(canvas, c1)
            "neon" -> drawNeon(canvas, c1, c2)
            "supernova" -> drawParticles(canvas, c1, trails = true)
            "magma" -> drawMagma(canvas)
            "sonic" -> drawSonic(canvas, c1)
            "blackhole" -> drawParticles(canvas, c2, trails = false)
            "rain" -> drawRain(canvas, c1)
            "chroma" -> drawChroma(canvas)
            else -> drawRipples(canvas, c1)
        }
    }

    private fun scanKeycapsRoot() {
        rootRef?.get()?.let { scanKeycaps(it) }
    }

    private fun drawRipples(canvas: Canvas, c1: Int) {
        for (r in ripples) {
            paint.shader = RadialGradient(
                r.x, r.y, r.radius.coerceAtLeast(1f),
                (r.alpha shl 24) or (c1 and 0xFFFFFF), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(r.x, r.y, r.radius, paint)
        }
    }

    private fun drawWater(canvas: Canvas, c1: Int) {
        for (r in ripples) {
            for (ring in 0 until 3) {
                val rr = r.radius - ring * 18f
                if (rr <= 0) continue
                paint.shader = RadialGradient(
                    r.x, r.y + ring * 6f, rr,
                    ((r.alpha / (ring + 1)) shl 24) or (c1 and 0xFFFFFF),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(r.x, r.y + ring * 6f, rr, paint)
            }
        }
    }

    private fun drawNeon(canvas: Canvas, c1: Int, c2: Int) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        for (r in ripples) {
            paint.strokeWidth = 3f
            paint.color = (r.alpha shl 24) or (c1 and 0xFFFFFF)
            val seg = 8
            var px = r.x
            var py = r.y
            for (i in 1..seg) {
                val angle = Math.PI * 2 * i / seg + phase
                val nx = r.x + Math.cos(angle) * r.radius
                val ny = r.y + Math.sin(angle) * r.radius * 0.6
                canvas.drawLine(px, py, nx.toFloat(), ny.toFloat(), paint)
                px = nx.toFloat(); py = ny.toFloat()
            }
            paint.color = ((r.alpha / 2) shl 24) or (c2 and 0xFFFFFF)
            canvas.drawCircle(r.x, r.y, r.radius * 0.35f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawParticles(canvas: Canvas, color: Int, trails: Boolean) {
        paint.shader = null
        for (p in particles) {
            val a = (p.life * 255 / 40).coerceIn(0, 255)
            paint.color = (a shl 24) or (color and 0xFFFFFF)
            if (trails) {
                canvas.drawLine(p.x, p.y, p.x - p.vx * 4, p.y - p.vy * 4, paint)
            } else {
                canvas.drawCircle(p.x, p.y, 3f, paint)
            }
        }
        // 中心核
        val r = ripples.lastOrNull()
        if (r != null) {
            paint.shader = RadialGradient(
                r.x, r.y, r.radius.coerceAtLeast(1f),
                (r.alpha shl 24) or (color and 0xFFFFFF), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(r.x, r.y, r.radius * 0.5f, paint)
        }
    }

    private fun drawMagma(canvas: Canvas) {
        paint.shader = null
        val hot = android.graphics.Color.HSVToColor(floatArrayOf((hue + 20f) % 360f, 0.9f, 1f))
        for (p in particles) {
            val a = (p.life * 255 / 60).coerceIn(0, 255)
            paint.color = (a shl 24) or (hot and 0xFFFFFF)
            canvas.drawCircle(p.x, p.y, 2.5f + (60 - p.life) / 20f, paint)
        }
        // 底部熔岩渐层
        val h = height.toFloat()
        glow.shader = RadialGradient(
            width / 2f, h * 1.3f, width / 1.2f,
            (0x66 shl 24) or (hot and 0xFFFFFF), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.7f, width.toFloat(), h, glow)
    }

    private fun drawSonic(canvas: Canvas, c1: Int) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        val r = ripples.lastOrNull()
        if (r != null) {
            for (ring in 0 until 5) {
                val rr = r.radius - ring * 30f * speedFactor()
                if (rr <= 0) continue
                val a = (r.alpha / (ring + 1)).coerceIn(0, 255)
                paint.color = (a shl 24) or (c1 and 0xFFFFFF)
                canvas.drawCircle(r.x, r.y, rr, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawRain(canvas: Canvas, c1: Int) {
        paint.shader = null
        paint.strokeWidth = 1.8f
        paint.color = (0x99 shl 24) or (c1 and 0xFFFFFF)
        for (r in rain) {
            canvas.drawLine(r.x, r.y, r.x - 2f, r.y + r.len, paint)
        }
    }

    private fun drawChroma(canvas: Canvas) {
        paint.shader = null
        val band = 14f
        var y = height * 0.5f
        while (y < height) {
            paint.color = android.graphics.Color.HSVToColor(0x40, floatArrayOf((hue + y / 8f) % 360f, 0.8f, 1f))
            canvas.drawRect(0f, y, width.toFloat(), y + band, paint)
            y += band
        }
    }

    /** 滑行轨迹（霓虹激光尾迹） */
    private fun drawTrail(canvas: Canvas, c1: Int) {
        if (trail.isEmpty()) return
        paint.shader = null
        paint.style = Paint.Style.STROKE
        for (i in 1 until trail.size) {
            val a = trail[i - 1]
            val b = trail[i]
            val alpha = (b.life * 255 / 22).coerceIn(0, 255)
            paint.strokeWidth = 2f + b.life / 4f
            paint.color = (alpha shl 24) or (c1 and 0xFFFFFF)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** 键帽流光：渐变带扫过键帽矩形（可选只描边） */
    private fun drawKeyFlow(canvas: Canvas, c1: Int, c2: Int) {
        val w = width.toFloat()
        val borderOnly = Config.rgbKeyBorderOnly
        val bandPos = (phase * 400f * (Config.rgbKeyFlowSpeed / 100f).coerceIn(0.2f, 3f)) % (w + 400f) - 200f
        keyPaint.shader = null
        for (k in keycaps) {
            val dist = Math.abs(k.centerX() - bandPos)
            if (dist > 160f) continue
            val intensity = (1f - dist / 160f)
            val color = android.graphics.Color.HSVToColor(
                (intensity * 150).toInt(), floatArrayOf(hue, 0.7f, 1f)
            )
            if (borderOnly) {
                keyPaint.style = Paint.Style.STROKE
                keyPaint.strokeWidth = 2.5f
                keyPaint.color = color
                canvas.drawRoundRect(k, 18f, 18f, keyPaint)
            } else {
                keyPaint.style = Paint.Style.FILL
                keyPaint.color = (color and 0xFFFFFF) or ((intensity * 70).toInt() shl 24)
                canvas.drawRoundRect(k, 18f, 18f, keyPaint)
            }
        }
        keyPaint.style = Paint.Style.FILL
        // 渐变双色调带
        paint.shader = RadialGradient(
            bandPos, height * 0.75f, 220f,
            (0x30 shl 24) or (c1 and 0xFFFFFF),
            Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRect(bandPos - 220f, 0f, bandPos + 220f, height.toFloat(), paint)
        // 滑行轨迹叠加
        if (Config.rgbTrailEnabled) drawTrail(canvas, c2)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ripples.clear(); trail.clear(); rain.clear(); particles.clear(); keycaps.clear()
    }
}
