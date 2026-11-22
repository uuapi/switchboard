/*
 * Ported from gboard-shuangpin-hints HintEngine.java.
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright 2026 Gboard Shuangpin Hints contributors
 */
package com.switchboard.gboard.hook.shuangpin

import java.util.Locale

/**
 * 双拼提示纯策略：不修改 Gboard 的文本流，只计算键帽上要画的提示。
 */
object HintEngine {

    fun scheme(value: String?): String? {
        if (value == null) return null
        return when (value.lowercase(Locale.ROOT)) {
            "shuangpin_flypy", "xiaohe" -> "XIAOHE"
            "shuangpin_ziranma", "ziranma" -> "Ziranma"
            "shuangpin_ms", "ms" -> "MS"
            "shuangpin_abc", "abc" -> "ABC"
            "shuangpin_ziguang", "ziguang" -> "Ziguang"
            "shuangpin_jiajia", "pinyinjiajia" -> "PinyinJiaJia"
            else -> null
        }
    }

    /** 从 composing 文本推断当前等待的声母（只取最后一个有效键） */
    fun independentInitial(text: CharSequence?, scheme: String?): String {
        if (text == null || text.length > 4096) return ""
        var pending = ""
        for (i in 0 until text.length) {
            val key = text[i]
            if (isPreeditSpace(key) || key == '\'' || key == '’' || key == '·') continue
            if (key in 'A'..'Z') {
                pending = ""
                continue
            }
            if (key !in 'a'..'z') {
                pending = ""
                continue
            }
            if (pending.isNotEmpty() && canPair(scheme, pending, key)) {
                pending = ""
            } else {
                val special = SchemeData.initial(scheme, key)
                pending = special ?: key.toString()
            }
        }
        return pending
    }

    private fun canPair(scheme: String?, initial: String, key: Char): Boolean {
        val values = SchemeData.finals(scheme)[key] ?: return false
        if (initial == "a" || initial == "e" || initial == "o") {
            if (SchemeData.SYLLABLES.contains(initial + key)) return true
            for (value in values) {
                if (value.length > 2 && value.startsWith(initial) &&
                    SchemeData.SYLLABLES.contains(value)
                ) return true
            }
            return false
        }
        for (value in values) {
            var syllable = initial + value
            if (value.startsWith("v") && initial in "jqxy") {
                syllable = initial + value.replace('v', 'u')
            }
            if (SchemeData.SYLLABLES.contains(syllable)) return true
        }
        return false
    }

    // 注意：此处必须用转义序列而非原始字符（U+0085 会被词法器当作行终止符）
    private fun isPreeditSpace(c: Char): Boolean =
        Character.isWhitespace(c) || Character.isSpaceChar(c) ||
            c == '\u0085' || c == '\u200B' || c == '\u200E' ||
            c == '\u2060' || c == '\uFEFF'

    data class KeyHint(val top: String, val center: String, val bottom: String) {
        val isEmpty: Boolean get() = top.isEmpty() && center.isEmpty() && bottom.isEmpty()

        companion object {
            @JvmField
            val EMPTY = KeyHint("", "", "")
        }
    }

    private fun join(values: List<String>): String = values.joinToString("/")

    fun hint(scheme: String?, key: Char, initial: String?): KeyHint =
        hint(scheme, key, initial, false)

    /**
     * @param initial 当前等待的声母（动态提示），空串 = 空闲态
     * @param singleVowels 空闲态是否显示单韵母
     */
    fun hint(scheme: String?, key: Char, initial: String?, singleVowels: Boolean): KeyHint {
        val prefix = initial ?: ""
        if (prefix == "a" || prefix == "o" || prefix == "e") {
            return zeroInitialHint(scheme, key, prefix, singleVowels)
        }
        val values = SchemeData.finals(scheme)[key.lowercaseChar()] ?: return KeyHint.EMPTY
        val shown = ArrayList<String>()
        val singles = ArrayList<String>()
        for (value in values) {
            val single = value.length == 1 && value[0] in "aeoiuv"
            if (single && (!singleVowels || prefix.isEmpty())) continue
            var syllable = prefix + value
            if (value.startsWith("v") && prefix.isNotEmpty() && prefix[0] in "jqxy") {
                syllable = prefix + "u" + value.substring(1)
            }
            if (prefix.isEmpty() || SchemeData.SYLLABLES.contains(syllable)) {
                val display = when {
                    value == "ve" && values.contains("ue") -> "ue"
                    value.startsWith("v") && prefix.isNotEmpty() && prefix[0] in "jqxy" ->
                        value.replace('v', 'u')
                    else -> value.replace('v', 'ü')
                }
                val destination = if (single) singles else shown
                if (!destination.contains(display)) destination.add(display)
            }
        }
        val special = if (prefix.isEmpty()) SchemeData.initial(scheme, key) else null
        return KeyHint(special ?: "", join(singles), join(shown))
    }

    private fun zeroInitialHint(
        scheme: String?,
        rawKey: Char,
        prefix: String,
        singleVowels: Boolean,
    ): KeyHint {
        val key = rawKey.lowercaseChar()
        val shown = ArrayList<String>()
        val values = SchemeData.finals(scheme)[key]
        if (values != null) {
            for (value in values) {
                if (value.length > 2 && value.startsWith(prefix) &&
                    SchemeData.SYLLABLES.contains(value) && !shown.contains(value)
                ) shown.add(value)
            }
        }
        val next = key.toString()
        val center = if (singleVowels && key in 'a'..'z' &&
            SchemeData.SYLLABLES.contains(prefix + next)
        ) next else ""
        return KeyHint("", center, join(shown))
    }
}
