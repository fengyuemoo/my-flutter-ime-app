package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

/**
 * CN-T9 候选管线协调器（Handler）。
 *
 * 职责：
 *  将 session 状态、字典、评分参数组装成一次完整的候选生成流水线，
 *  返回 ImeModeHandler.Output（candidates + pinyinSidebar）。
 *
 * 不包含：
 *  - Sidebar 构建逻辑  → CnT9SidebarBuilder
 *  - 拼音切分工具      → CnT9PinyinSplitter
 *  - 路径规划          → CnT9SentencePlanner
 *  - 候选过滤          → CnT9CandidateFilter
 *  - 候选评分          → CnT9CandidateScorer
 *  - Unicode/生僻字兜底 → CnT9UnicodeFallback
 */
object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output = build(session, dictEngine, singleCharMode, null, null, -1)

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        focusedSegmentIndex: Int = -1
    ): ImeModeHandler.Output {

        val rawDigits = session.rawT9Digits
        val stackSegs = session.pinyinStack.map { it.lowercase(Locale.ROOT) }

        // ── Sidebar ───────────────────────────────────────────────
        val sidebarDigits = CnT9SidebarBuilder.resolveSidebarDigits(
            session, focusedSegmentIndex, rawDigits
        )
        val sidebar = CnT9SidebarBuilder.buildSidebar(dictEngine, sidebarDigits)

        // ── 路径规划 ──────────────────────────────────────────────
        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else emptyList()

        val plans = buildPlans(stackSegs, autoPlans)

        // ── 候选查询 & 过滤 ───────────────────────────────────────
        val queried = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            CnT9CandidateFilter.queryCandidates(dictEngine, plans)
        } else emptyList()

        val filtered = if (singleCharMode) queried.filter { it.word.length == 1 } else queried

        // ── 候选评分 & 排序 ───────────────────────────────────────
        val finalList = ArrayList(filtered)
        val lockedSegmentCount = stackSegs.size

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

        // ── Fallback：词库查无结果时的三级生僻字兜底 ─────────────
        if (finalList.isEmpty() && rawDigits.isNotEmpty()) {
            val fallbacks = CnT9UnicodeFallback.buildFallbackCandidates(
                rawDigits = rawDigits,
                dictEngine = dictEngine,
                plans = plans
            )
            if (fallbacks.isNotEmpty()) {
                finalList.addAll(fallbacks)
            } else {
                finalList.add(
                    Candidate(
                        word = rawDigits, input = rawDigits, priority = 0,
                        matchedLength = 0, pinyinCount = 0,
                        pinyin = null, syllables = 0, acronym = null
                    )
                )
            }
        }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = sidebar,
            composingPreviewText = null,
            enterCommitText = null
        )
    }

    private fun buildPlans(
        stackSegs: List<String>,
        autoPlans: List<CnT9SentencePlanner.PathPlan>
    ): List<CnT9SentencePlanner.PathPlan> {
        if (stackSegs.isEmpty() && autoPlans.isEmpty()) return emptyList()

        if (autoPlans.isEmpty()) {
            return listOf(
                CnT9SentencePlanner.PathPlan(rank = 0, segments = stackSegs, consumedDigits = 0)
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
