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

    override fun onPinyinSidebarClick(pinyin: String) {
        session().clear()
        session().appendQwerty(pinyin)
    }

    override fun onEnter(ic: InputConnection?): Boolean {
        // Similar logic to CnQwertyComposeStrategy
        if (session().isComposing()) {
            return true // Consumed
        }
        return false
    }
}
