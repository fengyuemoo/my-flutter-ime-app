package com.example.myapp.ime.router

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.cn.qwerty.CnQwertyInputEngine
import com.example.myapp.ime.compose.cn.t9.CnT9InputEngine
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.compose.en.qwerty.EnQwertyInputEngine
import com.example.myapp.ime.compose.en.t9.EnT9InputEngine
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.keyboard.model.PanelState
import com.example.myapp.ime.prefs.SymbolPrefs
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.keyboard.core.PanelType

/**
 * Mode input engine abstraction (4 engines live in 4 mode files).
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

    private lateinit var cnQwertyEngine: ModeInputEngine
    private lateinit var cnT9Engine: ModeInputEngine
    private lateinit var enQwertyEngine: ModeInputEngine
    private lateinit var enT9Engine: ModeInputEngine

    private fun enginesReady(): Boolean =
        ::ui.isInitialized && ::keyboardController.isInitialized && ::candidateController.isInitialized &&
            ::cnQwertyEngine.isInitialized

    private fun mainMode(): KeyboardMode {
        if (!::keyboardController.isInitialized) {
            return KeyboardMode(isChinese = true, useT9Layout = false)
        }
        return keyboardController.getMainMode()
    }

    private enum class ModeKey {
        CN_QWERTY, CN_T9, EN_QWERTY, EN_T9
    }

    private fun currentModeKey(): ModeKey {
        val mode = mainMode()
        return when {
            mode.isChinese && !mode.useT9Layout -> ModeKey.CN_QWERTY
            mode.isChinese && mode.useT9Layout -> ModeKey.CN_T9
            !mode.isChinese && !mode.useT9Layout -> ModeKey.EN_QWERTY
            else -> ModeKey.EN_T9
        }
    }

    private fun currentEngineOrNull(): ModeInputEngine? {
        if (!enginesReady()) return null
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine
            ModeKey.CN_T9 -> cnT9Engine
            ModeKey.EN_QWERTY -> enQwertyEngine
            ModeKey.EN_T9 -> enT9Engine
        }
    }

    private fun currentEngine(): ModeInputEngine = requireNotNull(currentEngineOrNull())

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

        // Build 4 engines (each binds its fixed session).
        cnQwertyEngine = CnQwertyInputEngine(
            context = context,
            ui = ui,
            keyboardController = keyboardController,
            candidateController = candidateController,
            session = sessions.cnQwerty,
            inputConnectionProvider = { inputConnectionProvider() }
        )
        cnT9Engine = CnT9InputEngine(
            context = context,
            ui = ui,
            keyboardController = keyboardController,
            candidateController = candidateController,
            session = sessions.cnT9,
            inputConnectionProvider = { inputConnectionProvider() }
        )
        enQwertyEngine = EnQwertyInputEngine(
            ui = ui,
            keyboardController = keyboardController,
            candidateController = candidateController,
            session = sessions.enQwerty,
            inputConnectionProvider = { inputConnectionProvider() }
        )
        enT9Engine = EnT9InputEngine(
            ui = ui,
            keyboardController = keyboardController,
            candidateController = candidateController,
            session = sessions.enT9,
            inputConnectionProvider = { inputConnectionProvider() }
        )

        keyboardController.onKeyboardChanged = {
            currentEngineOrNull()?.syncEnglishPredictUi()
            syncSymbolPanelUi()
        }

        currentEngineOrNull()?.syncEnglishPredictUi()
        syncSymbolPanelUi()
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

    // --- External refresh API ---

    fun refreshComposingView() {
        currentEngineOrNull()?.refreshComposingView()
    }

    fun refreshCandidates() {
        currentEngineOrNull()?.refreshCandidates()
    }

    // --- ImeActions ---

    override fun inputConnection(): InputConnection? = inputConnectionProvider()

    override fun clearComposing() {
        currentEngineOrNull()?.clearComposing()
    }

    override fun handleComposingInput(text: String) {
        currentEngineOrNull()?.handleComposingInput(text)
    }

    override fun handleT9Input(digit: String) {
        currentEngineOrNull()?.handleT9Input(digit)
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        val engine = currentEngineOrNull() ?: return
        engine.beforeModeSwitch()
        engine.onPinyinSidebarClick(pinyin)
    }

    override fun commitText(text: String) {
        inputConnectionProvider()?.commitText(text, 1)
        currentEngineOrNull()?.clearComposing()
    }

    override fun handleSpaceKey() {
        val engine = currentEngineOrNull() ?: run {
            inputConnectionProvider()?.commitText(" ", 1)
            return
        }
        engine.beforeModeSwitch()
        engine.handleSpaceKey()
    }

    override fun handleBackspace() {
        currentEngineOrNull()?.handleBackspace() ?: run {
            val ic = inputConnectionProvider() ?: return
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    override fun handleSpecialKey(keyLabel: String) {
        val engine = currentEngineOrNull() ?: return

        engine.beforeModeSwitch()

        val isEnter = keyLabel.contains("⏎") || keyLabel.contains("\n")
        if (isEnter) {
            val consumed = engine.handleEnter(inputConnectionProvider())
            if (!consumed) {
                val ic = inputConnectionProvider() ?: return
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            return
        }

        if (keyLabel == "SPACE") {
            handleSpaceKey()
        }
    }

    override fun switchToEnglishMode() {
        val oldEngine = currentEngineOrNull()
        oldEngine?.beforeModeSwitch()
        // B: switching clears composing (clear old + clear new).
        oldEngine?.clearComposing()

        if (!::keyboardController.isInitialized) return
        keyboardController.setLanguage(false)

        val newEngine = currentEngineOrNull()
        newEngine?.clearComposing()
        newEngine?.afterModeSwitch()

        syncSymbolPanelUi()
    }

    override fun switchToChineseMode() {
        val oldEngine = currentEngineOrNull()
        oldEngine?.beforeModeSwitch()
        // B: switching clears composing (clear old + clear new).
        oldEngine?.clearComposing()

        if (!::keyboardController.isInitialized) return
        keyboardController.setLanguage(true)

        val newEngine = currentEngineOrNull()
        newEngine?.clearComposing()
        newEngine?.afterModeSwitch()

        syncSymbolPanelUi()
    }

    override fun switchToNumericMode() {
        val engine = currentEngineOrNull()
        engine?.beforeModeSwitch()

        if (!::keyboardController.isInitialized) return
        keyboardController.openPanel(PanelType.NUMERIC)

        engine?.syncEnglishPredictUi()
    }

    override fun openSymbolPanel() {
        val engine = currentEngineOrNull()
        engine?.beforeModeSwitch()

        if (!::keyboardController.isInitialized) return

        symbolCategory = ImeActions.SymbolCategory.COMMON
        symbolPage = 0

        keyboardController.openPanel(PanelType.SYMBOL)

        engine?.syncEnglishPredictUi()
        syncSymbolPanelUi()
    }

    override fun closeSymbolPanel() {
        val engine = currentEngineOrNull()
        engine?.beforeModeSwitch()

        if (!::keyboardController.isInitialized) return
        keyboardController.closePanel()

        engine?.syncEnglishPredictUi()
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

        commitText(symbol)

        if (!symbolLocked) {
            closeSymbolPanel()
        } else {
            syncSymbolPanelUi()
        }
    }

    override fun getEnglishPredictEnabled(): Boolean {
        return currentEngineOrNull()?.getEnglishPredictEnabled() ?: false
    }

    override fun setEnglishPredict(enabled: Boolean) {
        currentEngineOrNull()?.setEnglishPredict(enabled)
    }

    override fun toggleEnglishPredict() {
        currentEngineOrNull()?.toggleEnglishPredict()
    }
}
