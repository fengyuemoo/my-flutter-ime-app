package com.example.myapp.ime.compose.en.qwerty

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.ModeInputEngine
import com.example.myapp.ime.ui.ImeUi

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

    // --- EnglishPredictable ---

    override fun getEnglishPredictEnabled(): Boolean = englishPredictEnabled

    override fun setEnglishPredictEnabled(enabled: Boolean) {
        if (englishPredictEnabled == enabled) return
        englishPredictEnabled = enabled

        // Keep old UX: switching predict mode clears composing.
        session().clear()
        ic()?.setComposingText("", 0)
    }

    // --- ComposeStrategy ---

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

/**
 * EN-QWERTY input engine.
 */
class EnQwertyInputEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : ModeInputEngine() {

    private val strategy: EnglishComposeStrategy =
        EnQwertyComposeStrategy(
            sessionProvider = { session },
            inputConnectionProvider = { inputConnectionProvider() }
        )

    private fun afterSessionMutated() {
        refreshCandidates()
        refreshComposingView()
        syncEnglishPredictUi()
    }

    private fun afterCommitOrClear() {
        refreshCandidates()
        syncEnglishPredictUi()
    }

    private fun clearSessionAndEditorComposing() {
        session.clear()
        ui.setComposingPreview(null)
        inputConnectionProvider()?.setComposingText("", 0)
    }

    private fun commitAndReset(text: String) {
        inputConnectionProvider()?.commitText(text, 1)
        clearSessionAndEditorComposing()
        afterCommitOrClear()
    }

    override fun refreshCandidates() {
        candidateController.updateCandidates()
    }

    override fun refreshComposingView() {
        val ic = inputConnectionProvider()
        val displayText = session.displayText(useT9Layout = false)

        if (displayText.isNullOrEmpty()) {
            ui.setComposingPreview(null)
            ic?.setComposingText("", 0)
            return
        }

        ui.setComposingPreview(displayText)
        ic?.setComposingText(displayText, 1)
    }

    override fun clearComposing() {
        clearSessionAndEditorComposing()
        afterCommitOrClear()
    }

    override fun handleComposingInput(text: String) {
        handleStrategyResult(strategy.onComposingInput(text))
    }

    override fun handleT9Input(digit: String) {
        handleStrategyResult(strategy.onT9Input(digit))
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        @Suppress("UNUSED_PARAMETER")
        val ignored = pinyin
    }

    override fun handleBackspace() {
        val consumedBySession = session.backspace(useT9Layout = false)
        if (consumedBySession) {
            if (!session.isComposing()) {
                clearComposing()
            } else {
                afterSessionMutated()
            }
            return
        }

        val ic = inputConnectionProvider() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    override fun handleSpaceKey() {
        if (strategy.isPredicting()) {
            candidateController.handleSpaceKey()
        } else {
            inputConnectionProvider()?.commitText(" ", 1)
        }
    }

    override fun handleEnter(ic: InputConnection?): Boolean {
        val result = strategy.onEnter(ic)
        return if (result is StrategyResult.Noop) {
            false
        } else {
            handleStrategyResult(result)
            true
        }
    }

    override fun beforeModeSwitch() {
        // No pending buffer in this mode.
    }

    override fun afterModeSwitch() {
        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun getEnglishPredictEnabled(): Boolean = strategy.getEnglishPredictEnabled()

    override fun setEnglishPredict(enabled: Boolean) {
        strategy.setEnglishPredictEnabled(enabled)
        afterSessionMutated()
    }

    override fun syncEnglishPredictUi() {
        keyboardController.updateEnglishPredictUi(getEnglishPredictEnabled())
    }

    private fun handleStrategyResult(result: StrategyResult) {
        when (result) {
            is StrategyResult.SessionMutated -> afterSessionMutated()
            is StrategyResult.DirectCommit -> commitAndReset(result.text)
            is StrategyResult.ComposingUpdate -> {
                ui.setComposingPreview(result.composingText)
                inputConnectionProvider()?.setComposingText(result.composingText, 1)
                refreshCandidates()
            }
            is StrategyResult.Noop -> {}
        }
    }
}
