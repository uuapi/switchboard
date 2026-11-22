package com.switchboard.gboard

import com.switchboard.gboard.hook.gloss.Glossary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class GlossaryTest {

    /** 内存版 Opener：用小词典测整条离线翻译管线 */
    private class FakeOpener(private val files: Map<String, String>) : Glossary.Opener {
        override fun open(name: String): InputStream =
            files[name]?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.FileNotFoundException(name)
    }

    private val opener = FakeOpener(
        mapOf(
            // 精确词表
            "glossary.tsv" to "hello\thello-gloss\nworld\tworld-gloss\n",
            // en→zh 词典（带 cost 列，模拟 CC-CEDICT 预处理格式）
            "dicts/en_zh.tsv" to
                "hello\tni hao; how do you do\t120\n" +
                "world\tshi jie\t90\n" +
                "good morning\tzao an\t60\n",
            // zh→en（否定/构词测试用）
            "dicts/zh_en.tsv" to
                "go\tto go; walk\t100\n" +
                "看\tto see; to look at\t100\n" +
                "不懂\tdo not understand\t50\n",
        )
    )

    @Test
    fun `en to zh exact dictionary hit`() {
        // displayGloss 会拼接第二个短义项
        assertEquals("ni hao; how do you do", Glossary.translate(opener, "hello", "zh"))
    }

    @Test
    fun `unknown word returns null`() {
        assertNull(Glossary.translate(opener, "qqqqzzzz", "zh"))
    }

    @Test
    fun `same language without glossary hit returns null`() {
        // 上游行为：词表精确命中优先于同语言短路（en→en 命中词表仍显示），
        // 未命中词表时同语言直接短路
        assertNull(Glossary.translate(opener, "qqqqzzzz", "en"))
        assertEquals("hello-gloss", Glossary.translate(opener, "hello", "en"))
    }

    @Test
    fun `fitGlossToken truncates on word boundary with ellipsis`() {
        val out = Glossary.fitGlossToken("a very long glossary entry that overflows", 24)!!
        assertTrue(out.length <= 25)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `fitGlossToken keeps short text intact`() {
        assertEquals("hello", Glossary.fitGlossToken("hello", 24))
    }

    @Test
    fun `detect routes scripts`() {
        assertEquals(listOf("zh"), Glossary.detect("你好").take(1))
        assertEquals("ja", Glossary.detect("こんにちは").first())
        assertTrue(Glossary.detect("hello").contains("en"))
    }
}
