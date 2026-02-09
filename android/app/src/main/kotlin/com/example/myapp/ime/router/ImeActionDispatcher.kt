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

    // --- Prevent re-entrance for mode switching ---
    private var inHandleMainModeChanged: Boolean = false
    private var deferredMainMode: KeyboardMode? = null

    // --- EN-QWERTY predict preference (default ON) ---
    private val prefs by lazy { context.getSharedPreferences("ime_settings", Context.MODE_PRIVATE) }

    companion object {
        private const val KEY_EN_QWERTY_PREDICT_ENABLED = "en_qwerty_predict_enabled"
    }

    private fun loadEnQwertyPredictPref(): Boolean =
        prefs.getBoolean(KEY_EN_QWERTY_PREDICT_ENABLED, true)

    private fun saveEnQwertyPredictPref(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EN_QWERTY_PREDICT_ENABLED, enabled).apply()
    }

    // --- Debug assertions for B semantic ---

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

    // --- helpers ---

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

    private fun applyEnQwertyPredictPrefIfNeeded(mode: KeyboardMode) {
        // Only apply default/pref when entering English QWERTY.
        if (!mode.isChinese && !mode.useT9Layout) {
            engineByModeOrNull(mode)?.setEnglishPredict(loadEnQwertyPredictPref())
        }
        // EN-T9: do nothing ("不管") — its strategy keeps its own in-memory state (default true).
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

        lastKnownMainMode = keyboardController.getMainMode()

        // Chain callbacks (avoid breaking any existing hooks).
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

        // If we are already in EN-QWERTY when attached, apply pref once.
        applyEnQwertyPredictPrefIfNeeded(mainMode())

        currentEngineOrNull()?.syncEnglishPredictUi()
        syncSymbolPanelUi()
    }

    /**
     * B semantic: whenever main mode changes (language OR layout), clear composing for both
     * old and new mode sessions, and run per-mode before/after hooks.
     *
     * This catches layout switching even if it is triggered outside ImeActions.
     */
    private fun handleMainModeChanged(newMode: KeyboardMode) {
        if (!enginesReady()) {
            lastKnownMainMode = newMode
            return
        }

        // Prevent re-entrance: remember only the latest mode requested while handling.
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

                        // B: switching clears composing (clear old + clear new).
                        oldEngine.clearComposing()
                        newEngine.clearComposing()
                        newEngine.afterModeSwitch()

                        // NEW: only EN-QWERTY auto-apply predict pref; EN-T9 not touched.
                        applyEnQwertyPredictPrefIfNeeded(target)

                        // Debug assert: both sessions must be cleared after switching.
                        assertSessionCleared(oldMode, from = "handleMainModeChanged ${oldMode} -> ${target} (old)")
                        assertSessionCleared(target, from = "handleMainModeChanged ${oldMode} -> ${target} (new)")

                        // If symbol panel is open, its content may depend on isChineseMainMode.
                        syncSymbolPanelUi()
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
        if (!::keyboardController.isInitialized) return
        // Clearing + hooks are handled in onModeChanged (B).
        keyboardController.setLanguage(false)
        syncSymbolPanelUi()
    }

    override fun switchToChineseMode() {
        if (!::keyboardController.isInitialized) return
        // Clearing + hooks are handled in onModeChanged (B).
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

        // Only EN-QWERTY uses preference as the "default" source; other modes return engine state.
        return if (!mode.isChinese && !mode.useT9Layout) {
            engineValue ?: loadEnQwertyPredictPref()
        } else {
            engineValue ?: false
        }
    }

    override fun setEnglishPredict(enabled: Boolean) {
        val mode = mainMode()

        // Only persist user's choice for EN-QWERTY; EN-T9 不管（不写偏好）。
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
