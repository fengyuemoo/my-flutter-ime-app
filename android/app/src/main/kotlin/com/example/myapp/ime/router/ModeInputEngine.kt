package com.example.myapp.ime.router

import android.view.inputmethod.InputConnection

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
