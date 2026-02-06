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

    // ---------------- Symbol panel state (source of truth) ----------------

    private var symbolCategory: ImeActions.SymbolCategory = ImeActions.SymbolCategory.COMMON
    private var symbolPage: Int = 0
    private var symbolLocked: Boolean = false

    // ---------------- Session access ----------------

    private fun session(): ComposingSession = sessions.current()

    private fun mainMode(): KeyboardMode {
        if (!::keyboardController.isInitialized) {
            return KeyboardMode(isChinese = true, useT9Layout = false)
        }
        return keyboardController.getMainMode()
    }

    // ---------------- Strategy layer ----------------

    private val cnQwertyStrategy: ComposeStrategy =
        CnQwertyComposeStrategy(
            sessionProvider = { sessions.cnQwerty },
            clearComposing = { clearComposing() }
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

    // ---------------- Wiring & UI sync ----------------

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
        // 需求：中文拼音 composing 不进入输入框（全键盘/T9 都一样）。
        // 英文保持原有 composing 行为（写入输入框），避免破坏英文预测/编辑体验。
        return !mode.isChinese
    }

    private fun formatChinesePreeditForUi(raw: String): String {
        // 本轮先做“强制小写”，并把可能存在的分隔空格替换成 `'`，让 UI 至少符合小写与分隔符风格。
        // “严格按音节切分并插入 `'`”会在下一步改到 ComposingSession/CN 策略层，确保音节边界正确。
        return raw
            .lowercase()
            .trim()
            .replace(' ', '\'')
    }

    fun refreshComposingView() {
        if (!::ui.isInitialized) return

        val mode = mainMode()
        val ic = inputConnection()
        val displayText = session().displayText(mode.useT9Layout)

        if (displayText.isNullOrEmpty()) {
            ui.setComposingPreview(null)
            // 清理 editor 侧 composing：即使中文模式我们也清一次，避免残留。
            ic?.setComposingText("", 0)
            return
        }

        val uiText = if (mode.isChinese) formatChinesePreeditForUi(displayText) else displayText
        ui.setComposingPreview(uiText)

        // 关键：中文模式不再把 composing 写入宿主输入框
        if (shouldWriteComposingToEditor(mode)) {
            ic?.setComposingText(displayText, 1)
        }
    }

    fun refreshCandidates() {
        if (!::candidateController.isInitialized) return
        candidateController.updateCandidates()
    }

    private fun afterSessionMutated() {
        refreshComposingView()
        refreshCandidates()
        syncEnglishPredictUi()
    }

    // ---------------- ImeActions ----------------

    override fun inputConnection(): InputConnection? = inputConnectionProvider()

    override fun handleComposingInput(text: String) {
        val result = currentStrategy().onComposingInput(text)
        handleStrategyResult(result)
    }

    override fun handleT9Input(digit: String) {
        val result = currentStrategy().onT9Input(digit)
        handleStrategyResult(result)
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        currentStrategy().onPinyinSidebarClick(pinyin)
        afterSessionMutated()
    }

    override fun commitText(text: String) {
        inputConnection()?.commitText(text, 1)

        session().clear()
        if (::ui.isInitialized) ui.setComposingPreview(null)
        inputConnection()?.setComposingText("", 0)

        refreshCandidates()
        syncEnglishPredictUi()
    }

    override fun clearComposing() {
        session().clear()
        if (::ui.isInitialized) ui.setComposingPreview(null)
        inputConnection()?.setComposingText("", 0)

        refreshCandidates()
        syncEnglishPredictUi()
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

        val isEnter = keyLabel.contains("⏎") || keyLabel.contains("\\n")
        if (isEnter) {
            val mode = mainMode()

            // 1) 中文全键盘：composing 时 Enter 提交小写字母（不换行），并清空 composing
            // 注意：这条功能是你要求保留的，这里保持原逻辑不动。
            if (mode.isChinese && !mode.useT9Layout && session().isComposing()) {
                val s = session()
                val textToCommit = (s.committedPrefix + s.qwertyInput.lowercase())
                if (textToCommit.isNotEmpty()) {
                    commitText(textToCommit)
                    // commitText 内部会清空 session + 预览 + composingText 并刷新候选
                    return
                }
            }

            // 2) 中文 T9：composing 且有候选时，Enter 提交候选第 1 个（不换行），并清空本次序列
            if (mode.isChinese && mode.useT9Layout && session().isComposing()) {
                if (::candidateController.isInitialized && candidateController.commitFirstCandidateOnEnter()) {
                    // commitCandidate 会按现有排序提交第 1 候选，并清空 composing
                    return
                }
            }

            // 3) 其他模式：交给策略（如果策略消费了则直接返回）
            if (currentStrategy().onEnter(inputConnection())) return

            // 4) 默认行为：发送系统 Enter（你的需求场景会在上面被拦截掉）
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

        // Default: Common + page 0.
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

    // ---------------- Symbol panel actions ----------------

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
        // Learn: record MRU for Common.
        SymbolPrefs.recordMruCommon(context, symbol)

        // Commit
        inputConnection()?.commitText(symbol, 1)

        session().clear()
        if (::ui.isInitialized) ui.setComposingPreview(null)
        inputConnection()?.setComposingText("", 0)

        refreshCandidates()
        syncEnglishPredictUi()

        if (!symbolLocked) {
            closeSymbolPanel()
        } else {
            syncSymbolPanelUi()
        }
    }

    // ---------------- English predict (per English main mode) ----------------

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

    // ---------------- Internal helpers ----------------

    private fun handleStrategyResult(result: StrategyResult) {
        when (result) {
            is StrategyResult.SessionMutated -> afterSessionMutated()

            is StrategyResult.DirectCommit -> {
                inputConnection()?.commitText(result.text, 1)

                session().clear()
                if (::ui.isInitialized) ui.setComposingPreview(null)
                inputConnection()?.setComposingText("", 0)

                refreshCandidates()
                syncEnglishPredictUi()
            }

            is StrategyResult.ComposingUpdate -> {
                val mode = mainMode()
                val uiText =
                    if (mode.isChinese) formatChinesePreeditForUi(result.composingText) else result.composingText

                if (::ui.isInitialized) ui.setComposingPreview(uiText)

                // 关键：中文 composing 不再写入宿主输入框
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
