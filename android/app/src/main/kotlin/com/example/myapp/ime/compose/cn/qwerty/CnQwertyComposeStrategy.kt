package com.example.myapp.ime.compose.cn.qwerty

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult

class CnQwertyComposeStrategy(
    private val sessionProvider: () -> ComposingSession
) : ComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()

    override fun onComposingInput(text: String): StrategyResult {
        if (text.isEmpty()) return StrategyResult.Noop
        session().appendQwerty(text.lowercase())
        return StrategyResult.SessionMutated
    }

    override fun onT9Input(digit: String): StrategyResult {
        return StrategyResult.Noop
    }

    // 修复：签名加 t9Code（CN-QWERTY 模式不使用 sidebar，忽略 t9Code 即可）
    override fun onPinyinSidebarClick(pinyin: String, t9Code: String) {
        val s = session()
        if (s.committedPrefix.isNotEmpty()) return
        s.clear()
        s.appendQwerty(pinyin.lowercase())
    }

    override fun onEnter(ic: InputConnection?): StrategyResult {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic

        val s = session()
        if (!s.isComposing()) return StrategyResult.Noop

        val textToCommit = (s.committedPrefix + s.qwertyInput.lowercase())
        return if (textToCommit.isNotEmpty()) {
            StrategyResult.DirectCommit(textToCommit)
        } else {
            StrategyResult.Noop
        }
    }
}
