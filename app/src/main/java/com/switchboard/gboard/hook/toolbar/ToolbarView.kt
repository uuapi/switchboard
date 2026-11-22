package com.switchboard.gboard.hook.toolbar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 键盘上方工具栏（移植自 Gboard+ ToolbarView 的文本功能子集）。
 * 按钮：剪贴板保险库 📋 / 粘贴 📥 / 全选 ✂️ / 光标 ◀ ▶ / 撤销 ↩ / 文本快捷 ⭐1 ⭐2
 */
class ToolbarView(context: Context) : LinearLayout(context) {

    companion object {
        const val TAG = "switchboard_kb_toolbar"
        private const val BG = 0xF0151515.toInt()
        private const val FG = 0xFFE0E0E0.toInt()
        private const val ACCENT = 0xFF8AB4F8.toInt()
    }

    var inputConnection: android.view.inputmethod.InputConnection? = null
    var undoManager: UndoManager? = null
    var vault: VaultDb? = null

    private val popup = PopupWindow(context)

    /** 选择模式：激活后 ◀▶ 以 SHIFT+方向键扩展选区 */
    @Volatile
    var selectMode = false
    private var selectBtn: TextView? = null

    init {
        tag = TAG
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = round(BG, 18f)
        elevation = dp(4).toFloat()

        btn("📋") { showVault() }
        btn("📥") { inputConnection?.performContextMenuAction(android.R.id.paste) }
        btn("✂️") { inputConnection?.performContextMenuAction(android.R.id.selectAll) }
        selectBtn = btn("⇱") { toggleSelectMode() }
        btn("◀") { moveCursor(-1) }
        btn("▶") { moveCursor(1) }
        btn("↩") { doUndo() }
        btn("⭐") { commitShortcut(1) }
        btn("⭐⭐") { commitShortcut(2) }
    }

    private fun toggleSelectMode() {
        selectMode = !selectMode
        selectBtn?.background = if (selectMode) round(0xFF8AB4F8.toInt(), 14f) else roundAccent()
        selectBtn?.setTextColor(if (selectMode) 0xFF101418.toInt() else FG)
        Toast.makeText(
            context,
            if (selectMode) "选择模式：◀▶ 扩展选区" else "选择模式关闭",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
        }

    private fun roundAccent(): GradientDrawable = round(0x338AB4F8, 14f)

    private fun btn(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(FG)
            gravity = Gravity.CENTER
            minWidth = dp(42)
            minHeight = dp(38)
            setPadding(dp(6), 0, dp(6), 0)
            background = roundAccent()
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        lp.setMargins(dp(3), 0, dp(3), 0)
        addView(tv, lp)
        return tv
    }

    private fun moveCursor(delta: Int) {
        val ic = inputConnection ?: return
        val code = if (delta < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT
        else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        val meta = if (selectMode) android.view.KeyEvent.META_SHIFT_ON else 0
        ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun doUndo() {
        val ic = inputConnection ?: return
        val undone = undoManager?.undo(ic)
        if (undone == null) {
            Toast.makeText(context, "Switchboard：没有可撤销的操作", Toast.LENGTH_SHORT).show()
        }
    }

    private fun commitShortcut(index: Int) {
        val text = com.switchboard.gboard.Config.str("toolbar_text_$index", "")
        if (text.isEmpty()) {
            Toast.makeText(context, "先在 Switchboard 设置中配置文本快捷 $index", Toast.LENGTH_SHORT).show()
            return
        }
        inputConnection?.commitText(text, 1)
    }

    // ---------- 保险库弹层 ----------
    private fun showVault() {
        val db = vault ?: return
        if (popup.isShowing) {
            popup.dismiss()
            return
        }
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            background = round(0xF5151515.toInt(), 14f)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val search = EditText(context).apply {
            hint = "搜索剪贴板历史"
            setTextColor(FG)
            setHintTextColor(0x80FFFFFF.toInt())
            textSize = 13f
            background = round(0xFF202124.toInt(), 10f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isSingleLine = true
        }
        panel.addView(search)

        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        val count = TextView(context).apply {
            textSize = 11f
            setTextColor(0x90FFFFFF.toInt())
            text = "Switchboard 保险库 · ${db.count()} 条"
        }
        header.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "🗑 清空"
            textSize = 12f
            setTextColor(ACCENT)
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener {
                db.clear()
                refreshList(panel, search.text.toString(), count)
                Toast.makeText(context, "已清空（置顶保留）", Toast.LENGTH_SHORT).show()
            }
        })
        panel.addView(header)

        // 图片库横排缩略图
        val images = db.listImages()
        if (images.isNotEmpty()) {
            val strip = HorizontalScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
                )
            }
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (img in images) {
                val iv = android.widget.ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    background = round(0xFF202124.toInt(), 8f)
                    clipToOutline = true
                    post {
                        runCatching {
                            val bmp = android.graphics.BitmapFactory.decodeFile(img.path)
                            if (bmp != null) setImageBitmap(bmp)
                        }
                    }
                    setOnClickListener {
                        copyImageToClipboardAndPaste(img.path)
                        popup.dismiss()
                    }
                    setOnLongClickListener {
                        db.deleteImage(img.id)
                        refreshList(panel, search.text.toString(), count)
                        true
                    }
                }
                row.addView(iv)
            }
            strip.addView(row)
            panel.addView(strip)
        }

