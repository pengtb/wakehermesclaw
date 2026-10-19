package com.openclaw.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitPhrasesTest {

    private val defaults = ExitPhrases.parse("退出,退出对话,结束对话,exit,quit")

    @Test fun `plain exit word matches`() {
        assertTrue(ExitPhrases.matches("退出", defaults))
        assertTrue(ExitPhrases.matches("exit", defaults))
    }

    @Test fun `punctuation and particles are ignored`() {
        assertTrue(ExitPhrases.matches("退出。", defaults))
        assertTrue(ExitPhrases.matches("退出吧", defaults))
        assertTrue(ExitPhrases.matches("  EXIT! ", defaults))
        assertTrue(ExitPhrases.matches("退出对话呀", defaults))
    }

    @Test fun `longer sentences containing the word are not exits`() {
        assertFalse(ExitPhrases.matches("退出这个应用怎么弄", defaults))
        assertFalse(ExitPhrases.matches("我想知道怎么退出登录", defaults))
        assertFalse(ExitPhrases.matches("", defaults))
    }

    @Test fun `separators split commas chinese commas and newlines`() {
        assertEquals(listOf("退出", "exit", "quit"), ExitPhrases.parse("退出，exit\nquit"))
        assertEquals(emptyList<String>(), ExitPhrases.parse("  ,  \n "))
    }

    @Test fun `custom phrases replace the defaults entirely`() {
        val custom = ExitPhrases.parse("拜拜,完事了")
        assertTrue(ExitPhrases.matches("拜拜", custom))
        assertFalse(ExitPhrases.matches("退出", custom))
    }
}
