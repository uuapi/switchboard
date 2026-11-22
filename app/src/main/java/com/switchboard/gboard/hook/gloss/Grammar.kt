/*
 * Ported from gboard-gloss Grammar.java.
 * SPDX-License-Identifier: LGPL-2.1-or-later (part of Switchboard gloss port)
 */
package com.switchboard.gboard.hook.gloss

import java.util.regex.Matcher
import java.util.regex.Pattern

/** 词典合成短语的轻量英语语法修正（离线回退用） */
internal object Grammar {
    private val VERBS = setOf(
        "know", "go", "eat", "see", "do", "be", "have", "buy", "sleep", "learn", "ask", "say", "tell",
        "make", "take", "come", "get", "find", "understand", "study", "play", "work", "read", "write",
        "watch", "listen", "help", "try", "leave", "rest", "change", "use", "sing", "travel", "drink",
        "meet", "call", "visit", "live", "stay", "wait", "talk", "speak", "think", "give", "look",
        "send", "open", "close", "start", "stop", "finish", "return", "sit", "stand", "walk", "run",
        "swim", "drive", "cook", "wash", "clean", "pay", "sell", "borrow", "lend", "marry", "die",
        "cry", "laugh", "relax", "shop", "dance", "draw", "fly", "move", "join", "win", "lose",
        "chat", "explain", "answer", "remember", "forget", "believe", "check", "fix", "share",
        "become", "keep", "let", "put", "bring", "show", "hear", "feel", "love", "like", "hate",
        "follow", "choose", "decide", "prepare", "practice", "improve", "earn", "save", "spend",
    )
    private val TO_INF: Pattern = Pattern.compile(
        "\\b(want|wants|wanted|need|needs|hope|hopes|plan|plans|try|tries|decide|decided|" +
            "prepare|forget|remember|learn|begin|start|like|love|hate|going)\\s+([a-z]+)\\b"
    )
    private val MODAL_TO: Pattern =
        Pattern.compile("\\b(can|can't|will|won't|must|should|could|would|may|might|don't|didn't|doesn't) to\\b")
    private val THIRD: Pattern = Pattern.compile("^(he|she|it) ([a-z]+)\\b")
    private val SUBJ_NOT: Pattern = Pattern.compile("^(I|you|we|they|he|she|it) not ([a-z]+)\\b")
    private val MODAL_Q: Pattern = Pattern.compile(
        "^(I|you|we|they|he|she|it) (can|will|should|must|could|would|may|can't|won't) (.+)$"
    )
    private val PRES_Q: Pattern =
        Pattern.compile("^(I|you|we|they|he|she|it) (?:don't |doesn't )?([a-z]+)(.*)$")
    private val PAST_S: Pattern = Pattern.compile("^(I|you|we|they|he|she|it) ([a-z]+)\\b")

    private val COMMON = HashMap<String, String>()
    private val PAST = HashMap<String, String>()

    init {
        val c = arrayOf(
            "comprehend", "understand", "coin", "money", "consume", "eat",
            "return home", "go home", "purchase", "buy", "inquire", "ask", "reside", "live",
        )
        var i = 0
        while (i < c.size) { COMMON[c[i]] = c[i + 1]; i += 2 }
        val p = arrayOf(
            "go", "went", "eat", "ate", "see", "saw", "do", "did", "have", "had", "buy", "bought",
            "sleep", "slept", "say", "said", "tell", "told", "make", "made", "take", "took", "come", "came",
            "get", "got", "find", "found", "understand", "understood", "read", "read", "write", "wrote",
            "leave", "left", "drink", "drank", "meet", "met", "speak", "spoke", "think", "thought", "give", "gave",
            "send", "sent", "sit", "sat", "stand", "stood", "run", "ran", "swim", "swam", "drive", "drove",
            "pay", "paid", "sell", "sold", "lend", "lent", "draw", "drew", "fly", "flew", "win", "won", "lose", "lost",
            "become", "became", "keep", "kept", "put", "put", "bring", "brought", "hear", "heard", "feel", "felt",
            "forget", "forgot", "choose", "chose", "spend", "spent", "know", "knew", "sing", "sang", "be", "was",
        )
        i = 0
        while (i < p.size) { PAST[p[i]] = p[i + 1]; i += 2 }
    }

    private fun is3(subj: String) = subj == "he" || subj == "she" || subj == "it"

    private fun base(v: String): String =
        v.replace(Regex("(?<=[a-z])(ies)$"), "y")
            .replace(Regex("(?<=(s|sh|ch|x|z))es$"), "")
            .replace(Regex("(?<=[^s])s$"), "")

