package com.example.myapp.ime.keyboard

import android.widget.FrameLayout
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.keyboard.model.PanelState
import com.example.myapp.ime.keyboard.ui.EnglishPredictUi
import com.example.myapp.keyboard.core.IKeyboardMode
import com.example.myapp.keyboard.core.ISidebarHost
import com.example.myapp.keyboard.core.KeyboardRegistry
import com.example.myapp.keyboard.core.KeyboardType
import com.example.myapp.keyboard.core.PanelType
import com.example.myapp.keyboard.core.RawCommitMode

class KeyboardController(
    bodyFrame: FrameLayout,
    private val host: KeyboardHost,
    private val registry: KeyboardRegistry
) {
    private val bodyFrame: FrameLayout = bodyFrame

    private var currentKeyboard: IKeyboardMode? = null
    private var lastKeyboardBeforePanel: IKeyboardMode? = null

    // Internal unified state: main mode + panel state
    private var _mode: KeyboardMode = KeyboardMode(isChinese = true, useT9Layout = false)
    private var _panelState: PanelState = PanelState.None

    // Optional listeners (for SessionHub / UI binder / etc.)
    var onModeChanged: ((KeyboardMode) -> Unit)? = null
    var onKeyboardChanged: (() -> Unit)? = null

    /**
     * English predict state provider: injected by router/dispatcher.
     *
     * KeyboardController does NOT own predict state; it only pushes it into current keyboard UI
     * (if supported).
     */
    var englishPredictEnabledProvider: (() -> Boolean)? = null

    // Read-only public properties
    val mode: KeyboardMode get() = _mode

    /**
     * Note: don't name this 'panelState' (would conflict with getPanelState() JVM signature).
     * Use panel / getPanelState().
     */
    val panel: PanelState get() = _panelState

    // Compat getters (legacy call sites)
    val isChinese: Boolean get() = _mode.isChinese
    val useT9Layout: Boolean get() = _mode.useT9Layout

    // Cache: avoid repeated registry.get and ensure stable instances
    private val kbNumeric: IKeyboardMode = registry.get(KeyboardType.NUMERIC)
    private val kbSymbol: IKeyboardMode = registry.get(KeyboardType.SYMBOL)
    private val kbCnT9: IKeyboardMode = registry.get(KeyboardType.CNT9)
    private val kbEnT9: IKeyboardMode = registry.get(KeyboardType.ENT9)

    /**
     * UI-only: push English predict state to current keyboard UI if it supports EnglishPredictUi.
     */
    fun updateEnglishPredictUi(enabled: Boolean) {
        val keyboard = currentKeyboard
        if (keyboard is EnglishPredictUi) {
            keyboard.setEnglishPredictEnabled(enabled)
        }
    }

    /**
     * UI-only: pull predict state from provider (current strategy) and push to keyboard UI.
     * If provider isn't injected, default to false.
     */
    fun refreshEnglishPredictUi() {
        val enabled = englishPredictEnabledProvider?.invoke() ?: false
        updateEnglishPredictUi(enabled)
    }

    /**
     * Router/dispatcher should use this: panel does not participate in compose strategy selection.
     */
    fun getMainMode(): KeyboardMode = _mode

    /**
     * Panel state (None / Open(type)).
     */
    fun getPanelState(): PanelState = _panelState

    fun isPanelOpen(): Boolean = getPanelState() is PanelState.Open

    fun isRawCommitMode(): Boolean = currentKeyboard is RawCommitMode

    fun updateSidebar(items: List<String>) {
        val kb = currentKeyboard
        if (kb is ISidebarHost) {
            kb.updateSideBar(items)
        }
    }

    private fun resolveMainKeyboard(isChinese: Boolean, useT9Layout: Boolean): IKeyboardMode {
        return when {
            isChinese && !useT9Layout -> registry.get(KeyboardType.CNQWERTY)
            !isChinese && !useT9Layout -> registry.get(KeyboardType.ENQWERTY)
            isChinese && useT9Layout -> kbCnT9
            else -> kbEnT9
        }
    }

    /**
     * Set main input mode (CN/EN + Qwerty/T9).
     *
     * If a panel is currently open, we still update internal mode and refresh
     * "keyboard to restore to" (lastKeyboardBeforePanel), but we do not change UI immediately.
     */
    fun setMainMode(isChinese: Boolean, useT9Layout: Boolean) {
        val newMode = KeyboardMode(isChinese = isChinese, useT9Layout = useT9Layout)

        _mode = newMode
        onModeChanged?.invoke(_mode)

        val targetMain = resolveMainKeyboard(_mode.isChinese, _mode.useT9Layout)

        // Panel open: only update the keyboard that will be restored after closing the panel.
        if (_panelState is PanelState.Open) {
            lastKeyboardBeforePanel = targetMain
            host.onToolbarUpdate()
            // Refresh is harmless even on panels (only keyboards implementing EnglishPredictUi react).
            refreshEnglishPredictUi()
            return
        }

        showKeyboard(targetMain)
    }

    fun setLanguage(chinese: Boolean) {
        setMainMode(isChinese = chinese, useT9Layout = _mode.useT9Layout)
    }

    fun toggleLanguage() {
        setMainMode(isChinese = !_mode.isChinese, useT9Layout = _mode.useT9Layout)
    }

    fun setLayout(useT9: Boolean) {
        setMainMode(isChinese = _mode.isChinese, useT9Layout = useT9)
    }

    fun toggleLayout() {
        setMainMode(isChinese = _mode.isChinese, useT9Layout = !_mode.useT9Layout)
    }

    /**
     * Open panel (NUMERIC / SYMBOL).
     * Records which keyboard to restore after closing the panel.
     */
    fun openPanel(type: PanelType) {
        // Record the pre-panel keyboard only once.
        if (_panelState !is PanelState.Open) {
            lastKeyboardBeforePanel = currentKeyboard
        }

        val target = when (type) {
            PanelType.NUMERIC -> kbNumeric
            PanelType.SYMBOL -> kbSymbol
        }

        _panelState = PanelState.Open(type)
        showKeyboard(target)
    }

    /**
     * Close panel: prefer restoring lastKeyboardBeforePanel;
     * if null, fall back to current main mode keyboard.
     */
    fun closePanel() {
        _panelState = PanelState.None

        val last = lastKeyboardBeforePanel
        if (last != null) {
            showKeyboard(last)
            lastKeyboardBeforePanel = null
            return
        }

        showKeyboard(resolveMainKeyboard(_mode.isChinese, _mode.useT9Layout))
    }

    fun applyTheme(themeMode: Int) {
        currentKeyboard?.applyTheme(themeMode)
    }

    private fun showKeyboard(target: IKeyboardMode) {
        currentKeyboard = target
        bodyFrame.removeAllViews()
        bodyFrame.addView(target.getView())
        target.onActivate()
        host.onToolbarUpdate()
        onKeyboardChanged?.invoke()

        // After switching keyboards, sync English predict UI once (if supported by current keyboard).
        refreshEnglishPredictUi()
    }
}
