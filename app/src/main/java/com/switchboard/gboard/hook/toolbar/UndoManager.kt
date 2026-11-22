package com.switchboard.gboard.hook.toolbar

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * 撤销管理（移植自 Gboard+ UndoManager 思路，环形缓冲简化版）。
 *
 * 记录两类操作：
 *  - COMMIT(text)：提交文本 → 撤销 = 删除该文本
 *  - DELETE(text)：删除的文本（before/after 一并记录）→ 撤销 = 重新插回
 */
class UndoManager {
    companion object {
        const val MAX_OPS = 20
        const val MAX_CHARS = 20000
        const val MAX_DELETE_SCAN = 200
    }

    sealed class Op {
        class Commit(val text: String) : Op()
        class Delete(val before: String, val after: String) : Op()
    }

    private val ops = ArrayDeque<Op>()

    @Synchronized
    fun pushCommit(text: String) {
        if (text.isEmpty()) return
        trim(text.length)
        ops.addLast(Op.Commit(text))
        overLimit()
    }

    @Synchronized
    fun pushDelete(before: String, after: String) {
        if (before.isEmpty() && after.isEmpty()) return
        trim(before.length + after.length)
        ops.addLast(Op.Delete(before, after))
        overLimit()
    }

    private fun trim(needed: Int) {
        if (needed > MAX_CHARS) {
            ops.clear()
            return
        }
        var size = ops.sumOf {
            when (it) {
                is Op.Commit -> it.text.length
                is Op.Delete -> it.before.length + it.after.length
            }
        }
        while (ops.isNotEmpty() && size + needed > MAX_CHARS) {
            size -= when (val o = ops.removeFirst()) {
                is Op.Commit -> o.text.length
                is Op.Delete -> o.before.length + o.after.length
            }
        }
    }

    private fun overLimit() {
        while (ops.size > MAX_OPS) ops.removeFirst()
    }

    @Synchronized
    fun clear() = ops.clear()

    @Synchronized
    fun isEmpty(): Boolean = ops.isEmpty()

    /** 执行撤销；返回被操作的描述文本（供 toast），无操作返回 null */
    @Synchronized
    fun undo(ic: InputConnection): String? {
        while (ops.isNotEmpty()) {
            return when (val op = ops.removeLast()) {
                is Op.Commit -> {
                    val len = op.text.length
                    // 提交的文本通常在光标前
                    ic.beginBatchEdit()
                    val before = ic.getTextBeforeCursor(len + 2, 0) ?: ""
                    if (before.endsWith(op.text)) {
                        ic.deleteSurroundingText(len, 0)
                    } else {
                        ic.deleteSurroundingText(0, 0)
                    }
                    ic.endBatchEdit()
                    op.text
                }
                is Op.Delete -> {
                    val focus = op.after.length
                    ic.beginBatchEdit()
                    if (op.after.isNotEmpty()) {
                        ic.setComposingText(op.before + op.after, focus)
                    } else {
                        ic.commitText(op.before, 1)
                    }
                    ic.endBatchEdit()
                    op.before.ifEmpty { op.after }
                }
            }
        }
        return null
    }

    /** InputConnection 包装：自动记录 commitText / deleteSurroundingText */
    class RecordingConnection(
        inner: InputConnection,
        private val undo: UndoManager,
    ) : InputConnectionWrapper(inner, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text != null) undo.pushCommit(text.toString())
            return super.commitText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            try {
                val before = if (beforeLength > 0) {
                    getTextBeforeCursor(beforeLength.coerceAtMost(MAX_DELETE_SCAN), 0)?.toString() ?: ""
                } else ""
                val after = if (afterLength > 0) {
                    getTextAfterCursor(afterLength.coerceAtMost(MAX_DELETE_SCAN), 0)?.toString() ?: ""
                } else ""
                if (before.length == beforeLength || after.length == afterLength) {
                    undo.pushDelete(before, after)
                }
            } catch (ignored: Throwable) {
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }
}
