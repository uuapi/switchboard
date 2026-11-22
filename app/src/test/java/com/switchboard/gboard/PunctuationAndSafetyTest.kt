package com.switchboard.gboard

import com.switchboard.gboard.hook.ClipboardHook
import com.switchboard.gboard.hook.MathSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationAndSafetyTest {

    // ---------- 九键标点：运算符映射 ----------
    @Test
    fun `operator map covers digit page punctuation`() {
        assertEquals("+", MathSymbols.transform("？"))
        assertEquals("-", MathSymbols.transform("！"))
        assertEquals("×", MathSymbols.transform("……"))
        assertEquals("÷", MathSymbols.transform("～"))
    }

    @Test
    fun `halfwidth variants map too`() {
        assertEquals("+", MathSymbols.transform("?"))
        assertEquals("-", MathSymbols.transform("!"))
        assertEquals("×", MathSymbols.transform("…"))
        assertEquals("÷", MathSymbols.transform("~"))
    }

    @Test
    fun `normal text untouched`() {
        assertNull(MathSymbols.transform("hello"))
        assertNull(MathSymbols.transform("，"))
    }

    @Test
    fun `key label mappings take precedence`() {
        MathSymbols.setReplaceMappings(mapOf("，" to ","))
        try {
            assertEquals(",", MathSymbols.transform("，"))
        } finally {
            MathSymbols.clearReplaceMappings()
        }
        // 清除后回到静态表
        assertNull(MathSymbols.transform("，"))
    }

    // ---------- 剪贴板敏感信息判定 ----------
    @Test
    fun `otp codes blocked`() {
        assertTrue(ClipboardHook.isSensitiveText("123456"))
        assertTrue(ClipboardHook.isSensitiveText("4821"))
    }

    @Test
    fun `card number rule also matches plain long digit runs`() {
        // 12-19 位纯数字按卡号拦截（13 位 = 毫秒时间戳长度，hook 侧依赖
        // insert 遍历只检查 String 值来避开 metadata 列）
        assertTrue(ClipboardHook.isSensitiveText("1795000000123"))
        assertFalse(ClipboardHook.isSensitiveText("11-digit number"))
    }

    @Test
    fun `card numbers blocked even with separators`() {
        assertTrue(ClipboardHook.isSensitiveText("4111 1111 1111 1111"))
        assertTrue(ClipboardHook.isSensitiveText("6222-0211-1111-1111"))
        assertTrue(ClipboardHook.isSensitiveText("1234567890123456"))
    }

    @Test
    fun `keywords blocked`() {
        assertTrue(ClipboardHook.isSensitiveText("password: hunter2"))
        assertTrue(ClipboardHook.isSensitiveText("您的验证码是 8848"))
        assertTrue(ClipboardHook.isSensitiveText("auth token abc"))
    }

    @Test
    fun `normal content allowed`() {
        assertFalse(ClipboardHook.isSensitiveText("hello world"))
        assertFalse(ClipboardHook.isSensitiveText("明天下午三点开会"))
        assertFalse(ClipboardHook.isSensitiveText("https://example.com/path"))
        assertFalse(ClipboardHook.isSensitiveText(""))
    }
}
