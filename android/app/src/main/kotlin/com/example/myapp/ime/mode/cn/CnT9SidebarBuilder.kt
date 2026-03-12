package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import java.util.Locale

/**
 * CN-T9 左侧音节栏（sidebar）构建器。
 *
 * 职责：
 *  1. resolveSidebarDigits — 根据焦点段决定用哪段 digits 来查询 sidebar
 *  2. buildSidebar         — 用给定 digits 从字典查拼音可能性，合并 T9 字母候选
 *
 * 规则来源（交互规则清单）：
 *  - 焦点音节的职责：同一数字段可对应 shi/si/sh 等候选音节集合
 *  - 音节栏的元素：按"音节"展示，不是按单字母
 */
object CnT9SidebarBuilder {

    private const val MAX_SIDEBAR_ITEMS = 24

    /**
     * 决定 sidebar 应该基于哪段 digits 来查询拼音可能性。
     *  - 有焦点音节 → 用该音节的 digitChunk（消歧模式）
     *  - 无焦点     → 用 rawDigits 前缀（正常输入模式）
     */
    fun resolveSidebarDigits(
        session: ComposingSession,
        focusedSegmentIndex: Int,
        rawDigits: String
    ): String {
        if (focusedSegmentIndex < 0) return rawDigits

        val segs = session.t9MaterializedSegments
        val seg = segs.getOrNull(focusedSegmentIndex) ?: return rawDigits
        val digitChunk = seg.digitChunk.filter { it in '0'..'9' }
        return digitChunk.ifEmpty { rawDigits }
    }

    /**
     * 用给定 digits 构建 sidebar 候选拼音列表。
     * 结合字典拼音可能性与 T9 键位字母，去重后取前 MAX_SIDEBAR_ITEMS 个。
     */
    fun buildSidebar(dictEngine: Dictionary, sidebarDigits: String): List<String> {
        if (!dictEngine.isLoaded || sidebarDigits.isEmpty()) return emptyList()

        val fromDict = dictEngine.getPinyinPossibilities(sidebarDigits)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        val fromT9 = T9Lookup.charsFromDigit(sidebarDigits.first())
            .map { it.lowercase(Locale.ROOT) }

        return (fromDict + fromT9)
            .distinct()
            .take(MAX_SIDEBAR_ITEMS)
    }
}
