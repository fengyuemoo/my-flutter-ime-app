package com.example.myapp.ime.compose.common

import com.example.myapp.ime.keyboard.model.KeyboardMode

class ComposingSessionHub(
    /**
     * 返回当前主模式；当上层还没准备好（例如 keyboardController 尚未 attach 或尚未创建）
     * 可以返回 null。
     */
    private val modeProvider: () -> KeyboardMode?
) {
    val cnQwerty: ComposingSession = ComposingSession()
    val cnT9: ComposingSession = ComposingSession()
    val enQwerty: ComposingSession = ComposingSession()
    val enT9: ComposingSession = ComposingSession()

    /**
     * 当 modeProvider 返回 null 时，说明主模式信息还不可用。
     */
    fun currentOrNull(): ComposingSession? {
        val mode = modeProvider() ?: return null
        return currentByMode(mode)
    }

    /**
     * 常规使用入口：总能返回一个 session。
     * 若 modeProvider 返回 null，默认返回中文QWERTY（最保守、也最符合默认语言）。
     */
    fun current(): ComposingSession {
        val mode = modeProvider() ?: KeyboardMode(isChinese = true, useT9Layout = false)
        return currentByMode(mode)
    }

    fun clearCurrent() {
        current().clear()
    }

    fun clearAll() {
        cnQwerty.clear()
        cnT9.clear()
        enQwerty.clear()
        enT9.clear()
    }

    private fun currentByMode(mode: KeyboardMode): ComposingSession {
        return when {
            mode.isChinese && !mode.useT9Layout -> cnQwerty
            mode.isChinese && mode.useT9Layout -> cnT9
            !mode.isChinese && !mode.useT9Layout -> enQwerty
            else -> enT9
        }
    }
}
