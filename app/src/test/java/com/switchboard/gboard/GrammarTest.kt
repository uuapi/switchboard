package com.switchboard.gboard

import com.switchboard.gboard.hook.gloss.Grammar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrammarTest {

    @Test
    fun `verb after want gets to-infinitive`() {
        assertEquals("want to go", Grammar.fix("want go"))
        assertEquals("need to eat", Grammar.fix("need eat"))
    }

    @Test
    fun `third person singular agreement`() {
        assertEquals("he goes", Grammar.fix("he go"))
        assertEquals("she eats", Grammar.fix("she eat"))
        assertEquals("it works", Grammar.fix("it work"))
    }

    @Test
    fun `modal to collapses`() {
        assertEquals("can go", Grammar.fix("can to go"))
        assertEquals("will go", Grammar.fix("will to go"))
    }

    @Test
    fun `to to collapses`() {
        assertEquals("want to go", Grammar.fix("want to to go"))
    }

    @Test
    fun `null and empty pass through`() {
        assertNull(Grammar.fix(null))
        assertEquals("", Grammar.fix(""))
    }

    @Test
    fun `sentence level fix marks question`() {
        // 你去吗 → compositional gloss "you go" → "Do you go?"
        assertEquals("Do you go?", Grammar.fix("you go", "去吗"))
    }

    @Test
    fun `sentence level fix marks past`() {
        assertEquals("you went", Grammar.fix("you go", "去了"))
    }
}