    private fun cap(w: String): String =
        if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1)

    /** 句级修正：了 → 过去式、吗 → 疑问句。不自信时返回 null（空着也比错的好） */
    fun fix(s: String?, srcKey: String?): String? {
        var r = fix(s) ?: return null
        if (r.isEmpty()) return r
        for (e in COMMON.entries) {
            r = r.replace(Regex("\\b" + Pattern.quote(e.key) + "\\b"), e.value)
        }
        val n = SUBJ_NOT.matcher(r)
        if (n.find()) {
            val subj = n.group(1)!!
            val v = n.group(2)!!
            r = if (v in setOf("be", "is", "am", "are")) {
                subj + " " + (if (subj == "I") "am" else if (is3(subj)) "is" else "are") + " not" + r.substring(n.end())
            } else if (VERBS.contains(v) || VERBS.contains(base(v)) || v.matches(Regex("want|need|hope|plan"))) {
                subj + (if (is3(subj)) " doesn't " else " don't ") + base(v) + r.substring(n.end())
            } else {
                return null
            }
        }
        val ds = Pattern.compile("\\b(don't|doesn't|didn't) ([a-z]+)\\b").matcher(r)
        val sb = StringBuffer()
        while (ds.find()) {
            ds.appendReplacement(sb, Matcher.quoteReplacement(ds.group(1) + " " + (if (VERBS.contains(base(ds.group(2)!!))) base(ds.group(2)!!) else ds.group(2))))
        }
        ds.appendTail(sb)
        r = sb.toString()

        val k = srcKey?.trim() ?: ""
        val q = k.endsWith("吗") || k.endsWith("嗎") || k.endsWith("？") || k.endsWith("?")
        val past = !q && (k.endsWith("了") || k.contains("过")) &&
            !k.contains("不") && !k.contains("没") && !k.contains("沒")
        if (past) {
            val pm = PAST_S.matcher(r)
            if (pm.find() && !r.contains("not") && !r.contains("'t")) {
                val v = base(pm.group(2)!!)
                val pv = PAST[v] ?: if (VERBS.contains(v)) {
                    when {
                        v.endsWith("e") -> v + "d"
                        Regex(".*[^aeiou]y").matches(v) -> v.substring(0, v.length - 1) + "ied"
                        else -> v + "ed"
                    }
                } else null
                if (pv != null && !Regex("can|will|must|should").matches(v)) {
                    r = pm.group(1) + " " + pv + r.substring(pm.end())
                }
            }
        }
        if (q) {
            val mq = MODAL_Q.matcher(r)
            if (mq.find()) {
                r = cap(mq.group(2)!!) + " " + mq.group(1) + " " + mq.group(3)
            } else {
                val mp = PRES_Q.matcher(r)
                if (!mp.find()) return null
                val subj = mp.group(1)!!
                val v = mp.group(2)!!
                r = if (v.matches(Regex("am|is|are"))) {
                    cap(v) + " " + subj + mp.group(3)
                } else if (VERBS.contains(base(v)) || v.matches(Regex("want|need|wants|needs"))) {
                    val neg = r.startsWith("$subj don't ") || r.startsWith("$subj doesn't ")
                    (if (is3(subj)) "Does" else "Do") + (if (neg) "n't " else " ") +
                        (if (subj == "I") "I" else subj) + " " + base(v) + mp.group(3)
                } else {
                    return null
                }
            }
            r = r.trim()
            if (r.matches(Regex("(Do|Does|Don't|Doesn't) \\w+ (like|love|want|need|know|understand|have)"))) r += " it"
            r += "?"
        }
        if (SUBJ_NOT.matcher(r).find() || r.matches(Regex(".*\\b(not|don't|doesn't|didn't)$"))) return null
        return r
    }

    fun fix(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        var r = s
        val m = TO_INF.matcher(r)
        val sb = StringBuffer()
        while (m.find()) {
            val head = m.group(1)!!
            val v = m.group(2)!!
            var add = VERBS.contains(v)
            if ((head == "like" || head == "love" || head == "hate") && !VERBS.contains(v)) add = false
            m.appendReplacement(sb, Matcher.quoteReplacement(if (add) "$head to $v" else m.group(0)!!))
        }
        m.appendTail(sb)
        r = sb.toString()
        r = MODAL_TO.matcher(r).replaceAll("$1")
        val t = THIRD.matcher(r)
        if (t.find() && (VERBS.contains(t.group(2)!!) || t.group(2)!!.matches(Regex("want|need|hope|plan")))) {
            r = t.group(1) + " " + third(t.group(2)!!) + r.substring(t.end())
        }
        return r.replace(Regex("\\bto to\\b"), "to").replace(Regex("\\s+"), " ").trim()
    }

    private fun third(v: String): String = when (v) {
        "be" -> "is"
        "have" -> "has"
        "do" -> "does"
        "go" -> "goes"
        else -> when {
            Regex(".*(s|sh|ch|x|z)").matches(v) -> v + "es"
            Regex(".*[^aeiou]y").matches(v) -> v.substring(0, v.length - 1) + "ies"
            else -> v + "s"
        }
    }
}