        val listScroll = ScrollView(context)
        listScroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(230)
        )
        panel.addView(
            listScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ),
        )

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshList(panel, s?.toString() ?: "", count)
            }
        })

        popup.contentView = panel
        popup.width = ViewGroup.LayoutParams.MATCH_PARENT
        popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(null)
        popup.animationStyle = 0
        popup.showAtLocation(this, Gravity.BOTTOM, 0, height + dp(4))
        refreshList(panel, "", count)
    }

    private fun refreshList(panel: LinearLayout, query: String, countView: TextView) {
        val db = vault ?: return
        val listScroll = (panel.getChildAt(panel.childCount - 1) as? ScrollView) ?: return
        listScroll.removeAllViews()
        val items = db.list(query)
        countView.text = "Switchboard 保险库 · ${db.count()} 条"
        if (items.isEmpty()) {
            listScroll.addView(TextView(context).apply {
                text = if (query.isBlank()) "暂无记录（复制内容后自动入库）" else "无匹配结果"
                setTextColor(0x80FFFFFF.toInt())
                textSize = 13f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
            return
        }
        for (clip in items) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(0xFF1E1F22.toInt(), 10f)
                setPadding(dp(10), dp(8), dp(6), dp(8))
            }
            row.addView(TextView(context).apply {
                val prefix = if (clip.pinned) "📌 " else ""
                val preview = clip.text.replace("\n", "⏎")
                text = prefix + if (preview.length > 80) preview.take(80) + "…" else preview
                textSize = 13f
                setTextColor(FG)
                typeface = Typeface.DEFAULT
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = "📌"
                textSize = 14f
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    db.togglePin(clip.id)
                    refreshList(panel, query, countView)
                }
            })
            row.addView(TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(0xFFFF8A80.toInt())
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener {
                    db.delete(clip.id)
                    refreshList(panel, query, countView)
                }
            })
            row.setOnClickListener {
                inputConnection?.commitText(clip.text, 1)
                popup.dismiss()
            }
            row.setOnLongClickListener {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("switchboard", clip.text))
                    Toast.makeText(context, "已复制到系统剪贴板", Toast.LENGTH_SHORT).show()
                } catch (ignored: Throwable) {
                }
                true
            }
            listScroll.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) },
            )
        }
    }

    private fun TextView.maxHeight(@Suppress("UNUSED_PARAMETER") px: Int) {
        // 占位：列表高度已固定为 260dp
    }

    /** 图片上屏：先复制到系统剪贴板再触发粘贴（支持 commitContent 的编辑器可直接粘贴） */
    private fun copyImageToClipboardAndPaste(path: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", java.io.File(path)
            )
            cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Switchboard image", uri))
            inputConnection?.performContextMenuAction(android.R.id.paste)
        }.onFailure {
            Toast.makeText(context, "图片上屏失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
