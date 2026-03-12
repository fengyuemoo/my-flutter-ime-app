package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.ime.compose.common.ComposingSession
import java.util.Locale

/**
 * CN-T9 左侧音节栏（sidebar）构建器。
 *
 * 职责：
 *  1. resolveSidebarDigits   — 根据焦点段决定用哪段 digits 查询 sidebar
 *  2. buildSidebar           — 从字典查拼音可能性列表
 *  3. buildSidebarTitle      — 生成焦点段的数字标题（如 "94664"），供 UI 顶部展示
 *  4. buildResegmentPaths    — 枚举焦点段 digitChunk 的所有合法切分路径（重切分功能）
 *  5. buildSidebarForSegment — 消歧模式下合并字典来源 + 重切分路径音节，统一给 UI
 *  6. build（一次性版本）     — 一次调用返回 SidebarResult，减少重复计算
 *
 * 对应规则清单：
 *  - 「音节栏的元素」：按音节展示，内容来自字典可能性 + 重切分路径
 *  - 「焦点音节的职责」：允许替换/缩短/扩展消歧，sidebar 提供所有候选音节
 *  - 「音节栏的重新切分」：通过 buildResegmentPaths 枚举多条路径供用户切换
 *  - 「音节栏与数字段对齐」：每条路径的每个音节对应 digitChunk 的连续子段
 */
object CnT9SidebarBuilder {

    private const val MAX_SIDEBAR_ITEMS = 24

    // ── 数据类 ───────────────────────────────────────────────────────────

    /**
     * sidebar 构建结果。
     *
     * @param syllables      sidebar 显示的音节候选列表（去重，最多 MAX_SIDEBAR_ITEMS 个）
     * @param resegmentPaths 当前焦点段的所有合法切分路径（每条路径是音节列表）
     * @param title          焦点段的数字标题（如 "94664"）；正常输入模式为 null
     */
    data class SidebarResult(
        val syllables: List<String>,
        val resegmentPaths: List<List<String>>,
        val title: String?
    )

    // ── 原有接口（保持兼容）──────────────────────────────────────────────

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

        val seg = session.t9MaterializedSegments.getOrNull(focusedSegmentIndex)
            ?: return rawDigits
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
     * 让用户在消歧时能确认「当前在切哪一段」。
     *
     * @return 焦点段的 digitChunk（如 "94664"）；正常输入模式返回 null
     */
    fun buildSidebarTitle(
        session: ComposingSession,
        focusedSegmentIndex: Int,
        rawDigits: String
    ): String? {
        if (focusedSegmentIndex < 0) return null

        val seg = session.t9MaterializedSegments.getOrNull(focusedSegmentIndex)
            ?: return null
        val digitChunk = seg.digitChunk.filter { it in '0'..'9' }
        return digitChunk.ifEmpty { null }
    }

    // ── 新增接口 ─────────────────────────────────────────────────────────

    /**
     * 枚举焦点段 digitChunk 的所有合法拼音切分路径。
     *
     * 对应规则清单「音节栏的重新切分」：
     *  - 返回列表中每一项是一条完整路径（音节列表）
     *  - 第 0 条是最优路径（最长匹配贪婪优先）
     *  - 供 UI 提供「路径切换」手势/按钮
     *
     * @param digitChunk 焦点段的纯数字串
     * @return 所有合法切分路径；无合法路径时返回空列表
     */
    fun buildResegmentPaths(digitChunk: String): List<List<String>> {
        if (digitChunk.isEmpty()) return emptyList()
        return CnT9ResegmentResolver.resolve(digitChunk)
    }

    /**
     * 消歧模式下，合并「字典拼音可能性」与「重切分路径音节」两个来源，
     * 返回统一的 sidebar 音节候选列表。
     *
     * 合并策略：
     *  1. 先放字典来源（频率高、更可靠）
     *  2. 再补充重切分路径中出现但字典未覆盖的音节
     *  3. 去重，最多 MAX_SIDEBAR_ITEMS 个
     *
     * 对应规则清单「音节栏的元素」和「焦点音节的职责」。
     *
     * @param dictEngine          字典引擎
     * @param digitChunk          焦点段的纯数字串
     * @param resegmentPaths      已枚举好的切分路径（避免重复计算）
     * @return 合并后的音节候选列表
     */
    fun buildSidebarForSegment(
        dictEngine: Dictionary,
        digitChunk: String,
        resegmentPaths: List<List<String>>
    ): List<String> {
        val dictSyllables = if (dictEngine.isLoaded && digitChunk.isNotEmpty()) {
            dictEngine.getPinyinPossibilities(digitChunk)
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        // 从重切分路径中提取所有出现过的音节（扁平化后去重）
        val resegSyllables = resegmentPaths
            .flatten()
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        // 合并：字典优先，重切分补充
        return (dictSyllables + resegSyllables)
            .distinct()
            .take(MAX_SIDEBAR_ITEMS)
    }

    /**
     * 一次性构建完整 SidebarResult，减少调用方重复计算。
     *
     * 正常输入模式（focusedSegmentIndex == -1）：
     *  - syllables 来自字典对 rawDigits 的查询
     *  - resegmentPaths 为空（不在消歧中）
     *  - title 为 null
     *
     * 消歧模式（focusedSegmentIndex >= 0）：
     *  - syllables 来自字典 + 重切分路径合并
     *  - resegmentPaths 为该焦点段的所有合法切分路径
     *  - title 为该焦点段的 digitChunk 字符串
     */
    fun build(
        dictEngine: Dictionary,
        session: ComposingSession,
        focusedSegmentIndex: Int,
        rawDigits: String
    ): SidebarResult {
        val sidebarDigits = resolveSidebarDigits(session, focusedSegmentIndex, rawDigits)

        return if (focusedSegmentIndex < 0) {
            // 正常输入模式
            val syllables = buildSidebar(dictEngine, sidebarDigits)
            SidebarResult(
                syllables = syllables,
                resegmentPaths = emptyList(),
                title = null
            )
        } else {
            // 消歧模式
            val digitChunk = sidebarDigits
            val resegmentPaths = buildResegmentPaths(digitChunk)
            val syllables = buildSidebarForSegment(dictEngine, digitChunk, resegmentPaths)
            val title = buildSidebarTitle(session, focusedSegmentIndex, rawDigits)
            SidebarResult(
                syllables = syllables,
                resegmentPaths = resegmentPaths,
                title = title
            )
        }
    }
}
