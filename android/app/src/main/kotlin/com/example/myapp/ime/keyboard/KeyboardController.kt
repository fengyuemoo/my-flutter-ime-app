package com.example.myapp.ime.keyboard

import android.widget.FrameLayout
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.keyboard.model.PanelState
import com.example.myapp.ime.keyboard.ui.EnglishPredictUi
import com.example.myapp.ime.ui.FontApplier
import com.example.myapp.keyboard.core.IKeyboardMode
import com.example.myapp.keyboard.core.ISidebarHost
import com.example.myapp.keyboard.core.KeyboardRegistry
import com.example.myapp.keyboard.core.KeyboardType
import com.example.myapp.keyboard.core.PanelType
import com.example.myapp.keyboard.core.RawCommitMode

interface SymbolPanelUi {
    fun renderSymbolPanel(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean,
        isChineseMainMode: Boolean
    )
}

class KeyboardController(
    bodyFrame: FrameLayout,
    private val host: KeyboardHost,
    private val registry: KeyboardRegistry
) {
    private val bodyFrame: FrameLayout = bodyFrame

    private var currentKeyboard: IKeyboardMode? = null
    private var lastKeyboardBeforePanel: IKeyboardMode? = null

    private var _mode: KeyboardMode = KeyboardMode(isChinese = true, useT9Layout = false)
    private var _panelState: PanelState = PanelState.None

    var onModeChanged: ((KeyboardMode) -> Unit)? = null
    var onKeyboardChanged: (() -> Unit)? = null

    var themeModeProvider: (() -> Int)? = null
    var englishPredictEnabledProvider: (() -> Boolean)? = null

    // NEW: Font config provider (family + scale)
    var fontConfigProvider: (() -> Pair<String, Float>)? = null

    val mode: KeyboardMode get() = _mode
    val panel: PanelState get() = _panelState

    val isChinese: Boolean get() = _mode.isChinese
    val useT9Layout: Boolean get() = _mode.useT9Layout

    private val kbNumeric: IKeyboardMode = registry.get(KeyboardType.NUMERIC)
    private val kbSymbol: IKeyboardMode = registry.get(KeyboardType.SYMBOL)
    private val kbCnT9: IKeyboardMode = registry.get(KeyboardType.CNT9)
    private val kbEnT9: IKeyboardMode = registry.get(KeyboardType.ENT9)

    private fun applyFontIfAny() {
        val (family, scale) = fontConfigProvider?.invoke() ?: return
        currentKeyboard?.getView()?.let { FontApplier.apply(it, family, scale) }
    }

    fun applyFont(fontFamily: String, fontScale: Float) {
        currentKeyboard?.getView()?.let { FontApplier.apply(it, fontFamily, fontScale) }
    }

    fun updateEnglishPredictUi(enabled: Boolean) {
        val keyboard = currentKeyboard
        if (keyboard is EnglishPredictUi) {
            keyboard.setEnglishPredictEnabled(enabled)
        }
    }

    fun refreshEnglishPredictUi() {
        val enabled = englishPredictEnabledProvider?.invoke() ?: false
        updateEnglishPredictUi(enabled)
    }

    fun updateSymbolPanelUi(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean
    ) {
        val keyboard = currentKeyboard
        if (keyboard is SymbolPanelUi) {
            keyboard.renderSymbolPanel(
                category = category,
                page = page,
                locked = locked,
                isChineseMainMode = _mode.isChinese
            )
        }
    }

    fun getMainMode(): KeyboardMode = _mode
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

    fun setMainMode(isChinese: Boolean, useT9Layout: Boolean) {
        val newMode = KeyboardMode(isChinese = isChinese, useT9Layout = useT9Layout)

        _mode = newMode
        onModeChanged?.invoke(_mode)

        val targetMain = resolveMainKeyboard(_mode.isChinese, _mode.useT9Layout)

        if (_panelState is PanelState.Open) {
            lastKeyboardBeforePanel = targetMain
            host.onToolbarUpdate()
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

    fun openPanel(type: PanelType) {
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
        // NEW: 主题应用后也要保持当前字体/字号选择
        applyFontIfAny()
    }

    private fun showKeyboard(target: IKeyboardMode) {
        currentKeyboard = target
        bodyFrame.removeAllViews()
        bodyFrame.addView(target.getView())
        target.onActivate()

        val themeMode = themeModeProvider?.invoke() ?: 0
        target.applyTheme(themeMode)

        // NEW: 每次键盘切换后应用用户字体/字号
        applyFontIfAny()

        host.onToolbarUpdate()
        onKeyboardChanged?.invoke()

        refreshEnglishPredictUi()
    }
}
