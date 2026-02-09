package com.example.myapp.ime.router

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.cn.qwerty.CnQwertyComposeStrategy
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.PendingCommitStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.ui.ImeUi

class CnQwertyInputEngine(
    private val context: Context,
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : ImeInputEngine {

    private val strategy: ComposeStrategy =
        CnQwertyComposeStrategy(sessionProvider = { session })

    private val ENABLE_CN_PREVIEW_GUARD: Boolean = true
    private var inRefreshComposingView: Boolean = false

    private fun mainMode(): KeyboardMode = keyboardController.getMainMode()

    private fun shouldWriteComposingToEditor(mode: KeyboardMode): Boolean = !mode.isChinese

    private fun isDebuggableApp(): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun setComposingPreviewSafely(text: String?, from: String) {
        val mode = mainMode()
        if (ENABLE_CN_PREVIEW_GUARD && isDebuggableApp() && mode.isChinese && text != null && !inRefreshComposingView) {
            val msg = "CN composing preview updated outside refreshComposingView: from=$from, text=$text"
            Log.wtf("CnQwertyInputEngine", msg)
            throw AssertionError(msg)
        }
        ui.setComposingPreview(text)
    }

    private fun afterSessionMutated() {
        refreshCandidates()
        refreshComposingView()
        syncEnglishPredictUi()
    }

    private fun clearSessionAndEditorComposing() {
        session.clear()
        setComposingPreviewSafely(null, from = "clearSessionAndEditorComposing")
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
            setComposingPreviewSafely(null, from = "refreshComposingView-empty")
            ic?.setComposingText("", 0)
            return
        }

        val overrideText = candidateController.getComposingPreviewOverride()
        val rawUiText = overrideText ?: displayText

        inRefreshComposingView = true
        try {
            setComposingPreviewSafely(rawUiText, from = "refreshComposingView")
        } finally {
            inRefreshComposingView = false
        }

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
        beforeModeSwitch()
        strategy.onPinyinSidebarClick(pinyin)
        afterSessionMutated()
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

        val s = strategy
        if (s !is EnglishComposeStrategy || s.isPredicting()) {
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

    override fun getEnglishPredictEnabled(): Boolean = false
    override fun setEnglishPredict(enabled: Boolean) {
        @Suppress("UNUSED_PARAMETER")
        val ignored = enabled
    }

    override fun toggleEnglishPredict() {}

    override fun beforeModeSwitch() {
        val pending = strategy as? PendingCommitStrategy ?: return
        handleStrategyResult(pending.flushPendingCommit())
    }

    override fun afterModeSwitch() {
        // B: switching mode clears composing; actual clear is done by dispatcher on old engine.
        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun syncEnglishPredictUi() {
        // CN doesn't use English predict UI; keep no-op.
    }

    override fun handleStrategyResult(result: StrategyResult) {
        when (result) {
            is StrategyResult.SessionMutated -> afterSessionMutated()
            is StrategyResult.DirectCommit -> commitAndReset(result.text)
            is StrategyResult.ComposingUpdate -> {
                // Chinese: preview comes from candidate override; just refresh.
                refreshCandidates()
                refreshComposingView()
            }
            is StrategyResult.Noop -> {}
        }
    }
}
