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

        // Chinese QWERTY: Always append to session
        session().appendQwerty(text)
        return StrategyResult.SessionMutated
    }

    override fun onT9Input(digit: String): StrategyResult {
        // Not handled in QWERTY mode
        return StrategyResult.Noop
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        // In a real implementation, this might involve more complex logic,
        // such as clearing the existing composing text and starting a new session.
        session().clear()
        session().appendQwerty(pinyin)
    }

    override fun onEnter(ic: InputConnection?): Boolean {
        if (session().isComposing()) {
            // If there's composing text, commit the first candidate or the composing text itself.
            // This part would interact with the candidate view to get the selection.
            // For simplicity, we'll just clear composing here.
            clearComposing()
            return true // Consumed
        }
        return false // Not consumed, let the system handle it (e.g., new line)
    }
}
