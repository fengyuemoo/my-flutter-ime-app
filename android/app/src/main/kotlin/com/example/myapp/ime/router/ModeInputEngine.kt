package com.example.myapp.ime.router

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.PendingCommitStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.ui.ImeUi

internal object DebugFlags {
    const val MODE_SWITCH_ASSERT: Boolean = true
    const val CN_CLEAR_ASSERT: Boolean = true

    fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

abstract class ModeInputEngine {
    abstract fun refreshCandidates()
    abstract fun refreshComposingView()

    abstract fun clearComposing()

    abstract fun handleComposingInput(text: String)
    abstract fun handleT9Input(digit: String)
    abstract fun onPinyinSidebarClick(pinyin: String)
    abstract fun handleBackspace()

    abstract fun handleSpaceKey()

    abstract fun handleEnter(ic: InputConnection?): Boolean

    abstract fun beforeModeSwitch()
    abstract fun afterModeSwitch()

    abstract fun getEnglishPredictEnabled(): Boolean
    abstract fun setEnglishPredict(enabled: Boolean)
    fun toggleEnglishPredict() = setEnglishPredict(!getEnglishPredictEnabled())

    abstract fun syncEnglishPredictUi()
}

abstract class CnBaseInputEngine(
    private val context: Context,
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?,
    private val useT9Layout: Boolean,
    private val logTag: String,
    private val strategy: ComposeStrategy
) : ModeInputEngine() {

    private val debuggableApp: Boolean = DebugFlags.isDebuggable(context)

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

        if (DebugFlags.CN_CLEAR_ASSERT && debuggableApp) {
            check(!session.isComposing()) {
                "CN session must be cleared after clearSessionAndEditorComposing(): engine=$logTag"
            }
        }
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
        val previewText = candidateController.resolveComposingPreviewText()

        if (previewText.isNullOrEmpty()) {
            ui.setComposingPreview(null)
            ic?.setComposingText("", 0)
            return
        }

        ui.setComposingPreview(previewText)

        // CN: do not write composing preview into editor.
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
        strategy.onPinyinSidebarClick(pinyin)
        afterSessionMutated()
    }

    override fun handleBackspace() {
        val consumedBySession = session.backspace(useT9Layout = useT9Layout)
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
        candidateController.handleSpaceKey()
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
        // No pending buffer in CN modes.
    }

    override fun afterModeSwitch() {
        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun getEnglishPredictEnabled(): Boolean = false

    override fun setEnglishPredict(enabled: Boolean) {
        @Suppress("UNUSED_PARAMETER")
        val ignored = enabled
        syncEnglishPredictUi()
    }

    override fun syncEnglishPredictUi() {
        keyboardController.updateEnglishPredictUi(false)
    }

    private fun handleStrategyResult(result: StrategyResult) {
        when (result) {
            is StrategyResult.SessionMutated -> afterSessionMutated()
            is StrategyResult.DirectCommit -> commitAndReset(result.text)
            is StrategyResult.ComposingUpdate -> {
                refreshCandidates()
                refreshComposingView()
            }
            is StrategyResult.Noop -> {}
        }
    }
}

abstract class EnBaseInputEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?,
    private val useT9Layout: Boolean,
    private val strategy: EnglishComposeStrategy
) : ModeInputEngine() {

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
        val previewText = candidateController.resolveComposingPreviewText()

        if (previewText.isNullOrEmpty()) {
            ui.setComposingPreview(null)
            ic?.setComposingText("", 0)
            return
        }

        ui.setComposingPreview(previewText)
        ic?.setComposingText(previewText, 1)
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

        val consumedBySession = session.backspace(useT9Layout = useT9Layout)
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
        val result = p.flushPendingCommit()
        if (result !is StrategyResult.Noop) {
            handleStrategyResult(result)
        }
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
                refreshComposingView()
                refreshCandidates()
            }
            is StrategyResult.Noop -> {}
        }
    }
}
