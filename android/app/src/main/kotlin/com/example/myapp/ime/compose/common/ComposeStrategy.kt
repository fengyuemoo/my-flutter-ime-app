package com.example.myapp.ime.compose.common

import android.view.inputmethod.InputConnection

interface ComposeStrategy {
    fun onComposingInput(text: String): StrategyResult
    fun onT9Input(digit: String): StrategyResult
    fun onPinyinSidebarClick(pinyin: String)

    /**
     * 处理 Enter / Re-enter 这类特殊键。
     * 返回 true 表示已消费；false 表示由上层使用默认 KeyEvent ENTER 处理。
     */
    fun onEnter(ic: InputConnection?): Boolean
}
