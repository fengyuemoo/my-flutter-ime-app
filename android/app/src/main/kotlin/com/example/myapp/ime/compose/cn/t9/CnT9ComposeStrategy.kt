package com.example.myapp.ime.compose.cn.t9

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult

class CnT9ComposeStrategy(
    private val sessionProvider: () -> ComposingSession
) : ComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()

    override fun onComposingInput(text: String): StrategyResult {
        // Not handled in T9 mode for Chinese
        return StrategyResult.Noop
    }

    override fun onT9Input(digit: String): StrategyResult {
        session().appendT9Digit(digit)
        return StrategyResult.SessionMutated
    }

    private fun pinyinToT9Code(pinyin: String): String {
        val s = pinyin.lowercase()
        val sb = StringBuilder()
        for (ch in s) {
            val d = when (ch) {
                'a', 'b', 'c' -> '2'
                'd', 'e', 'f' -> '3'
                'g', 'h', 'i' -> '4'
                'j', 'k', 'l' -> '5'
                'm', 'n', 'o' -> '6'
                'p', 'q', 'r', 's' -> '7'
                't', 'u', 'v', 'ü' -> '8'
                'w', 'x', 'y', 'z' -> '9'
                else -> null
            }
            if (d != null) sb.append(d)
        }
        return sb.toString()
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        val code = pinyinToT9Code(pinyin)
        session().onPinyinSidebarClick(pinyin.lowercase(), code)
    }

    override fun onEnter(ic: InputConnection?): StrategyResult {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic

        if (!session().isComposing()) return StrategyResult.Noop

        val previewCommit = session().t9PreviewCommitText()
        return if (!previewCommit.isNullOrEmpty()) {
            StrategyResult.DirectCommit(previewCommit)
        } else {
            StrategyResult.Noop
        }
    }
}
