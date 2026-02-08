package com.example.myapp.ime.compose.common

import android.view.inputmethod.InputConnection

interface ComposeStrategy {
    fun onComposingInput(text: String): StrategyResult
    fun onT9Input(digit: String): StrategyResult
    fun onPinyinSidebarClick(pinyin: String)

    /**
     * 处理 Enter / Re-enter 这类特殊键。
     * 返回 StrategyResult：
     * - DirectCommit / SessionMutated / ComposingUpdate => 已消费
     * - Noop => 不消费，由上层发送系统 ENTER KeyEvent
     */
    fun onEnter(ic: InputConnection?): StrategyResult
}
