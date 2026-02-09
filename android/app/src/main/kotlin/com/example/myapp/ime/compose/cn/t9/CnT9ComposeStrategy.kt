package com.example.myapp.ime.compose.cn.t9

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.ModeInputEngine
import com.example.myapp.ime.ui.ImeUi

class CnT9ComposeStrategy(
    private val sessionProvider: () -> ComposingSession,
    private val enterCommitProvider: () -> String?
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

    private fun pinyinToT9Code(pinyin: String): String {
        val s = pinyin.lowercase()
        val sb = StringBuilder()
        for (ch in s) {
            val d = when (ch) {
                'a', 'b', 'c' -> '2'
                'd', 'e', 'f' -> '3'
                'g', 'h', 'i' -> '4'
                'j', 'k', 'l' -> '5'
                'm', 'n', 'o' -> '6'
                'p', 'q', 'r', 's' -> '7'
                't', 'u', 'v', 'ü' -> '8'
                'w', 'x', 'y', 'z' -> '9'
                else -> null
            }
            if (d != null) sb.append(d)
        }
        return sb.toString()
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        val code = pinyinToT9Code(pinyin)
        session().onPinyinSidebarClick(pinyin.lowercase(), code)
    }

    override fun onEnter(ic: InputConnection?): StrategyResult {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic

        // CN-T9: commit handler-provided preview letters on Enter (provided by CandidateController)
        val commit = enterCommitProvider()
        return if (!commit.isNullOrEmpty()) {
            StrategyResult.DirectCommit(commit)
        } else {
            StrategyResult.Noop
        }
    }
}

/**
 * CN-T9 input engine.
 */
class CnT9InputEngine(
    private val context: Context,
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : ModeInputEngine() {

    private val strategy: ComposeStrategy =
        CnT9ComposeStrategy(
            sessionProvider = { session },
            enterCommitProvider = { candidateController.getEnterCommitTextOverride() }
        )

    private val ENABLE_CN_PREVIEW_GUARD: Boolean = true
    private var inRefreshComposingView: Boolean = false

    private fun isDebuggableApp(): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun setComposingPreviewSafely(text: String?, from: String) {
        if (ENABLE_CN_PREVIEW_GUARD && isDebuggableApp() && text != null && !inRefreshComposingView) {
            val msg = "CN composing preview updated outside refreshComposingView: from=$from, text=$text"
            Log.wtf("CnT9InputEngine", msg)
            throw AssertionError(msg)
        }
        ui.setComposingPreview(text)
    }

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
        setComposingPreviewSafely(null, from = "clearSessionAndEditorComposing")
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

        // CN: do not write composing to editor.
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
        // No pending buffer in this mode.
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
