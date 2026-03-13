package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionSnapshot
import java.util.Locale

/**
 * CN-T9 左侧音节栏（sidebar）构建器。
 *
 * 职责：
 *  1. resolveSidebarDigits          — 根据焦点段决定用哪段 digits 查询 sidebar（session 版）
 *  2. resolveSidebarDigitsFromSnapshot — 同上，接受不可变快照（后台线程安全版）
 *  3. buildSidebar                  — 从字典查拼音可能性列表
 *  4. buildSidebarTitle             — 生成焦点段的数字标题（session 版）
 *  5. buildSidebarTitleFromSnapshot — 同上，接受不可变快照（后台线程安全版）
 *  6. buildResegmentPaths           — 枚举焦点段 digitChunk 的所有合法切分路径
 *  7. buildSidebarForSegment        — 消歧模式下合并字典来源 + 重切分路径音节
 *  8. build                         — 一次性构建 SidebarResult（session 版）
 *  9. buildFromSnapshot             — 一次性构建 SidebarResult（快照版，线程安全）
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

    // ── Session 版公开接口（原有，保持兼容）──────────────────────────────

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
     * 生成焦点段的数字标题，供 UI 在 sidebar 顶部展示。
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

    /**
     * 一次性构建完整 SidebarResult（session 版）。
     *
     * 正常输入模式（focusedSegmentIndex == -1）：
     *  - syllables 来自字典对 rawDigits 的查询
     *  - resegmentPaths 为空
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
        val title = if (focusedSegmentIndex >= 0)
            buildSidebarTitle(session, focusedSegmentIndex, rawDigits)
        else null
        return buildCore(dictEngine, sidebarDigits, focusedSegmentIndex, title)
    }

    // ── 快照版公开接口（新增，后台线程安全）─────────────────────────────

    /**
     * 决定 sidebar 应该基于哪段 digits 来查询拼音可能性（快照版）。
     *
     * 语义与 [resolveSidebarDigits] 完全一致，从 [ComposingSessionSnapshot]
     * 的 [ComposingSessionSnapshot.t9DigitsStack] 读取 digitChunk，
     * 无需访问主线程的可变 [ComposingSession]。
     */
    fun resolveSidebarDigitsFromSnapshot(
        snapshot: ComposingSessionSnapshot,
        focusedSegmentIndex: Int,
        rawDigits: String
    ): String {
        if (focusedSegmentIndex < 0) return rawDigits
        // t9DigitsStack 与 pinyinStack 对齐，下标一一对应
        val digitChunk = snapshot.t9DigitsStack
            .getOrNull(focusedSegmentIndex)
            ?.filter { it in '0'..'9' }
            .orEmpty()
        return digitChunk.ifEmpty { rawDigits }
    }

    /**
     * 生成焦点段的数字标题（快照版）。
     *
     * 语义与 [buildSidebarTitle] 完全一致，从快照读取，线程安全。
     *
     * @return 焦点段的 digitChunk（如 "94664"）；正常输入模式返回 null
     */
    fun buildSidebarTitleFromSnapshot(
        snapshot: ComposingSessionSnapshot,
        focusedSegmentIndex: Int
    ): String? {
        if (focusedSegmentIndex < 0) return null
        val digitChunk = snapshot.t9DigitsStack
            .getOrNull(focusedSegmentIndex)
            ?.filter { it in '0'..'9' }
            .orEmpty()
        return digitChunk.ifEmpty { null }
    }

    /**
     * 一次性构建完整 SidebarResult（快照版，线程安全）。
     *
     * 与 [build] 完全对称，接受不可变的 [ComposingSessionSnapshot]，
     * 可安全在后台线程调用，供 [CnT9Handler.buildFromSnapshot] 使用。
     *
     * @param dictEngine          字典引擎（只读使用）
     * @param snapshot            来自 [ComposingSession.buildSnapshot] 的只读快照
     * @param focusedSegmentIndex 当前焦点段下标（-1 表示正常输入模式）
     * @param rawDigits           当前未物化的 T9 数字串
     */
    fun buildFromSnapshot(
        dictEngine: Dictionary,
        snapshot: ComposingSessionSnapshot,
        focusedSegmentIndex: Int,
        rawDigits: String
    ): SidebarResult {
        val sidebarDigits = resolveSidebarDigitsFromSnapshot(snapshot, focusedSegmentIndex, rawDigits)
        val title = if (focusedSegmentIndex >= 0)
            buildSidebarTitleFromSnapshot(snapshot, focusedSegmentIndex)
        else null
        return buildCore(dictEngine, sidebarDigits, focusedSegmentIndex, title)
    }

    // ── 工具接口（两套入口共用，无需改动）───────────────────────────────

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
     * 枚举焦点段 digitChunk 的所有合法拼音切分路径。
     *
     * 对应规则清单「音节栏的重新切分」：
     *  - 返回列表中每一项是一条完整路径（音节列表）
     *  - 第 0 条是最优路径（最长匹配贪婪优先）
     *  - 供 UI 提供「路径切换」手势/按钮
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

        val resegSyllables = resegmentPaths
            .flatten()
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        return (dictSyllables + resegSyllables)
            .distinct()
            .take(MAX_SIDEBAR_ITEMS)
    }

    // ── 私有：session 版与快照版共用的核心逻辑 ───────────────────────────

    /**
     * sidebar 构建核心，不依赖任何外部状态来源。
     *
     * 两套公开入口（[build] / [buildFromSnapshot]）在各自解析出
     * [sidebarDigits] 和 [title] 之后，统一调用此方法完成实际构建，
     * 确保两套接口的行为严格一致。
     *
     * @param dictEngine          字典引擎
     * @param sidebarDigits       已解析好的 digits（焦点段 digitChunk 或 rawDigits）
     * @param focusedSegmentIndex 焦点段下标（< 0 表示正常输入模式）
     * @param title               已解析好的 sidebar 标题（正常输入模式为 null）
     */
    private fun buildCore(
        dictEngine: Dictionary,
        sidebarDigits: String,
        focusedSegmentIndex: Int,
        title: String?
    ): SidebarResult {
        return if (focusedSegmentIndex < 0) {
            // 正常输入模式：只展示字典对 rawDigits 的查询结果
            SidebarResult(
                syllables      = buildSidebar(dictEngine, sidebarDigits),
                resegmentPaths = emptyList(),
                title          = null
            )
        } else {
            // 消歧模式：合并字典 + 重切分路径
            val resegmentPaths = buildResegmentPaths(sidebarDigits)
            SidebarResult(
                syllables      = buildSidebarForSegment(dictEngine, sidebarDigits, resegmentPaths),
                resegmentPaths = resegmentPaths,
                title          = title
            )
        }
    }
}
