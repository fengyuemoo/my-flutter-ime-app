package com.example.myapp.ime.compose.cn.t9

/**
 * Preedit 单段的样式信息。
 *
 * @param text      该段的拼音文字（如 "zhong"）
 * @param style     段样式枚举
 */
data class PreeditSegment(
    val text: String,
    val style: Style
) {
    enum class Style {
        /** 普通未锁定段（浅色） */
        NORMAL,
        /** 用户已锁定的段（高亮/加粗） */
        LOCKED,
        /** 当前焦点段（下划线 + 高亮） */
        FOCUSED,
        /** 词库未加载时的键位占位符，如 [abc] */
        FALLBACK,
        /** 已物化上屏的汉字前缀 */
        COMMITTED_PREFIX
    }
}

/**
 * Preedit 富文本展示载体。
 *
 * [plainText] 用于传给 InputConnection.setComposingText()（只需纯文字）。
 * [segments]  用于 UI 渲染各段样式（高亮/下划线等）。
 * [isFallback] 为 true 表示词库未加载，所有段均为 FALLBACK 样式。
 */
data class PreeditDisplay(
    val plainText: String,
    val segments: List<PreeditSegment>,
    val isFallback: Boolean = false
) {
    companion object {
        val EMPTY = PreeditDisplay(plainText = "", segments = emptyList())
    }
}
