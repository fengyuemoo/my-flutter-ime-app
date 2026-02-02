package com.example.myapp.ime.api

import android.view.inputmethod.InputConnection

/**
 * IME actions exposed to keyboard views (QWERTY/T9/panels) and other UI components.
 *
 * Keep this interface in sync with [com.example.myapp.ime.router.ImeActionDispatcher],
 * which is the primary implementation.
 */
interface ImeActions {

    fun inputConnection(): InputConnection?

    fun clearComposing()

    fun handleComposingInput(text: String)
    fun handleT9Input(digit: String)
    fun onPinyinSidebarClick(pinyin: String)

    fun commitText(text: String)
    fun handleSpaceKey()
    fun handleBackspace()
    fun handleSpecialKey(keyLabel: String)

    fun switchToEnglishMode()
    fun switchToChineseMode()

    fun switchToNumericMode()
    fun openSymbolPanel()
    fun closeSymbolPanel()
    fun exitNumericMode()

    /** Symbol panel (multi-page) */
    enum class SymbolCategory { COMMON, CN, EN }

    fun setSymbolCategory(category: SymbolCategory)
    fun symbolPageUp()
    fun symbolPageDown()
    fun toggleSymbolLock()

    /**
     * Commit a symbol from the symbol panel.
     * Dispatcher will decide whether to close the panel based on lock state.
     */
    fun commitSymbolFromPanel(symbol: String)

    /** Returns the current English prediction state for the active English main mode. */
    fun getEnglishPredictEnabled(): Boolean

    /** Sets the prediction state for the active English main mode (EnQwerty or EnT9). */
    fun setEnglishPredict(enabled: Boolean)

    /** Convenience action for the English predict button. */
    fun toggleEnglishPredict()
}
