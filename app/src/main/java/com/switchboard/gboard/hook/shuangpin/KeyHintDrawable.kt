/*
 * Ported (simplified) from gboard-shuangpin-hints AdaptiveKeyAdapter.java.
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright 2026 Gboard Shuangpin Hints contributors
 */
package com.switchboard.gboard.hook.shuangpin

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 键帽元数据/标签提取与提示绘制。
 */
object KeyFace {
    const val KEY_CLASS = "com.google.android.libraries.inputmethod.widgets.SoftKeyView"

    /** 找 SoftKeyView 上的 SoftKeyDef 元数据字段（兼容新旧布局） */
    fun metadataField(type: Class<*>): Field? {
        for (field in type.declaredFields) {
            if (!Modifier.isStatic(field.modifiers) &&
                field.type.name.endsWith(".metadata.SoftKeyDef")
            ) {
                field.isAccessible = true
                return field
            }
        }
        for (field in type.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            for (member in field.type.declaredFields) {
                val t = member.type
                if (t.isArray && CharSequence::class.java.isAssignableFrom(t.componentType)) {
                    field.isAccessible = true
                    return field
                }
            }
        }
        for (field in type.declaredFields) {
            if (field.type.name.endsWith(".metadata.SoftKeyDef")) {
                field.isAccessible = true
                return field
            }
        }
        return null
    }

    /** 找可见主标签 TextView（取字号最大的字母标签） */
    fun textLabel(view: View): TextView? {
        if (view.visibility != View.VISIBLE) return null
        if (view is TextView && letter(view.text) != 0.toChar()) return view
        var best: TextView? = null
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val candidate = textLabel(view.getChildAt(i))
                if (candidate != null && (best == null || candidate.textSize > best.textSize)) {
                    best = candidate
                }
            }
        }
        return best
    }

    fun letter(value: CharSequence?): Char {
        if (value == null || value.length != 1) return 0.toChar()
        val c = value[0].lowercaseChar()
        return if ((c in 'a'..'z') || c == ';') c else 0.toChar()
    }

    /** 键位字母：优先元数据 labels[0]，回退可见标签，再回退 contentDescription */
    fun key(view: View, metadata: Field?): Char {
        if (metadata != null) {
            try {
                val def = metadata.get(view) ?: return 0.toChar()
                for (field in def.javaClass.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val t = field.type
                    if (t.isArray && CharSequence::class.java.isAssignableFrom(t.componentType)) {
                        field.isAccessible = true
                        val labels = field.get(def) as? Array<CharSequence>
                        if (labels != null && labels.isNotEmpty()) return letter(labels[0])
                    }
                    if (MutableList::class.java.isAssignableFrom(t)) {
                        field.isAccessible = true
                        val labels = field.get(def) as? List<*>
                        if (labels != null && labels.isNotEmpty() && labels[0] is CharSequence) {
                            return letter(labels[0] as CharSequence)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
        val label = textLabel(view)
        if (label != null) return letter(label.text)
        return letter(view.contentDescription)
    }

    /** 可见键面边界（简化版：取键框内缩 2dp，覆盖绝大多数主题） */
    fun faceBounds(key: View): RectF {
        val result = RectF(0f, 0f, key.width.toFloat(), key.height.toFloat())
        val inset = 2f * key.resources.displayMetrics.density
        result.inset(inset, inset)
        if (result.width() <= 0 || result.height() <= 0) result.setEmpty()
        return result
    }

    /** 键帽上的双拼提示覆盖层 */
    class HintDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val face = RectF()
        private var hint: HintEngine.KeyHint = HintEngine.KeyHint.EMPTY
        private var size = 0f

        fun update(key: View, label: HintEngine.KeyHint, sp: Int) {
            hint = label
            size = sp * key.resources.displayMetrics.scaledDensity
            paint.typeface = Typeface.DEFAULT
            val primary = textLabel(key)
            if (primary != null) {
                paint.color = primary.currentTextColor
            } else {
                val colors = key.context.obtainStyledAttributes(
                    intArrayOf(android.R.attr.textColorPrimary)
                )
                try {
                    val c: ColorStateList? = colors.getColorStateList(0)
                    paint.color = c?.defaultColor ?: Color.GRAY
                } finally {
                    colors.recycle()
                }
            }
            paint.alpha = 190
            face.set(faceBounds(key))
            setBounds(0, 0, key.width, key.height)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (hint.isEmpty || bounds.isEmpty || face.isEmpty) return
            drawRow(canvas, hint.top, face, -1)
            drawRow(canvas, hint.bottom, face, 1)
        }

        private fun drawRow(canvas: Canvas, text: String, row: RectF, position: Int) {
            if (text.isEmpty()) return
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = size
            paint.textScaleX = 1f
            val width = paint.measureText(text)
            val fm = paint.fontMetrics
            val height = fm.bottom - fm.top
            if (width <= 0 || height <= 0) return
            paint.textScaleX = minOf(1f, row.width() / width)
            val baseline = if (position < 0) row.top - fm.top else row.bottom - fm.bottom
            val saved = canvas.save()
            canvas.clipRect(row)
            canvas.drawText(text, row.centerX(), baseline, paint)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun setColorFilter(filter: ColorFilter?) {
            paint.colorFilter = filter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
