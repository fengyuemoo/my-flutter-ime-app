package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output = build(
        session = session,
        dictEngine = dictEngine,
        singleCharMode = singleCharMode,
        userChoiceStore = null,
        contextWindow = null,
        sidebarState = null
    )

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        sidebarState: CnT9SidebarState?
    ): ImeModeHandler.Output {
        val rawDigits = session.rawT9Digits
        val stackSegs = session.pinyinStack.map { it.lowercase(Locale.ROOT) }
        val focusedSegmentIndex = sidebarState?.focusedSegmentIndex ?: -1

        // ── Sidebar（一次性构建）────────────────────────────────────────
        val sidebarResult = CnT9SidebarBuilder.build(
            dictEngine = dictEngine,
            session = session,
            focusedSegmentIndex = focusedSegmentIndex,
            rawDigits = rawDigits
        )

        // ── 锁定段信息 ──────────────────────────────────────────────────
        // 已锁定段不参与 SentencePlanner 重算，只重算锁后的 rawDigits 部分
        val lockedSegmentIndices = sidebarState?.lockMap?.lockedSnapshot ?: emptyList()

        // ── 候选规划 ────────────────────────────────────────────────────
        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else emptyList()

        val plans = buildPlans(stackSegs, autoPlans, lockedSegmentIndices)

        val queried = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            CnT9CandidateFilter.queryCandidates(dictEngine, plans)
        } else emptyList()

        val filtered = if (singleCharMode) queried.filter { it.word.length == 1 } else queried

        val finalList = ArrayList<Candidate>(filtered)
        val lockedSegmentCount = lockedSegmentIndices.size.coerceAtLeast(stackSegs.size)

        val scoreCache = CnT9CandidateScorer.buildScoreCache(
            candidates = finalList,
            plans = plans,
            rawDigits = rawDigits,
            lockedSegmentCount = lockedSegmentCount,
            userChoiceStore = userChoiceStore,
            contextWindow = contextWindow
        )

        CnT9CandidateScorer.sortCandidates(finalList, scoreCache)

        if (finalList.size > MAX_DISPLAY_CANDIDATES) {
            finalList.subList(MAX_DISPLAY_CANDIDATES, finalList.size).clear()
        }

        // ── Fallback：词库查无结果时提供 Unicode CJK 兜底 ───────────────
        if (finalList.isEmpty() && rawDigits.isNotEmpty()) {
            val fallbacks = CnT9UnicodeFallback.buildFallbackCandidates(rawDigits, dictEngine)
            if (fallbacks.isNotEmpty()) {
                finalList.addAll(fallbacks)
            } else {
                finalList.add(
                    Candidate(
                        word = rawDigits, input = rawDigits, priority = 0,
                        matchedLength = 0, pinyinCount = 0, pinyin = null,
                        syllables = 0, acronym = null
                    )
                )
            }
        }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = sidebarResult.syllables,
            sidebarTitle = sidebarResult.title,
            resegmentPaths = sidebarResult.resegmentPaths,
            composingPreviewText = null,
            enterCommitText = null
        )
    }

    // ── 私有辅助 ─────────────────────────────────────────────────────────

    /**
     * 构建候选规划列表。
     *
     * 锁定策略：
     *  - 已锁定的 stackSegs 段不参与重算，直接作为前缀保留
     *  - autoPlans 接在锁定段之后拼接
     *  - 未锁定的 stackSegs 段同样作为前缀（与原逻辑一致）
     */
    private fun buildPlans(
        stackSegs: List<String>,
        autoPlans: List<CnT9SentencePlanner.PathPlan>,
        lockedSegmentIndices: List<Int>
    ): List<CnT9SentencePlanner.PathPlan> {
        if (stackSegs.isEmpty() && autoPlans.isEmpty()) return emptyList()

        if (autoPlans.isEmpty()) {
            return listOf(
                CnT9SentencePlanner.PathPlan(
                    rank = 0,
                    segments = stackSegs,
                    consumedDigits = 0
                )
            )
        }

        return autoPlans.map { auto ->
            CnT9SentencePlanner.PathPlan(
                rank = auto.rank,
                segments = stackSegs + auto.segments,
                consumedDigits = auto.consumedDigits
            )
        }
    }
}
