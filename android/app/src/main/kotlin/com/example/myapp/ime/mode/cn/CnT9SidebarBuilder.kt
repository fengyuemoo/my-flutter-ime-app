package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import java.util.Locale

/**
 * CN-T9 左侧音节栏（sidebar）构建器。
 *
 * 职责：
 *  1. resolveSidebarDigits  — 根据焦点段决定用哪段 digits 查询 sidebar
 *  2. buildSidebar          — 从字典查拼音可能性列表
 *  3. buildSidebarTitle     — 生成焦点段的数字标题（如 "94664"），供 UI 展示
 *
 * 修正（相比旧版）：
 *  - 去掉了 fromT9 单字母兜底（它只反映第一位按键，实际是噪音）
 *  - sidebar 内容只来自 dictEngine.getPinyinPossibilities，保证展示的是真实拼音
 */
object CnT9SidebarBuilder {

    private const val MAX_SIDEBAR_ITEMS = 24

    /**
     * 决定 sidebar 应该基于哪段 digits 来查询拼音可能性。
     *  - 有焦点段（index >= 0）→ 用该物化段的 digitChunk
     *  - 无焦点               → 用 rawDigits（正常输入模式，展示前段拼音）
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
     * 完全依赖字典的拼音可能性（去掉了噪音单字母兜底）。
     */
    fun buildSidebar(dictEngine: Dictionary, sidebarDigits: String): List<String> {
        if (!dictEngine.isLoaded || sidebarDigits.isEmpty()) return emptyList()

        return dictEngine.getPinyinPossibilities(sidebarDigits)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SIDEBAR_ITEMS)
    }

    /**
     * 生成焦点段的数字标题，供 UI 在 sidebar 顶部展示（让用户确认在消哪一段）。
     * 若无焦点（正常输入模式）则返回 null。
     */
    fun buildSidebarTitle(
        session: ComposingSession,
        focusedSegmentIndex: Int,
        rawDigits: String
    ): String? {
        if (focusedSegmentIndex < 0) return null

        val segs = session.t9MaterializedSegments
        val seg = segs.getOrNull(focusedSegmentIndex) ?: return null
        val digitChunk = seg.digitChunk.filter { it in '0'..'9' }
        return digitChunk.ifEmpty { null }
    }

    /**
     * 判断 sidebar 当前是否处于消歧模式（有焦点段）。
     */
    fun isInDisambiguationMode(focusedSegmentIndex: Int): Boolean =
        focusedSegmentIndex >= 0
}
