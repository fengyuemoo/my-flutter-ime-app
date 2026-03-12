package com.example.myapp.ime.mode.cn

/**
 * 中文标点辅助工具。
 *
 * 职责：
 *  1. 半角 → 全角标点转换（中文模式下自动上屏全角）
 *  2. 提供「快捷标点候选」列表（候选栏头部固定展示）
 *  3. 提供「是否为标点键」的判断
 */
object CnPunctuationHelper {

    /**
     * 半角 → 全角映射表（Char → String，因部分全角符号由多字符组成）。
     * 仅覆盖中文输入中最常见的标点，不做过度转换。
     */
    private val HALF_TO_FULL: Map<Char, String> = mapOf(
        '.'  to "。",
        ','  to "，",
        '!'  to "！",
        '?'  to "？",
        ';'  to "；",
        ':'  to "：",
        '"'  to "\u201C",   // 左双引号 "
        '\'' to "\u2018",   // 左单引号 '
        '('  to "（",
        ')'  to "）",
        '['  to "【",
        ']'  to "】",
        '<'  to "《",
        '>'  to "》",
        '-'  to "——",
        '_'  to "＿",
        '~'  to "～",
        '/'  to "、",
        '\\' to "、",
        '^'  to "……",
        '&'  to "＆",
        '@'  to "＠",
        '#'  to "＃",
        '%'  to "％",
        '*'  to "×",
        '+'  to "＋",
        '='  to "＝"
    )

    /**
     * 将半角标点转为中文全角标点。
     * 若无对应映射则返回原字符串。
     */
    fun toFullWidth(input: String): String {
        if (input.isEmpty()) return input
        if (input.length == 1) {
            return HALF_TO_FULL[input[0]] ?: input
        }
        return input.map { HALF_TO_FULL[it] ?: it.toString() }.joinToString("")
    }

    /**
     * 判断输入字符串是否是纯标点（需要做全半角转换）。
     */
    fun isPunctuation(input: String): Boolean {
        return input.isNotEmpty() && input.all { it in HALF_TO_FULL }
    }

    /**
     * 快捷标点候选列表（候选栏在 composing 状态下头部固定展示区域）。
     */
    val QUICK_PUNCTS: List<String> = listOf(
        "。", "，", "！", "？", "；", "：",
        "——", "……", "《", "》", "【", "】",
        "（", "）", "\u201C", "\u201D"
    )

    /**
     * 是否是快捷标点上屏（无需经过 composing 流程）。
     */
    fun isQuickPunct(text: String): Boolean = text in QUICK_PUNCTS
}
