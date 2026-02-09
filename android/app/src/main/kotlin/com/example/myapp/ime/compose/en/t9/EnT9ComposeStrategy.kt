package com.example.myapp.ime.compose.en.t9

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.PendingCommitStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.ModeInputEngine
import com.example.myapp.ime.ui.ImeUi

class EnT9ComposeStrategy(
    private val sessionProvider: () -> ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : EnglishComposeStrategy, PendingCommitStrategy {

    private fun session(): ComposingSession = sessionProvider()
    private fun ic(): InputConnection? = inputConnectionProvider()

    /**
     * Per-mode independent state (EN-T9 only).
     */
    private var englishPredictEnabled: Boolean = true

    // Multi-tap state (only used when englishPredictEnabled == false)
    private var multiTapKey: Char = ' '
    private var multiTapIndex = 0
    private val multiTapHandler = Handler(Looper.getMainLooper())
    private val multiTapConfirmRunnable = Runnable {
        confirmMultiTapInternalCommitIfAny()
    }

    private val multiTapMap = mapOf(
        '2' to "abc2",
        '3' to "def3",
        '4' to "ghi4",
        '5' to "jkl5",
        '6' to "mno6",
        '7' to "pqrs7",
        '8' to "tuv8",
        '9' to "wxyz9"
    )

    // --- EnglishPredictable ---

    override fun getEnglishPredictEnabled(): Boolean = englishPredictEnabled

    override fun setEnglishPredictEnabled(enabled: Boolean) {
        if (englishPredictEnabled == enabled) return
        englishPredictEnabled = enabled

        session().clear()
        resetMultiTapState()
        ic()?.setComposingText("", 0)
    }

    // --- ComposeStrategy ---

    override fun onComposingInput(text: String): StrategyResult {
        // Not handled in T9
        return StrategyResult.Noop
    }

    override fun onT9Input(digit: String): StrategyResult {
        if (digit.isEmpty()) return StrategyResult.Noop

        return if (englishPredictEnabled) {
            resetMultiTapState()
            session().appendT9Digit(digit)
            StrategyResult.SessionMutated
        } else {
            handleMultiTap(digit[0])
        }
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        // Not handled in English
    }

    override fun onEnter(ic: InputConnection?): StrategyResult = StrategyResult.Noop

    // --- PendingCommitStrategy ---

    override fun flushPendingCommit(): StrategyResult = confirmMultiTap()

    override fun handleBackspaceInOwnBuffer(ic: InputConnection?): Boolean {
        if (!englishPredictEnabled) {
            if (resetMultiTapState()) {
                ic()?.setComposingText("", 0)
                return true
            }
        }
        return false
    }

    // --- Multi-tap helpers (non-predict path) ---

    private fun handleMultiTap(digit: Char): StrategyResult {
        if (digit == '0' || digit == '1') {
            resetMultiTapState()
            return StrategyResult.DirectCommit(digit.toString())
        }

        if (!multiTapMap.containsKey(digit)) {
            resetMultiTapState()
            return StrategyResult.DirectCommit(digit.toString())
        }

        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)

        if (multiTapKey == digit) {
            val len = multiTapMap[digit]?.length ?: 1
            multiTapIndex = (multiTapIndex + 1) % len
        } else {
            confirmMultiTapInternalCommitIfAny()
            multiTapKey = digit
            multiTapIndex = 0
        }

        val composingText = getMultiTapComposingText()
        multiTapHandler.postDelayed(multiTapConfirmRunnable, 800)
        return StrategyResult.ComposingUpdate(composingText)
    }

    private fun getMultiTapComposingText(): String {
        if (multiTapKey == ' ') return ""
        val chars = multiTapMap[multiTapKey] ?: return ""
        if (chars.isEmpty()) return ""
        return chars[multiTapIndex].toString()
    }

    private fun confirmMultiTapInternalCommitIfAny() {
        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)
        if (multiTapKey == ' ') return

        val textToCommit = getMultiTapComposingText()
        resetMultiTapState()

        if (textToCommit.isNotEmpty()) {
            val ic = ic()
            ic?.setComposingText("", 0)
            ic?.commitText(textToCommit, 1)
        }
    }

    private fun confirmMultiTap(): StrategyResult {
        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)
        if (multiTapKey == ' ') return StrategyResult.Noop

        val textToCommit = getMultiTapComposingText()
        resetMultiTapState()

        return if (textToCommit.isNotEmpty()) {
            StrategyResult.DirectCommit(textToCommit)
        } else {
            StrategyResult.Noop
        }
    }

    private fun resetMultiTapState(): Boolean {
        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)
        if (multiTapKey == ' ') return false
        multiTapKey = ' '
        multiTapIndex = 0
        return true
    }
}

/**
 * EN-T9 input engine.
 */
class EnT9InputEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : ModeInputEngine() {

    private val strategy: EnglishComposeStrategy =
        EnT9ComposeStrategy(
            sessionProvider = { session },
            inputConnectionProvider = { inputConnectionProvider() }
        )

    private fun pending(): PendingCommitStrategy? = strategy as? PendingCommitStrategy

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
        val displayText = session.displayText(useT9Layout = true)

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
        if (pending()?.handleBackspaceInOwnBuffer(inputConnectionProvider()) == true) return

        val consumedBySession = session.backspace(useT9Layout = true)
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
        val p = pending() ?: return
        handleStrategyResult(p.flushPendingCommit())
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
