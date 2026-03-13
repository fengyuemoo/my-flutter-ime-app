package com.example.myapp.ime.compose.common

import android.view.inputmethod.InputConnection

interface ComposeStrategy {
    fun onComposingInput(text: String): StrategyResult
    fun onT9Input(digit: String): StrategyResult

    // 修复：加 t9Code 参数，与 ImeActions/ModeInputEngine 签名对齐
    fun onPinyinSidebarClick(pinyin: String, t9Code: String = "")

    fun onEnter(ic: InputConnection?): StrategyResult
}
