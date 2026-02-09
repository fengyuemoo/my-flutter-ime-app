package com.example.myapp.ime.router

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.StrategyResult

interface ImeInputEngine {
    fun refreshCandidates()
    fun refreshComposingView()

    fun clearComposing()
    fun handleComposingInput(text: String)
    fun handleT9Input(digit: String)
    fun onPinyinSidebarClick(pinyin: String)
    fun handleBackspace()

    fun handleSpaceKey()
    fun handleEnterFallback(ic: InputConnection?)

    fun getEnglishPredictEnabled(): Boolean
    fun setEnglishPredict(enabled: Boolean)
    fun toggleEnglishPredict()

    fun beforeModeSwitch()
    fun afterModeSwitch()

    fun syncEnglishPredictUi()
    fun handleStrategyResult(result: StrategyResult)
}
