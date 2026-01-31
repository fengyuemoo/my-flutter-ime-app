package com.example.myapp.keyboard.core

import com.example.myapp.ime.compose.common.ComposingSession

/**
 * 可选能力：定义 Enter 键在“正在 composing”时的提交策略。
 * 返回 null 表示不做特判，走默认 Enter 行为（发送 KEYCODE_ENTER）。
 */
interface EnterCommitPolicy {
    fun getTextToCommitOnEnterWhileComposing(session: ComposingSession): String?
}
