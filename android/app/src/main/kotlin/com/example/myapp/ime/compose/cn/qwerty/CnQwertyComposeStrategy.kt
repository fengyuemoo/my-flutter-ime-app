package com.example.myapp.ime.compose.cn.qwerty

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult

class CnQwertyComposeStrategy(
    private val sessionProvider: () -> ComposingSession,
    private val clearComposing: () -> Unit
) : ComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()

    override fun onComposingInput(text: String): StrategyResult {
        if (text.isEmpty()) return StrategyResult.Noop

        // Chinese QWERTY: Always append to session（统一小写）
        session().appendQwerty(text.lowercase())
        return StrategyResult.SessionMutated
    }

    override fun onT9Input(digit: String): StrategyResult {
        // Not handled in QWERTY mode
        return StrategyResult.Noop
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        session().clear()
        session().appendQwerty(pinyin.lowercase())
    }

    override fun onEnter(ic: InputConnection?): Boolean {
        if (session().isComposing()) {
            clearComposing()
            return true // Consumed
        }
        return false // Not consumed, let the system handle it (e.g., new line)
    }
}
