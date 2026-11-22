package com.switchboard.gboard.hook

import java.util.Locale

/**
 * 中文键盘判定（移植自 gboardtweak ImeState）：
 * 用键盘本地化名称（Keyboard#cz）判断当前是否中文。
 */
object ImeState {
    private val CHINESE_MARKERS = arrayOf(
        "中文", "简体", "简中", "繁体", "繁體", "繁中",
        "汉语", "漢語", "粵語", "粤语", "拼音", "注音",
        "笔画", "筆畫", "仓颉", "倉頡", "速成", "五笔", "五筆",
    )

    private val CHINESE_LATIN_MARKERS = arrayOf(
        "chinese", "pinyin", "cantonese", "zhuyin",
    )

    fun isChinese(): Boolean {
        val name = KeyboardStateTracker.activeName() ?: return false
        if (CHINESE_MARKERS.any { name.contains(it) }) return true
        val lower = name.lowercase(Locale.ROOT)
        return CHINESE_LATIN_MARKERS.any { lower.contains(it) }
    }
}
