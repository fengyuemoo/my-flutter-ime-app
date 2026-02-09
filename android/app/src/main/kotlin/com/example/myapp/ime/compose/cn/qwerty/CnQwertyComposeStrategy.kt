package com.example.myapp.ime.compose.cn.qwerty

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

class CnQwertyComposeStrategy(
    private val sessionProvider: () -> ComposingSession
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
        val s = session()

        // 关键：如果已有 committedPrefix（已选词但仍在 composing），这里不要 clear，避免前缀丢失
        // 当前 CN-QWERTY 正常情况下也不会出现 sidebar（sidebar 主要来自 CN-T9），保守处理即可。
        if (s.committedPrefix.isNotEmpty()) return

        s.clear()
        s.appendQwerty(pinyin.lowercase())
    }

    override fun onEnter(ic: InputConnection?): StrategyResult {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic

        val s = session()
        if (!s.isComposing()) return StrategyResult.Noop

        // 中文全键盘 composing 时，Enter 提交 raw input
        val textToCommit = (s.committedPrefix + s.qwertyInput.lowercase())
        return if (textToCommit.isNotEmpty()) {
            StrategyResult.DirectCommit(textToCommit)
        } else {
            StrategyResult.Noop
        }
    }
}

/**
 * CN-QWERTY input engine: owns session mutation + refresh + commit chain for this mode only.
 */
class CnQwertyInputEngine(
    private val context: Context,
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?
) : ModeInputEngine() {

    private val strategy: ComposeStrategy = CnQwertyComposeStrategy(sessionProvider = { session })

    private val ENABLE_CN_PREVIEW_GUARD: Boolean = true
    private var inRefreshComposingView: Boolean = false

    private fun isDebuggableApp(): Boolean {
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun setComposingPreviewSafely(text: String?, from: String) {
        if (ENABLE_CN_PREVIEW_GUARD && isDebuggableApp() && text != null && !inRefreshComposingView) {
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
        val displayText = session.displayText(useT9Layout = false)

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
                // CN preview comes from handler override; just refresh.
                refreshCandidates()
                refreshComposingView()
            }
            is StrategyResult.Noop -> {}
        }
    }
}
