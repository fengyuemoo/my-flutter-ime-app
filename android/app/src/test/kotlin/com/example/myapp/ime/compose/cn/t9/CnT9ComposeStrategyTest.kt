package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.StrategyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CnT9ComposeStrategyTest {

    @Test
    fun onEnter_returnsDirectCommit_with_sanitized_enter_text() {
        val strategy = CnT9ComposeStrategy(
            sessionProvider = { ComposingSession() },
            enterCommitProvider = { " Lü’Xing'123 " }
        )

        val result = strategy.onEnter(ic = null)

        assertTrue(result is StrategyResult.DirectCommit)
        assertEquals("lvxing", (result as StrategyResult.DirectCommit).text)
    }

    @Test
    fun onEnter_returnsNoop_when_enter_commit_text_is_blank() {
        val strategy = CnT9ComposeStrategy(
            sessionProvider = { ComposingSession() },
            enterCommitProvider = { "   " }
        )

        val result = strategy.onEnter(ic = null)

        assertTrue(result is StrategyResult.Noop)
    }

    @Test
    fun onEnter_returnsNoop_when_enter_commit_text_is_null() {
        val strategy = CnT9ComposeStrategy(
            sessionProvider = { ComposingSession() },
            enterCommitProvider = { null }
        )

        val result = strategy.onEnter(ic = null)

        assertTrue(result is StrategyResult.Noop)
    }

    @Test
    fun onEnter_keeps_ascii_letters_and_v_for_umlaut_normalization() {
        val strategy = CnT9ComposeStrategy(
            sessionProvider = { ComposingSession() },
            enterCommitProvider = { "zhong'guüo-v!" }
        )

        val result = strategy.onEnter(ic = null)

        assertTrue(result is StrategyResult.DirectCommit)
        assertEquals("zhongguvov", (result as StrategyResult.DirectCommit).text)
    }

    @Test
    fun onT9Input_appends_digits_and_returnsSessionMutated() {
        val session = ComposingSession()
        val strategy = CnT9ComposeStrategy(
            sessionProvider = { session },
            enterCommitProvider = { null }
        )

        val result = strategy.onT9Input("9a4#664")

        assertTrue(result is StrategyResult.SessionMutated)
        assertEquals("94664", session.rawT9Digits)
    }

    @Test
    fun onPinyinSidebarClick_materializes_normalized_pinyin_into_session() {
        val session = ComposingSession().apply {
            appendT9Digit("9")
            appendT9Digit("4")
            appendT9Digit("6")
            appendT9Digit("6")
            appendT9Digit("4")
        }

        val strategy = CnT9ComposeStrategy(
            sessionProvider = { session },
            enterCommitProvider = { null }
        )

        strategy.onPinyinSidebarClick("ZHONG")

        assertEquals(listOf("zhong"), session.pinyinStack)
        assertEquals("", session.rawT9Digits)
    }
}
