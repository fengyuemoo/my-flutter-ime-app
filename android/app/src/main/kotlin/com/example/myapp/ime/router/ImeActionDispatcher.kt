package com.example.myapp.ime.router

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.PendingCommitStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.compose.cn.qwerty.CnQwertyComposeStrategy
import com.example.myapp.ime.compose.cn.t9.CnT9ComposeStrategy
import com.example.myapp.ime.compose.en.qwerty.EnQwertyComposeStrategy
import com.example.myapp.ime.compose.en.t9.EnT9ComposeStrategy
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.keyboard.model.PanelState
import com.example.myapp.ime.prefs.SymbolPrefs
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.keyboard.core.PanelType

class ImeActionDispatcher(
    private val context: Context,
    private val sessions: ComposingSessionHub,
    private val inputConnectionProvider: () -> InputConnection?
) : ImeActions {

    private lateinit var ui: ImeUi
    private lateinit var keyboardController: KeyboardController
    private lateinit var candidateController: CandidateController

    private var symbolCategory: ImeActions.SymbolCategory = ImeActions.SymbolCategory.COMMON
    private var symbolPage: Int = 0
    private var symbolLocked: Boolean = false

    private fun session(): ComposingSession = sessions.current()

    private fun mainMode(): KeyboardMode {
        if (!::keyboardController.isInitialized) {
            return KeyboardMode(isChinese = true, useT9Layout = false)
        }
        return keyboardController.getMainMode()
    }

    private val cnQwertyStrategy: ComposeStrategy =
        CnQwertyComposeStrategy(
            sessionProvider = { sessions.cnQwerty }
        )

    private val cnT9Strategy: ComposeStrategy =
        CnT9ComposeStrategy(
            sessionProvider = { sessions.cnT9 }
        )

    private val enQwertyStrategy: ComposeStrategy =
        EnQwertyComposeStrategy(
            sessionProvider = { sessions.enQwerty },
            inputConnectionProvider = { inputConnectionProvider() }
        )

    private val enT9Strategy: ComposeStrategy =
        EnT9ComposeStrategy(
            sessionProvider = { sessions.enT9 },
            inputConnectionProvider = { inputConnectionProvider() }
        )

    private fun currentStrategy(): ComposeStrategy {
        val mode = mainMode()
        return when {
            mode.isChinese && !mode.useT9Layout -> cnQwertyStrategy
            mode.isChinese && mode.useT9Layout -> cnT9Strategy
            !mode.isChinese && !mode.useT9Layout -> enQwertyStrategy
            else -> enT9Strategy
        }
    }

    private fun currentEnglishStrategy(): EnglishComposeStrategy? =
        currentStrategy() as? EnglishComposeStrategy

    fun attach(
        ui: ImeUi,
        keyboardController: KeyboardController,
        candidateController: CandidateController,
        onToolbarUpdate: (() -> Unit)? = null
    ) {
        this.ui = ui
        this.keyboardController = keyboardController
        this.candidateController = candidateController

        @Suppress("UNUSED_PARAMETER")
        val ignored = onToolbarUpdate

        keyboardController.onKeyboardChanged = {
            syncEnglishPredictUi()
            syncSymbolPanelUi()
        }

        syncEnglishPredictUi()
        syncSymbolPanelUi()
    }

    private fun syncEnglishPredictUi() {
        if (!::keyboardController.isInitialized) return
        keyboardController.updateEnglishPredictUi(getEnglishPredictEnabled())
    }

    private fun syncSymbolPanelUi() {
        if (!::keyboardController.isInitialized) return
        val panel = keyboardController.getPanelState()
        if (panel is PanelState.Open && panel.type == PanelType.SYMBOL) {
            keyboardController.updateSymbolPanelUi(
                category = symbolCategory,
                page = symbolPage,
                locked = symbolLocked
            )
        }
    }

    private fun shouldWriteComposingToEditor(mode: KeyboardMode): Boolean {
        return !mode.isChinese
    }

    private fun formatChinesePreeditForUi(raw: String): String {
        return raw
            .lowercase()
            .trim()
            .replace(' ', ''')
    }

    fun refreshComposingView() {
        if (!::ui.isInitialized) return

        val mode = mainMode()
        val ic = inputConnection()

        val displayText = session().displayText(mode.useT9Layout)

        if (displayText.isNullOrEmpty()) {
            ui.setComposingPreview(null)
            ic?.setComposingText("", 0)
            return
        }

        val overrideText =
            if (mode.isChinese && ::candidateController.isInitialized) {
                candidateController.getComposingPreviewOverride()
            } else {
                null
            }

        val rawUiText = overrideText ?: displayText

        val uiText = if (mode.isChinese) formatChinesePreeditForUi(rawUiText) else rawUiText
        ui.setComposingPreview(uiText)

        if (shouldWriteComposingToEditor(mode)) {
            ic?.setComposingText(displayText, 1)
        }
    }

    fun refreshCandidates() {
        if (!::candidateController.isInitialized) return
        candidateController.updateCandidates()
    }

    private fun afterSessionMutated() {
        refreshCandidates()
        refreshComposingView()
        syncEnglishPredictUi()
    }

    override fun inputConnection(): InputConnection? = inputConnectionProvider()

    private fun clearSessionAndEditorComposing() {
        session().clear()
        if (::ui.isInitialized) ui.setComposingPreview(null)
        inputConnection()?.setComposingText("", 0)
    }

    private fun afterCommitOrClear() {
        refreshCandidates()
        syncEnglishPredictUi()
    }

    private fun commitAndReset(text: String) {
        inputConnection()?.commitText(text, 1)
        clearSessionAndEditorComposing()
        afterCommitOrClear()
    }

    private fun clearAndReset() {
        clearSessionAndEditorComposing()
        afterCommitOrClear()
    }

    override fun handleComposingInput(text: String) {
        val result = currentStrategy().onComposingInput(text)
        handleStrategyResult(result)
    }

    override fun handleT9Input(digit: String) {
        val result = currentStrategy().onT9Input(digit)
        handleStrategyResult(result)
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        // 只允许 CN-T9 响应 sidebar，避免其它模式误触导致 session 状态被改坏
        val mode = mainMode()
        if (!(mode.isChinese && mode.useT9Layout)) return

        currentStrategy().onPinyinSidebarClick(pinyin)
        afterSessionMutated()
    }

    override fun commitText(text: String) {
        commitAndReset(text)
    }

    override fun clearComposing() {
        clearAndReset()
    }

    override fun handleSpaceKey() {
        beforeModeSwitch()

        val strategy = currentStrategy()
        if (strategy !is EnglishComposeStrategy || strategy.isPredicting()) {
            if (::candidateController.isInitialized) {
                candidateController.handleSpaceKey()
            } else {
                inputConnection()?.commitText(" ", 1)
            }
        } else {
            inputConnection()?.commitText(" ", 1)
        }
    }

    override fun handleBackspace() {
        val strategy = currentStrategy()
        if ((strategy as? PendingCommitStrategy)?.handleBackspaceInOwnBuffer(inputConnection()) == true) {
            return
        }

        val consumedBySession = session().backspace(mainMode().useT9Layout)
        if (consumedBySession) {
            if (!session().isComposing()) {
                clearComposing()
            } else {
                afterSessionMutated()
            }
            return
        }

        val ic = inputConnection() ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    override fun handleSpecialKey(keyLabel: String) {
        beforeModeSwitch()

        val isEnter = keyLabel.contains("⏎") || keyLabel.contains("\n")
        if (isEnter) {
            val mode = mainMode()

            // CN-T9: commit handler-provided preview letters on Enter
            if (mode.isChinese && mode.useT9Layout && ::candidateController.isInitialized) {
                val enterCommit = candidateController.getEnterCommitTextOverride()
                if (!enterCommit.isNullOrEmpty()) {
                    commitAndReset(enterCommit)
                    return
                }
            }

            val result = currentStrategy().onEnter(inputConnection())
            if (result !is StrategyResult.Noop) {
                handleStrategyResult(result)
                return
            }

            val ic = inputConnection() ?: return
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return
        }

        if (keyLabel == "SPACE") {
            handleSpaceKey()
        }
    }

    override fun switchToEnglishMode() {
        beforeModeSwitch()
        clearComposing()
        if (!::keyboardController.isInitialized) return
        keyboardController.setLanguage(false)
        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun switchToChineseMode() {
        beforeModeSwitch()
        clearComposing()
        if (!::keyboardController.isInitialized) return
        keyboardController.setLanguage(true)
        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun switchToNumericMode() {
        beforeModeSwitch()
        if (!::keyboardController.isInitialized) return
        keyboardController.openPanel(PanelType.NUMERIC)
        syncEnglishPredictUi()
    }

    override fun openSymbolPanel() {
        beforeModeSwitch()
        if (!::keyboardController.isInitialized) return

        symbolCategory = ImeActions.SymbolCategory.COMMON
        symbolPage = 0

        keyboardController.openPanel(PanelType.SYMBOL)
        syncEnglishPredictUi()
        syncSymbolPanelUi()
    }

    override fun closeSymbolPanel() {
        beforeModeSwitch()
        if (!::keyboardController.isInitialized) return
        keyboardController.closePanel()
        syncEnglishPredictUi()
    }

    override fun exitNumericMode() {
        closeSymbolPanel()
    }

    override fun setSymbolCategory(category: ImeActions.SymbolCategory) {
        symbolCategory = category
        symbolPage = 0
        syncSymbolPanelUi()
    }

    override fun symbolPageUp() {
        symbolPage = maxOf(0, symbolPage - 1)
        syncSymbolPanelUi()
    }

    override fun symbolPageDown() {
        symbolPage += 1
        syncSymbolPanelUi()
    }

    override fun toggleSymbolLock() {
        symbolLocked = !symbolLocked
        syncSymbolPanelUi()
    }

    override fun commitSymbolFromPanel(symbol: String) {
        SymbolPrefs.recordMruCommon(context, symbol)

        commitAndReset(symbol)

        if (!symbolLocked) {
            closeSymbolPanel()
        } else {
            syncSymbolPanelUi()
        }
    }

    override fun getEnglishPredictEnabled(): Boolean {
        return currentEnglishStrategy()?.getEnglishPredictEnabled() ?: false
    }

    override fun setEnglishPredict(enabled: Boolean) {
        currentEnglishStrategy()?.setEnglishPredictEnabled(enabled)
        afterSessionMutated()
    }



    override fun toggleEnglishPredict() {
        currentEnglishStrategy()?.toggleEnglishPredict()
        afterSessionMutated()
    }

    private fun handleStrategyResult(result: StrategyResult) {
        when (result) {
            is StrategyResult.SessionMutated -> afterSessionMutated()

            is StrategyResult.DirectCommit -> {
                commitText(result.text)
            }

            is StrategyResult.ComposingUpdate -> {
                val mode = mainMode()

                // Chinese modes: composing preview must come from handler Output (CandidateController override),
                // so never set UI preview directly here.
                if (mode.isChinese) {
                    refreshCandidates()
                    refreshComposingView()
                    return
                }

                // Non-Chinese: keep existing behavior.
                val uiText = result.composingText
                if (::ui.isInitialized) ui.setComposingPreview(uiText)

                if (shouldWriteComposingToEditor(mode)) {
                    inputConnection()?.setComposingText(result.composingText, 1)
                }

                refreshCandidates()
            }

            is StrategyResult.Noop -> {
                /* Do nothing */
            }
        }
    }

    private fun beforeModeSwitch() {
        val strategy = currentStrategy()
        if (strategy is PendingCommitStrategy) {
            val result = strategy.flushPendingCommit()
            handleStrategyResult(result)
        }
    }
}
