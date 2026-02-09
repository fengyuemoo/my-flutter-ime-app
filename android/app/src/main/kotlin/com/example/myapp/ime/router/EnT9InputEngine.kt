package com.example.myapp.ime.router

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.PendingCommitStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.compose.en.t9.EnT9ComposeStrategy
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.ui.ImeUi

class EnT9InputEngine(
    private val context: Context,
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : ImeInputEngine {

    private val strategy: EnglishComposeStrategy =
        EnT9ComposeStrategy(
            sessionProvider = { session },
            inputConnectionProvider = { inputConnectionProvider() }
        )

    private fun mainMode(): KeyboardMode = keyboardController.getMainMode()
    private fun shouldWriteComposingToEditor(mode: KeyboardMode): Boolean = !mode.isChinese

    private fun afterSessionMutated() {
        refreshCandidates()
        refreshComposingView()
        syncEnglishPredictUi()
    }

    private fun clearSessionAndEditorComposing() {
        session.clear()
        ui.setComposingPreview(null)
        inputConnectionProvider()?.setComposingText("", 0)
    }

    private fun afterCommitOrClear() {
        refreshCandidates()
        syncEnglishPredictUi()
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
        val mode = mainMode()
        val ic = inputConnectionProvider()
        val displayText = session.displayText(mode.useT9Layout)

        if (displayText.isNullOrEmpty()) {
            ui.setComposingPreview(null)
            ic?.setComposingText("", 0)
            return
        }

        ui.setComposingPreview(displayText)

        if (shouldWriteComposingToEditor(mode)) {
            ic?.setComposingText(displayText, 1)
        }
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
        val pending = strategy as? PendingCommitStrategy
        if (pending?.handleBackspaceInOwnBuffer(inputConnectionProvider()) == true) return

        val consumedBySession = session.backspace(mainMode().useT9Layout)
        if (consumedBySession) {
            if (!session.isComposing()) clearComposing() else afterSessionMutated()
            return
        }

        val ic = inputConnectionProvider() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    override fun handleSpaceKey() {
        beforeModeSwitch()

        if (strategy.isPredicting()) {
            candidateController.handleSpaceKey()
        } else {
            inputConnectionProvider()?.commitText(" ", 1)
        }
    }

    override fun handleEnterFallback(ic: InputConnection?) {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic
        val realIc = inputConnectionProvider() ?: return
        realIc.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        realIc.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    override fun getEnglishPredictEnabled(): Boolean = strategy.getEnglishPredictEnabled()

    override fun setEnglishPredict(enabled: Boolean) {
        strategy.setEnglishPredictEnabled(enabled)
        afterSessionMutated()
    }

    override fun toggleEnglishPredict() {
        strategy.toggleEnglishPredict()
        afterSessionMutated()
    }

    override fun beforeModeSwitch() {
        val pending = strategy as? PendingCommitStrategy ?: return
        handleStrategyResult(pending.flushPendingCommit())
    }

    override fun afterModeSwitch() {
        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun syncEnglishPredictUi() {
        keyboardController.updateEnglishPredictUi(getEnglishPredictEnabled())
    }

    override fun handleStrategyResult(result: StrategyResult) {
        when (result) {
            is StrategyResult.SessionMutated -> afterSessionMutated()
            is StrategyResult.DirectCommit -> commitAndReset(result.text)
            is StrategyResult.ComposingUpdate -> {
                val mode = mainMode()
                ui.setComposingPreview(result.composingText)
                if (shouldWriteComposingToEditor(mode)) {
                    inputConnectionProvider()?.setComposingText(result.composingText, 1)
                }
                refreshCandidates()
            }
            is StrategyResult.Noop -> {}
        }
    }
}
