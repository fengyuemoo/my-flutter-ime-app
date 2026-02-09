package com.example.myapp.ime.router

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
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

/**
 * Centralized debug flags for IME refactor assertions/guards.
 *
 * Note:
 * - Do NOT depend on BuildConfig here (some builds may disable BuildConfig generation).
 * - All guards are effectively OFF in release because debuggable flag is false.
 */
internal object DebugFlags {
    /** Guard: CN composing preview can only be updated inside refreshComposingView(). */
    const val CN_PREVIEW_GUARD: Boolean = true

    /** Assert: after mode switch, old+new sessions must be cleared (B semantic). */
    const val MODE_SWITCH_ASSERT: Boolean = true

    /** Assert: after CN clearSessionAndEditorComposing(), session must not be composing. */
    const val CN_CLEAR_ASSERT: Boolean = true

    fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

/**
 * Mode input engine abstraction.
 *
 * Implementations live in 4 mode files (CN/EN × Qwerty/T9) and bind to fixed sessions.
 */
abstract class ModeInputEngine {
    abstract fun refreshCandidates()
    abstract fun refreshComposingView()

    abstract fun clearComposing()

    abstract fun handleComposingInput(text: String)
    abstract fun handleT9Input(digit: String)
    abstract fun onPinyinSidebarClick(pinyin: String)
    abstract fun handleBackspace()

    abstract fun handleSpaceKey()

    /**
     * @return true if consumed (handled), false to fallback to editor Enter key event.
     */
    abstract fun handleEnter(ic: InputConnection?): Boolean

    abstract fun beforeModeSwitch()
    abstract fun afterModeSwitch()

    abstract fun getEnglishPredictEnabled(): Boolean
    abstract fun setEnglishPredict(enabled: Boolean)
    fun toggleEnglishPredict() = setEnglishPredict(!getEnglishPredictEnabled())

    abstract fun syncEnglishPredictUi()
}

/**
 * Shared CN engine implementation (CN-Qwerty and CN-T9).
 *
 * Notes:
 * - CN composing preview should only be updated inside refreshComposingView().
 * - CN does not write composing text into editor (except clearing via setComposingText("", 0)).
 */
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
    private var inRefreshComposingView: Boolean = false

    private fun setComposingPreviewSafely(text: String?, from: String) {
        if (
            DebugFlags.CN_PREVIEW_GUARD &&
            debuggableApp &&
            text != null &&
            !inRefreshComposingView
        ) {
            val sessionDisplay = session.displayText(useT9Layout = useT9Layout)
            val msg =
                "CN composing preview must be updated inside refreshComposingView(): " +
                    "engine=$logTag, from=$from, text=$text, sessionDisplay=$sessionDisplay, useT9Layout=$useT9Layout"

            Log.e(logTag, msg)
            check(false) { msg }
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

        if (DebugFlags.CN_CLEAR_ASSERT && debuggableApp) {
            check(!session.isComposing()) {
                "CN session must be cleared after clearSessionAndEditorComposing()"
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
        val displayText = session.displayText(useT9Layout = useT9Layout)

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

/**
 * Shared EN engine implementation (EN-Qwerty and EN-T9).
 *
 * Notes:
 * - EN writes composing into editor (setComposingText) for preview.
 * - If strategy implements PendingCommitStrategy (EN-T9 multi-tap), base handles flush/backspace-in-buffer.
 */
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
        val displayText = session.displayText(useT9Layout = useT9Layout)

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
                ui.setComposingPreview(result.composingText)
                inputConnectionProvider()?.setComposingText(result.composingText, 1)
                refreshCandidates()
            }
            is StrategyResult.Noop -> {}
        }
    }
}
