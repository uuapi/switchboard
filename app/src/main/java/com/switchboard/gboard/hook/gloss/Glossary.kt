/*
 * Ported from gboard-gloss Dictionary.java.
 * SPDX-License-Identifier: LGPL-2.1-or-later (part of Switchboard gloss port)
 */
package com.switchboard.gboard.hook.gloss

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 离线词典 + 精确词表。展示策略：精确/词表/干净否定优先，
 * 拼接结果仅在高置信时显示；只有弱命中时返回 null（不显示）。
 */
object Glossary {
    interface Opener {
        @Throws(java.io.IOException::class)
        fun open(name: String): InputStream
    }

    const val LANGS = "en,zh,ja,de,fr,es,ru"

    class D {
        lateinit var b: ByteArray
        lateinit var off: IntArray
        var n = 0
        var src: String? = null
    }

    private val cache = HashMap<String, D>()
    private val missing = HashSet<String>()
    private const val MAX_TRANS_CACHE = 4096
    private val transCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > MAX_TRANS_CACHE
    }
    private const val NONE = "\u0000"

    private val glossary = HashMap<String, String>()
    private var glossaryLoaded = false
    var glossaryBuiltin = 0
        private set
    var glossaryUser = 0
        private set

    private val PRONOUNS = arrayOf(
        "我们", "你们", "他们", "她们", "它们", "咱们",
        "我", "你", "他", "她", "它", "您", "咱",
    )
    private const val WEAK_HEAD =
        "的了吗呢吧啊哦嗯着过和与或及在有是不没很还就都也又再把被让给对从上到一二三四五六七八九十百千万亿横纵正反大小高低长短"

    @Synchronized
    fun get(opener: Opener, s: String, t: String): D? {
        val k = "${s}_$t"
        cache[k]?.let { return it }
        if (missing.contains(k)) return null
        return try {
            try {
                opener.open("dicts/$k.tsv.gz").use { raw ->
                    val d = parse(GZIPInputStream(BufferedInputStream(raw, 65536), 65536))
                    d.src = "$k.tsv.gz"
                    cache[k] = d
                    d
                }
            } catch (gzFail: Throwable) {
                opener.open("dicts/$k.tsv").use { raw ->
                    val d = parse(BufferedInputStream(raw, 65536))
                    d.src = "$k.tsv"
                    cache[k] = d
                    d
                }
            }
        } catch (e: Throwable) {
            missing.add(k)
            null
        }
    }

    internal fun parse(input: InputStream): D {
        input.use { stream ->
            val bo = ByteArrayOutputStream(1 shl 22)
            val buf = ByteArray(65536)
            while (true) {
                val r = stream.read(buf)
                if (r <= 0) break
                bo.write(buf, 0, r)
            }
            val d = D()
            d.b = bo.toByteArray()
            var c = 0
            for (x in d.b) if (x == '\n'.code.toByte()) c++
            d.off = IntArray(c + 1)
            d.off[0] = 0
            var j = 1
            for (i in d.b.indices) {
                if (d.b[i] == '\n'.code.toByte() && j <= c) { d.off[j] = i + 1; j++ }
            }
            d.n = c
            return d
        }
    }

    fun has(opener: Opener, s: String, t: String): Boolean = get(opener, s, t) != null
    fun size(opener: Opener, s: String, t: String): Int = get(opener, s, t)?.n ?: 0
    fun loadedVia(opener: Opener, s: String, t: String): String? = get(opener, s, t)?.src

    @Synchronized
    fun loadGlossary(opener: Opener?, userTsv: String?) {
        glossary.clear()
        glossaryBuiltin = 0
        glossaryUser = 0
        glossaryLoaded = true
        if (opener != null) {
            try {
                opener.open("glossary.tsv").use { glossaryBuiltin = mergeGlossaryStream(it, glossary) }
            } catch (ignored: Throwable) {
            }
        }
        if (!userTsv.isNullOrEmpty()) {
            glossaryUser = mergeGlossaryStream(
                ByteArrayInputStream(userTsv.toByteArray(Charsets.UTF_8)), glossary
            )
        }
    }

    private fun mergeGlossaryStream(input: InputStream, into: MutableMap<String, String>): Int {
        var n = 0
        try {
            java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val k = line.substring(0, tab).trim()
                    val v = line.substring(tab + 1).trim()
                    if (k.isEmpty() || v.isEmpty()) continue
                    into[k] = v
                    n++
                }
            }
        } catch (ignored: Throwable) {
        }
        return n
    }

    /** 去掉第三列 cost 的值 */
    internal fun find(d: D, key: String): String? {
        val v = findRaw(d, key) ?: return null
        val t = v.lastIndexOf('\t')
        return if (t >= 0) v.substring(0, t) else v
    }

    internal fun costOf(raw: String): Int {
        val t = raw.lastIndexOf('\t')
        if (t < 0) return -1
        return try {
            raw.substring(t + 1).trim().toInt()
        } catch (e: Exception) {
            -1
        }
    }

    internal fun findRaw(d: D, key: String): String? {
        val k = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = d.n - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val p = d.off[m]
            var c = 0
            var i = 0
            while (true) {
                var a = d.b[p + i].toInt() and 0xff
                if (a == '\t'.code) a = -1
                val bb = if (i < k.size) k[i].toInt() and 0xff else -1
                if (a == -1 && bb == -1) { c = 0; break }
                if (a != bb) { c = a - bb; break }
                i++
            }
            if (c == 0) {
                var e = p + k.size + 1
                val s = e
                while (e < d.b.size && d.b[e] != '\n'.code.toByte()) e++
                return String(d.b, s, e - s, Charsets.UTF_8)
            }
            if (c < 0) lo = m + 1 else hi = m - 1
        }
        return null
    }

    internal fun cleanGloss(g: String?): String? {
        var s = g ?: return null
        val semi = s.indexOf(';')
        if (semi >= 0) s = s.substring(0, semi)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        s = s.trim()
        if (s.regionMatches(0, "to ", 0, 3, ignoreCase = true)) s = s.substring(3).trim()
        s = s.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        return fitGlossToken(s, 24)
    }

    fun fitGlossToken(g: String?, maxChars: Int): String? {
        var s = g ?: return null
        s = s.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        if (s.length <= maxChars) return s
        var cut = -1
        for (i in minOf(maxChars, s.length - 1) downTo maxOf(4, maxChars / 3)) {
            val ch = s[i]
            if (ch == ' ' || ch == '·' || ch == '/' || ch == ',') { cut = i; break }
        }
        if (cut > 0) {
            var head = s.substring(0, cut).trim()
            if (head.endsWith("·")) head = head.substring(0, head.length - 1).trim()
            return if (head.isEmpty()) null else "$head…"
        }
        return s.substring(0, maxChars).trim()
    }

    private fun stripLeadingPronoun(key: String): String? {
        for (p in PRONOUNS) {
            if (key.startsWith(p) && key.length > p.length) return key.substring(p.length)
        }
        return null
    }

    private fun isWeakHeadChar(key: String?): Boolean {
        if (key.isNullOrEmpty()) return true
        return WEAK_HEAD.indexOf(key[0]) >= 0
    }

    // ---- 短语引擎：整词 → 可能补语 → DP 分词 + 组合 ----

    private const val PARTICLES = "的了吗呢吧啊呀哦嘛啦么着过哈呗噢喔哇嗯"
    private val FUNC = HashMap<String, String>()

    private val OBJ = setOf("me", "you", "him", "her", "us", "them")
    private val BE = HashMap<String, String>()
    private val POSS = HashMap<String, String>()
    private val FUNC_VERBS = setOf("去", "来", "走", "到", "见", "想", "要", "会", "能", "叫", "有", "是", "在")
    private val INV = HashMap<String, String>()
    private const val NEG = "不没别"
    private val COMPL = mapOf('懂' to "understand", '见' to "see", '完' to "finish", '清' to "make out", '起' to "afford", '动' to "move")

    init {
        val f = arrayOf(
            "我", "I", "你", "you", "您", "you", "他", "he", "她", "she", "它", "it", "我们", "we", "咱们", "we",
            "你们", "you", "他们", "they", "她们", "they", "大家", "everyone", "自己", "oneself",
            "很", "very", "太", "too", "也", "also", "都", "all", "还", "still", "就", "just", "又", "again",
            "再", "again", "真", "really", "最", "most", "更", "more", "非常", "very", "已经", "already",
            "在", "at", "是", "is", "有", "have", "要", "want", "想", "want", "会", "can", "能", "can",
            "可以", "can", "这", "this", "那", "that", "这个", "this", "那个", "that", "一下", "", "一点", "a bit",
            "和", "and", "跟", "with", "给", "for", "把", "", "被", "by", "对", "right", "好", "good",
            "去", "go", "来", "come", "到", "arrive", "点", "o'clock", "见", "see", "走", "go",
            "哪", "where", "哪里", "where", "哪儿", "where", "什么", "what", "谁", "who", "怎么", "how",
            "为什么", "why", "多少", "how many", "几", "how many", "不错", "not bad",
            "不会", "can't", "不能", "can't", "不要", "don't", "不用", "no need to", "不想", "don't want",
            "没有", "have no", "不是", "isn't", "叫", "be called", "个", "", "本", "", "张", "", "件", "", "条", "", "位", "", "种", "", "杯", "", "瓶", "", "辆", "", "双", "", "台", "",
        )
        var i = 0
        while (i < f.size) { FUNC[f[i]] = f[i + 1]; i += 2 }
        val v = arrayOf("I", "am I", "you", "are you", "he", "is he", "she", "is she", "it", "is it", "we", "are we", "they", "are they")
        i = 0
        while (i < v.size) { INV[v[i]] = v[i + 1]; i += 2 }
        val p = arrayOf("我", "my", "你", "your", "您", "your", "他", "his", "她", "her", "它", "its", "我们", "our", "咱们", "our", "你们", "your", "他们", "their", "她们", "their")
        i = 0
        while (i < p.size) { POSS[p[i]] = p[i + 1]; i += 2 }
        BE["I"] = "I'm"; BE["you"] = "you're"; BE["he"] = "he's"; BE["she"] = "she's"
        BE["it"] = "it's"; BE["we"] = "we're"; BE["they"] = "they're"
    }

    internal fun displayGloss(v: String?, allowSecond: Boolean): String? {
        if (v == null) return null
        val ss = v.split("; ")
        var a = stripTo(ss[0]) ?: return null
        if (a.length > 20 && ss.size > 1 && isIdiomLike(ss)) {
            var i = 1
            while (i < ss.size && i < 4) {
                val c = stripTo(ss[i])
                if (c != null && c.length >= 4 && c.length < a.length) a = c
                i++
            }
        }
        if (allowSecond && ss.size > 1 && a.length <= 10) {
            val b = stripTo(ss[1])
            if (b != null && !b.equals(a, ignoreCase = true) && a.length + b.length + 2 <= 22) a = "$a; $b"
        }
        return fitGlossToken(a, 24)
    }

    private fun isIdiomLike(ss: List<String>): Boolean =
        !ss[0].startsWith("to ") || ss[0].split(" ").size > 5

    private fun stripTo(g: String?): String? {
        var s = g ?: return null
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        s = s.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
        if (s.regionMatches(0, "to ", 0, 3, ignoreCase = true)) s = s.substring(3).trim()
        return s.ifEmpty { null }
    }

    private fun segGloss(d: D, w: String, preferVerb: Boolean = false): String? {
        FUNC[w]?.let { return it }
        val v = find(d, w) ?: return null
        val ss = v.split("; ")
        var pick = ss[0]
        if (preferVerb && !pick.startsWith("to ")) {
            for (x in ss) if (x.startsWith("to ")) { pick = x; break }
        }
        val g = shortSense(pick)
        if (g != null && g.length <= 16) return g
        for (x in ss) {
            val c = shortSense(x)
            if (c != null && c.length <= 16) return c
        }
        return if (g != null && g.length <= 22) g else null
    }

    private fun shortSense(x: String): String? {
        var g = stripTo(x) ?: return null
        val comma = g.indexOf(", ")
        if (comma > 0) g = g.substring(0, comma)
        return g
    }

    private fun isParticle(w: String): Boolean = w.length == 1 && PARTICLES.indexOf(w[0]) >= 0

    private fun negate(g: String?, neg: Char): String? {
        if (g.isNullOrEmpty()) return null
        val lg = g.lowercase(Locale.ROOT)
        if (lg.startsWith("not ") || lg.startsWith("no ")) return g
        if (neg == '别') return "don't $g"
        if (lg.startsWith("be ") && neg != '没') return "not " + g.substring(3)
        if (neg == '没') {
            if (lg == "have") return "not have"
            if (lg in setOf("time", "money", "problem", "way", "matter", "meaning", "method")) return "no $g"
            return "not $g"
        }
        return "not $g"
    }

    private fun findWhole(d: D, key: String, cjk: Boolean): String? {
        find(d, key)?.let { return displayGloss(it, true) }
        if (!cjk || key.length < 2) return null
        val c0 = key[0]
        if (NEG.indexOf(c0) >= 0 && key.length >= 2) {
            val rest = key.substring(1)
            if (find(d, rest) != null) return negate(segGloss(d, rest, true), c0)
        }
        return null
    }

    /** X不Y 可能补语（看不懂 → can't understand） */
    private fun potential(d: D, key: String): String? {
        val i = key.indexOf('不')
        if (key.length != 3 || i != 1) return null
        val xy = "${key[0]}${key[2]}"
        val v = find(d, xy) ?: run {
            val c = COMPL[key[2]] ?: return null
            return if (find(d, key.substring(0, 1)) == null) null else "can't $c"
        }
        val g = stripTo(v.split("; ")[0]) ?: return null
        if (g.length > 18) return null
        return "can't $g"
    }

    /** DP 分词（最小频次代价）+ 语法组合；不自信返回 null */
    private fun segmentCompose(d: D, key: String): String? {
        val n = key.length
        if (n < 2 || n > 12) return null
        val INF = Int.MAX_VALUE / 4
        val SEG = 40
        val best = IntArray(n + 1) { INF }
        val back = IntArray(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] >= INF) continue
            var j = minOf(n, i + 8)
            while (j > i) {
                if (!(i == 0 && j == n)) {
                    val w = key.substring(i, j)
                    val c = if (isParticle(w)) 15 else {
                        val raw = findRaw(d, w)
                        val gl = glossary[w]
                        if (raw == null && gl == null && !FUNC.containsKey(w)) {
                            j--
                            continue
                        }
                        var fc = if (raw == null) 60 else costOf(raw)
                        if (fc < 0) fc = 120
                        fc + SEG + (if (w.length == 1 && !FUNC.containsKey(w)) 40 else 0)
                    }
                    if (best[i] + c < best[j]) { best[j] = best[i] + c; back[j] = i }
                }
                j--
            }
        }
        if (best[n] >= INF) return null
        val segs = ArrayList<String>()
        var jj = n
        while (jj > 0) { segs.add(0, key.substring(back[jj], jj)); jj = back[jj] }

        val out = ArrayList<String>()
        var content = 0
        var weakSingles = 0
        var pendingNeg: Char = 0.toChar()
        for ((k, w0) in segs.withIndex()) {
            var w = w0
            if (isParticle(w)) continue
            if (w.length == 1 && NEG.indexOf(w[0]) >= 0) { pendingNeg = w[0]; continue }
            val prev = out.lastOrNull() ?: ""
            val next = if (k + 1 < segs.size) segs[k + 1] else ""
            var nextIsVerb = false
            if (next.isNotEmpty() && !isParticle(next) && !FUNC.containsKey(next)) {
                val nv = find(d, next)
                nextIsVerb = nv != null && nv.startsWith("to ")
            }
            if (FUNC_VERBS.contains(next)) nextIsVerb = true
            if (w == "很" && (next == "想" || nextIsVerb)) { out.add("really"); continue }
            if (w.length == 3 && w[0] == w[2] && (w[1] == '不' || w[1] == '没')) w = w.substring(0, 1)
            if (w == "在" && nextIsVerb) continue
            if (w == "想" && pendingNeg == '没') { out.add("didn't think"); pendingNeg = 0.toChar(); continue }
            if (w == "没有" && nextIsVerb) { pendingNeg = '没'; continue }
            if (POSS.containsKey(w) && next == "的") { out.add(POSS[w]!!); continue }
            val subj = pendingNeg != 0.toChar()
            var g = glossary[w] ?: segGloss(d, w, subj) ?: return null
            if (w.length == 1 && !FUNC.containsKey(w)) weakSingles++
            if (!FUNC.containsKey(w)) content++
            if (g.isEmpty()) continue
            if (g.endsWith("?")) g = g.substring(0, g.length - 1).trim()
            if (out.isNotEmpty()) {
                g = when (g) {
                    "I" -> "me"
                    "he" -> "him"
                    "she" -> "her"
                    "we" -> "us"
                    "they" -> "them"
                    else -> g
                }
                if (prev == "want" && g in OBJ) out[out.size - 1] = "miss"
            }
            val wNext = next in setOf("谁", "什么", "哪", "哪里", "哪儿")
            if ((g == "is" || g == "very") && pendingNeg == 0.toChar() && BE.containsKey(prev) && !wNext) {
                out[out.size - 1] = BE[prev]!!
                if (g == "is") continue
            }
            if (g.startsWith("be ") && pendingNeg == 0.toChar() && BE.containsKey(prev)) {
                out[out.size - 1] = BE[prev]!!
                g = g.substring(3)
            }
            if (out.isNotEmpty() && g.startsWith("$prev ")) out.removeAt(out.size - 1)
            if (pendingNeg != 0.toChar()) {
                var isVerb = FUNC_VERBS.contains(w)
                if (!isVerb) {
                    val wv = find(d, w)
                    isVerb = wv != null && wv.startsWith("to ")
                }
                g = if (pendingNeg == '没' && isVerb && !g.startsWith("not ")) "didn't $g" else negate(g, pendingNeg) ?: return null
                pendingNeg = 0.toChar()
            }
            out.add(g)
        }
        if (pendingNeg != 0.toChar()) out.add("not")
        if (out.isEmpty()) return null
        var anyFunc = false
        for (w in segs) if (FUNC.containsKey(w) || isParticle(w)) anyFunc = true
        if (!anyFunc && content >= 3) return null
        if (n == 2 && weakSingles == 2) return null
        if (content == 0 && out.size > 4) return null
        if (content > 4 || weakSingles > 2 || (weakSingles == 2 && content > 3)) return null
        if (key.endsWith("吧") && out.isNotEmpty() && out[0] == "we") out[0] = "let's"
        val last = out[out.size - 1]
        if (out.size >= 2 && last in setOf("where", "what", "who")) {
            out.removeAt(out.size - 1)
            if (out.size == 2 && out[1] == "is" && INV.containsKey(out[0])) {
                val subj = out[0]
                out.clear()
                out.add(INV[subj]!!)
            }
            out.add(0, last)
        }
        var r = out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            .replace("where at", "where").replace("where go", "where to go")
        r = Grammar.fix(r, key) ?: return null
        if (r.length > 30) return null
        composedFlag = true
        return r
    }

    @Volatile
    private var composedFlag = false
    private val composed = HashSet<String>()

    fun wasComposed(word: String?, tgt: String): Boolean =
        word != null && composed.contains(word.trim() + "\t" + tgt)

    private fun findFlex(d: D, key: String, cjk: Boolean): String? {
        val r = findWhole(d, key, cjk)
        if (r != null || !cjk) return r
        if (key.length < 2) return null
        potential(d, key)?.let { return it }
        return segmentCompose(d, key)
    }

    fun detect(w: String): List<String> {
        var han = false; var kana = false; var hangul = false; var cyr = false; var latin = false
        var i = 0
        while (i < w.length) {
            val cp = w.codePointAt(i)
            i += Character.charCount(cp)
            val isHan = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF
            val isKana = cp in 0x3040..0x30FF
            val isHangul = cp in 0xAC00..0xD7AF
            val isCyr = cp in 0x0400..0x04FF
            val isLatin = cp < 0x2E80
            if (isHan) han = true
            else if (isKana) kana = true
            else if (isHangul) hangul = true
            else if (isCyr) cyr = true
            else if (isLatin) latin = true
        }
        return when {
            kana -> listOf("ja")
            hangul -> listOf("ko")
            han -> listOf("zh", "ja")
            cyr -> listOf("ru")
            latin -> listOf("en", "de", "fr", "es")
            else -> emptyList()
        }
    }

    fun detectPrimary(w: String): String? = detect(w).firstOrNull()

    private fun pivotKey(g: String): String {
        var s = g.split(";", ",")[0].trim().lowercase(Locale.ROOT)
        if (s.startsWith("to ")) s = s.substring(3)
        if (s.startsWith("not ")) s = s.substring(4)
        return s
    }

    private fun isCjkKey(s: String): Boolean = s == "zh" || s == "ja" || s == "ko"

    private fun glossaryLookup(word: String): String? {
        if (!glossaryLoaded) return null
        glossary[word]?.let { return fitGlossToken(it, 28) }
        if (word.isNotEmpty() && word[0] < 0x2E80.toChar()) {
            glossary[word.lowercase(Locale.ROOT)]?.let { return fitGlossToken(it, 28) }
        }
        return null
    }

    /** 离线翻译（带缓存）。无高置信结果返回 null */
    @Synchronized
    fun translate(opener: Opener, word0: String?, tgt: String): String? {
        if (word0 == null) return null
        val word = word0.trim()
        if (word.isEmpty() || word.length > 30) return null
        if (!glossaryLoaded) loadGlossary(opener, null)

        val ck = "$word\t$tgt"
        transCache[ck]?.let { return if (it == NONE) null else it }

        if (tgt == "en") {
            glossaryLookup(word)?.let {
                transCache[ck] = it
                return it
            }
        }

        composedFlag = false
        val srcs = detect(word)
        if (srcs.size == 2 && srcs[0] == "zh" && tgt == "en") {
            val z = get(opener, "zh", "en")
            val j = get(opener, "ja", "en")
            if (z != null && find(z, word) == null && j != null) {
                find(j, word)?.let { jw ->
                    val result = displayGloss(jw, false)
                    transCache[ck] = result!!
                    return result
                }
            }
        }
        var result: String? = null
        for (s in srcs) {
            if (s == tgt) { transCache[ck] = NONE; return null }
            val key = if (isCjkKey(s)) word else word.lowercase(Locale.ROOT)
            val cjk = isCjkKey(s)
            val d = get(opener, s, tgt)
            if (d != null) {
                val r = findFlex(d, key, cjk)
                if (r != null) { result = r; break }
                continue
            }
            val se = get(opener, s, "en")
            val et = get(opener, "en", tgt)
            if (se != null) {
                val en = findFlex(se, key, cjk)
                if (en != null) {
                    if (tgt == "en") { result = en; break }
                    if (et != null) {
                        val r = find(et, pivotKey(en))
                        if (r != null) { result = cleanGloss(r); break }
                    }
                }
            }
        }
        if (composedFlag && result != null) composed.add(ck)
        if (result != null && !composedFlag &&
            !isCjkKey(srcs.firstOrNull() ?: "") && word.indexOf(' ') > 0
        ) composed.add(ck)
        if (composed.size > 4096) composed.clear()
        transCache[ck] = result ?: NONE
        return result
    }

    @Synchronized
    fun clearTransCache() {
        transCache.clear()
    }
}
