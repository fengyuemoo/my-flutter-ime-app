package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionSnapshot
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120

    /**
     * 单字保底可见位置：前 SINGLE_CHAR_VISIBLE_WINDOW 个候选中若无单字，
     * 则将最高分单字强制提升至位置 SINGLE_CHAR_INJECT_POSITION。
     *
     * 规则来源：R-D03 单字候选必须在首屏可见范围内（位置 2–4）。
     * 实现为位置 1（0-indexed），即第二个候选槽，保证首位仍是最佳多字词，
     * 同时用户不需要翻页即可找到单字入口。
     */
    private const val SINGLE_CHAR_VISIBLE_WINDOW = 6
    private const val SINGLE_CHAR_INJECT_POSITION = 1

    // ── 公开入口 1：来自主线程 Session（原有接口，零破坏）────────────────────

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output = build(
        session         = session,
        dictEngine      = dictEngine,
        singleCharMode  = singleCharMode,
        userChoiceStore = null,
        contextWindow   = null,
        sidebarState    = null
    )

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        sidebarState: CnT9SidebarState?
    ): ImeModeHandler.Output {
        val lockedIndices   = sidebarState?.lockMap?.lockedSnapshot?.toList() ?: emptyList()
        val focusedIndex    = sidebarState?.focusedSegmentIndex ?: -1
        return buildInternal(
            snapshot        = session.buildSnapshot(),
            dictEngine      = dictEngine,
            singleCharMode  = singleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow,
            lockedIndices   = lockedIndices,
            focusedIndex    = focusedIndex
        )
    }

    // ── 公开入口 2：来自后台线程快照（缺陷 B 修复版）────────────────────────

    fun buildFromSnapshot(
        snapshot: ComposingSessionSnapshot,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore? = null,
        contextWindow: CnT9ContextWindow? = null,
        lockedIndices: List<Int> = emptyList(),
        focusedIndex: Int = -1
    ): ImeModeHandler.Output {
        return buildInternal(
            snapshot        = snapshot,
            dictEngine      = dictEngine,
            singleCharMode  = singleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow,
            lockedIndices   = lockedIndices,
            focusedIndex    = focusedIndex
        )
    }

    // ── 内部核心实现 ────────────────────────────────────────────────────────

    private fun buildInternal(
        snapshot: ComposingSessionSnapshot,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        lockedIndices: List<Int>,
        focusedIndex: Int
    ): ImeModeHandler.Output {
        val rawDigits = snapshot.rawT9Digits
        val stackSegs = snapshot.pinyinStack.map { it.lowercase(Locale.ROOT) }

        // ── Sidebar ──────────────────────────────────────────────────────
        val sidebarResult = CnT9SidebarBuilder.buildFromSnapshot(
            dictEngine          = dictEngine,
            snapshot            = snapshot,
            focusedSegmentIndex = focusedIndex,
            rawDigits           = rawDigits
        )

        // ── 候选规划 ─────────────────────────────────────────────────────
        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits     = rawDigits,
                manualCuts = snapshot.t9ManualCuts,
                dict       = dictEngine
            )
        } else emptyList()

        val plans = buildPlans(stackSegs, autoPlans, lockedIndices)

        val queried = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            CnT9CandidateFilter.queryCandidates(dictEngine, plans, lockedIndices)
        } else emptyList()

        val filtered = if (singleCharMode) queried.filter { it.word.length == 1 } else queried

        val finalList = ArrayList<Candidate>(filtered)

        val scoreCache = CnT9CandidateScorer.buildScoreCache(
            candidates      = finalList,
            plans           = plans,
            rawDigits       = rawDigits,
            lockedIndices   = lockedIndices,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow
        )

        CnT9CandidateScorer.sortCandidates(finalList, scoreCache)

        // ── R-D03 修复：单字首屏保证 ─────────────────────────────────────
        // singleCharMode 下全是单字，无需额外处理。
        // 普通模式下：若前 SINGLE_CHAR_VISIBLE_WINDOW 个候选中无单字，
        // 则将第一个出现的单字候选提升到 SINGLE_CHAR_INJECT_POSITION 位置。
        if (!singleCharMode) {
            ensureSingleCharVisible(finalList)
        }

        if (finalList.size > MAX_DISPLAY_CANDIDATES) {
            finalList.subList(MAX_DISPLAY_CANDIDATES, finalList.size).clear()
        }

        // ── Fallback：词库查无结果时提供 Unicode CJK 兜底 ────────────────
        if (finalList.isEmpty() && rawDigits.isNotEmpty()) {
            val fallbacks = CnT9UnicodeFallback.buildFallbackCandidates(rawDigits, dictEngine)
            if (fallbacks.isNotEmpty()) {
                finalList.addAll(fallbacks)
            } else {
                finalList.add(
                    Candidate(
                        word          = rawDigits,
                        input         = rawDigits,
                        priority      = 0,
                        matchedLength = 0,
                        pinyinCount   = 0,
                        pinyin        = null,
                        syllables     = 0,
                        acronym       = null
                    )
                )
            }
        }

        return ImeModeHandler.Output(
            candidates           = finalList,
            pinyinSidebar        = sidebarResult.syllables,
            sidebarTitle         = sidebarResult.title,
            resegmentPaths       = sidebarResult.resegmentPaths,
            composingPreviewText = null,
            enterCommitText      = null
        )
    }

    /**
     * R-D03：单字首屏保证。
     *
     * 遍历 finalList 前 SINGLE_CHAR_VISIBLE_WINDOW 个候选，
     * 若其中已有单字候选则直接返回（不操作）；
     * 若没有，则在 finalList 中找到第一个单字候选，
     * 将其移动到 SINGLE_CHAR_INJECT_POSITION（位置1，即第二候选槽）。
     *
     * 设计选择：
     *  - 注入位置为 1 而非 0，保证首位仍是打分最高的多字词（最佳首选）。
     *  - 只移动，不复制，finalList 中该候选不会出现两次。
     *  - 若列表中根本没有单字候选（如 singleCharMode 的反向 filter 后），
     *    则静默跳过，不插入占位符。
     */
    private fun ensureSingleCharVisible(finalList: ArrayList<Candidate>) {
        if (finalList.size <= SINGLE_CHAR_INJECT_POSITION) return

        // 检查前 SINGLE_CHAR_VISIBLE_WINDOW 个候选中是否已有单字
        val windowEnd = minOf(SINGLE_CHAR_VISIBLE_WINDOW, finalList.size)
        val alreadyVisible = (0 until windowEnd).any { finalList[it].word.length == 1 }
        if (alreadyVisible) return

        // 找到 window 之后第一个单字候选
        val singleCharIndex = (windowEnd until finalList.size)
            .firstOrNull { finalList[it].word.length == 1 }
            ?: return  // 列表中根本没有单字候选，跳过

        // 将其移动到注入位置
        val singleChar = finalList.removeAt(singleCharIndex)
        finalList.add(SINGLE_CHAR_INJECT_POSITION, singleChar)
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────

    private fun buildPlans(
        stackSegs: List<String>,
        autoPlans: List<CnT9SentencePlanner.PathPlan>,
        lockedSegmentIndices: List<Int>
    ): List<CnT9SentencePlanner.PathPlan> {
        if (stackSegs.isEmpty() && autoPlans.isEmpty()) return emptyList()

        if (autoPlans.isEmpty()) {
            return listOf(
                CnT9SentencePlanner.PathPlan(
                    rank           = 0,
                    segments       = stackSegs,
                    consumedDigits = 0
                )
            )
        }

        return autoPlans.map { auto ->
            CnT9SentencePlanner.PathPlan(
                rank           = auto.rank,
                segments       = stackSegs + auto.segments,
                consumedDigits = auto.consumedDigits
            )
        }
    }
}
