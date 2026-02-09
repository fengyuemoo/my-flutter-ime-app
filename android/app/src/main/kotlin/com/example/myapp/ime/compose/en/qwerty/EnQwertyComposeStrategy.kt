package com.example.myapp.ime.compose.en.qwerty

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult

class EnQwertyComposeStrategy(
    private val sessionProvider: () -> ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : EnglishComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()
    private fun ic(): InputConnection? = inputConnectionProvider()

    /**
     * Per-mode state: EN-QWERTY only (independent from EN-T9).
     */
    private var englishPredictEnabled: Boolean = false

    override fun getEnglishPredictEnabled(): Boolean = englishPredictEnabled

    override fun setEnglishPredictEnabled(enabled: Boolean) {
        if (englishPredictEnabled == enabled) return
        englishPredictEnabled = enabled

        // Keep old UX: switching predict mode clears composing.
        session().clear()
        ic()?.setComposingText("", 0)
    }

    override fun onComposingInput(text: String): StrategyResult {
        return if (englishPredictEnabled) {
            session().appendQwerty(text)
            StrategyResult.SessionMutated
        } else {
            StrategyResult.DirectCommit(text)
        }
    }

    override fun onT9Input(digit: String): StrategyResult = StrategyResult.Noop

    override fun onPinyinSidebarClick(pinyin: String) {
        // Not handled in English
    }

    override fun onEnter(ic: InputConnection?): StrategyResult = StrategyResult.Noop
}
