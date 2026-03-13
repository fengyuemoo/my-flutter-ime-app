package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionSnapshot
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120

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
        // 主线程内同步快照 sidebarState 的两个不可变值，再进入 buildInternal
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

    /**
     * 快照版入口。调用方须在**主线程**完成 sidebarState 的快照，再把两个
     * 不可变值传入，不得将可变的 CnT9SidebarState 对象跨线程传递。
     *
     * @param snapshot          来自 [ComposingSession.buildSnapshot] 的只读快照
     * @param lockedIndices     主线程快照的已锁定段下标列表（来自 sidebarState.lockMap.lockedSnapshot）
     * @param focusedIndex      主线程快照的当前焦点段下标（来自 sidebarState.focusedSegmentIndex）
     */
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

    // ── 内部核心实现（两个公开入口共用）────────────────────────────────────

    private fun buildInternal(
        snapshot: ComposingSessionSnapshot,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        lockedIndices: List<Int>,       // 缺陷 B 修复：改为直接接收快照值，不再持有可变 sidebarState
        focusedIndex: Int               // 缺陷 B 修复：同上
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

        // 缺陷 A 修复：queryCandidates 补传 lockedIndices，锁定段模糊音禁止正式生效
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
