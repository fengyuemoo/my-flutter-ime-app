package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.ime.compose.common.ComposingSession
import java.util.Locale

/**
 * CN-T9 左侧音节栏（sidebar）构建器。
 *
 * 职责：
 *  1. resolveSidebarDigits  — 根据焦点段决定用哪段 digits 查询 sidebar
 *  2. buildSidebar          — 从字典查拼音可能性列表（纯字典，无噪音单字母兜底）
 *  3. buildSidebarTitle     — 生成焦点段的数字标题（如 "94664"），供 UI 顶部展示
 *
 * 说明：
 *  旧版有一行 `fromT9 = T9Lookup.charsFromDigit(sidebarDigits.first())` 只取第一位
 *  按键的字母作为兜底，实际上是噪音——字母 a/b/c 和真实拼音音节语义不同，会干扰用户
 *  认知。现已移除，sidebar 内容完全来自 dictEngine.getPinyinPossibilities()。
 */
object CnT9SidebarBuilder {

    private const val MAX_SIDEBAR_ITEMS = 24

    /**
     * 决定 sidebar 应该基于哪段 digits 来查询拼音可能性。
     *  - 有焦点段（index >= 0）→ 用该物化段的 digitChunk（重切分/消歧模式）
     *  - 无焦点（index == -1）→ 用 rawDigits（正常输入，展示当前待切分段）
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
     * 用给定 digits 从字典构建 sidebar 候选拼音列表。
     * 结果按字典返回顺序排列，去重，最多 MAX_SIDEBAR_ITEMS 个。
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
     * 生成焦点段的数字标题，供 UI 在 sidebar 顶部展示。
     * 让用户在消歧时能确认"当前在切哪一段"。
     *
     * @return 焦点段的 digitChunk（如 "94664"）；正常输入模式返回 null
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
}
