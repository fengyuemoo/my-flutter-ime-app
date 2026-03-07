package com.example.myapp.ime.router

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.cn.qwerty.CnQwertyInputEngine
import com.example.myapp.ime.compose.cn.t9.CnT9InputEngine
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.compose.en.qwerty.EnQwertyInputEngine
import com.example.myapp.ime.compose.en.t9.EnT9InputEngine
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

    private val debuggableApp: Boolean = DebugFlags.isDebuggable(context)

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

    private var lastKnownMainMode: KeyboardMode? = null

    private var inHandleMainModeChanged: Boolean = false
    private var deferredMainMode: KeyboardMode? = null

    private val prefs by lazy { context.getSharedPreferences("ime_settings", Context.MODE_PRIVATE) }

    companion object {
        private const val KEY_EN_QWERTY_PREDICT_ENABLED = "en_qwerty_predict_enabled"
    }

    private fun loadEnQwertyPredictPref(): Boolean =
        prefs.getBoolean(KEY_EN_QWERTY_PREDICT_ENABLED, true)

    private fun saveEnQwertyPredictPref(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EN_QWERTY_PREDICT_ENABLED, enabled).apply()
    }

    private fun sessionByMode(mode: KeyboardMode): ComposingSession {
        return when {
            mode.isChinese && !mode.useT9Layout -> sessions.cnQwerty
            mode.isChinese && mode.useT9Layout -> sessions.cnT9
            !mode.isChinese && !mode.useT9Layout -> sessions.enQwerty
            else -> sessions.enT9
        }
    }

    private fun assertSessionCleared(mode: KeyboardMode, from: String) {
        if (!DebugFlags.MODE_SWITCH_ASSERT || !debuggableApp) return

        val s = sessionByMode(mode)
        val display = s.displayText(mode.useT9Layout)

        if (s.isComposing() || !display.isNullOrEmpty()) {
            val msg =
                "Mode switch must clear composing: from=$from, mode=$mode, isComposing=${s.isComposing()}, display=$display"
            throw AssertionError(msg)
        }
    }

    private fun enginesReady(): Boolean =
        ::ui.isInitialized && ::keyboardController.isInitialized && ::candidateController.isInitialized &&
            ::cnQwertyEngine.isInitialized

    private fun mainMode(): KeyboardMode {
        if (!::keyboardController.isInitialized) {
            return KeyboardMode(isChinese = true, useT9Layout = false)
        }
        return keyboardController.getMainMode()
    }

    private fun engineByModeOrNull(mode: KeyboardMode): ModeInputEngine? {
        if (!enginesReady()) return null
        return when {
            mode.isChinese && !mode.useT9Layout -> cnQwertyEngine
            mode.isChinese && mode.useT9Layout -> cnT9Engine
            !mode.isChinese && !mode.useT9Layout -> enQwertyEngine
            else -> enT9Engine
        }
    }

    private fun currentEngineOrNull(): ModeInputEngine? = engineByModeOrNull(mainMode())

    private fun applyResolvedComposingPreview() {
        if (!::ui.isInitialized || !::candidateController.isInitialized) return
        ui.setComposingPreview(candidateController.resolveComposingPreviewText())
    }

    private fun applyEnQwertyPredictPrefIfNeeded(mode: KeyboardMode) {
        if (!mode.isChinese && !mode.useT9Layout) {
            engineByModeOrNull(mode)?.setEnglishPredict(loadEnQwertyPredictPref())
        }
    }

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

        lastKnownMainMode = keyboardController.getMainMode()

        val oldOnKeyboardChanged = keyboardController.onKeyboardChanged
        keyboardController.onKeyboardChanged = {
            oldOnKeyboardChanged?.invoke()
            currentEngineOrNull()?.syncEnglishPredictUi()
            syncSymbolPanelUi()
        }

        val oldOnModeChanged = keyboardController.onModeChanged
        keyboardController.onModeChanged = { newMode ->
            oldOnModeChanged?.invoke(newMode)
            handleMainModeChanged(newMode)
        }

        applyEnQwertyPredictPrefIfNeeded(mainMode())

        currentEngineOrNull()?.syncEnglishPredictUi()
        syncSymbolPanelUi()
        applyResolvedComposingPreview()
    }

    private fun handleMainModeChanged(newMode: KeyboardMode) {
        if (!enginesReady()) {
            lastKnownMainMode = newMode
            return
        }

        if (inHandleMainModeChanged) {
            deferredMainMode = newMode
            return
        }

        inHandleMainModeChanged = true
        try {
            var target = newMode

            while (true) {
                val oldMode = lastKnownMainMode
                lastKnownMainMode = target

                if (oldMode != null && oldMode != target) {
                    val oldEngine = engineByModeOrNull(oldMode)
                    val newEngine = engineByModeOrNull(target)
                    if (oldEngine != null && newEngine != null) {
                        oldEngine.beforeModeSwitch()

                        oldEngine.clearComposing()
                        newEngine.clearComposing()
                        newEngine.afterModeSwitch()

                        applyEnQwertyPredictPrefIfNeeded(target)

                        assertSessionCleared(oldMode, from = "handleMainModeChanged ${oldMode} -> ${target} (old)")
                        assertSessionCleared(target, from = "handleMainModeChanged ${oldMode} -> ${target} (new)")

                        syncSymbolPanelUi()
                        applyResolvedComposingPreview()
                    }
                }

                val next = deferredMainMode
                if (next == null || next == target) {
                    deferredMainMode = null
                    break
                }

                deferredMainMode = null
                target = next
            }
        } finally {
            inHandleMainModeChanged = false
        }
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

    fun refreshComposingView() {
        applyResolvedComposingPreview()
    }

    fun refreshCandidates() {
        currentEngineOrNull()?.refreshCandidates()
    }

    override fun inputConnection(): InputConnection? = inputConnectionProvider()

    override fun clearComposing() {
        currentEngineOrNull()?.clearComposing()
        applyResolvedComposingPreview()
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
        applyResolvedComposingPreview()
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

        if (keyLabel == "分词") {
            val mode = mainMode()
            if (mode.isChinese && mode.useT9Layout) {
                sessions.cnT9.insertT9ManualCutAtEnd()
                engine.refreshCandidates()
                refreshComposingView()
            }
            return
        }

        val isEnter = keyLabel.contains("⏎") || keyLabel.contains("\n")
        if (isEnter) {
            val enterOverride = candidateController.resolveEnterCommitText()
            if (!enterOverride.isNullOrEmpty()) {
                val ic = inputConnectionProvider() ?: return
                ic.commitText(enterOverride, 1)
                currentEngineOrNull()?.clearComposing()
                applyResolvedComposingPreview()
                return
            }

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
        if (!::keyboardController.isInitialized) return
        keyboardController.setLanguage(false)
        syncSymbolPanelUi()
    }

    override fun switchToChineseMode() {
        if (!::keyboardController.isInitialized) return
        keyboardController.setLanguage(true)
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
        val mode = mainMode()
        val engineValue = currentEngineOrNull()?.getEnglishPredictEnabled()

        return if (!mode.isChinese && !mode.useT9Layout) {
            engineValue ?: loadEnQwertyPredictPref()
        } else {
            engineValue ?: false
        }
    }

    override fun setEnglishPredict(enabled: Boolean) {
        val mode = mainMode()

        if (!mode.isChinese && !mode.useT9Layout) {
            saveEnQwertyPredictPref(enabled)
        }

        currentEngineOrNull()?.setEnglishPredict(enabled)
    }

    override fun toggleEnglishPredict() {
        val next = !getEnglishPredictEnabled()
        setEnglishPredict(next)
    }
}
