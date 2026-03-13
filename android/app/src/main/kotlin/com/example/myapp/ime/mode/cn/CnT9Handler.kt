package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120

    /**
     * R-P12 修复：rawDigits 最大长度限制。
     *
     * 超过此长度时 planAll / 字典查询不执行，避免超长串导致 ANR。
     * 规则要求最多 16 键，此处设为 16。
     */
    private const val MAX_RAW_DIGITS_LENGTH = 16

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
        val rawDigitsFull = session.rawT9Digits
        // R-P12 修复：截断超长输入串，保证 planAll 不对超长串做全量规划
        val rawDigits = if (rawDigitsFull.length > MAX_RAW_DIGITS_LENGTH)
            rawDigitsFull.take(MAX_RAW_DIGITS_LENGTH)
        else
            rawDigitsFull

        val stackSegs = session.pinyinStack.map { it.lowercase(Locale.ROOT) }
        val focusedSegmentIndex = sidebarState?.focusedSegmentIndex ?: -1

        // ── Sidebar（一次性构建）────────────────────────────────────────
        val sidebarResult = CnT9SidebarBuilder.build(
            dictEngine = dictEngine,
            session = session,
            focusedSegmentIndex = focusedSegmentIndex,
            rawDigits = rawDigits        // 使用截断后的 rawDigits
        )

        // ── 锁定段信息 ──────────────────────────────────────────────────
        val lockedSegmentIndices: List<Int> =
            sidebarState?.lockMap?.lockedSnapshot ?: emptyList()

        // ── 候选规划 ────────────────────────────────────────────────────
        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits = rawDigits,      // 使用截断后的 rawDigits
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

        val scoreCache = CnT9CandidateScorer.buildScoreCache(
            candidates      = finalList,
            plans           = plans,
            rawDigits       = rawDigits,
            lockedIndices   = lockedSegmentIndices,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow
        )

        CnT9CandidateScorer.sortCandidates(finalList, scoreCache)

        // R-D03：单字保护——若前 6 名中无单字，将得分最高的单字强制插入位置 3
        ensureSingleCharVisible(finalList, scoreCache)

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

    // ── 私有：单字可见性保护（R-D03）──────────────────────────────────

    /**
     * R-D03 修复：保证首屏（前 6 名）至少有一个单字候选可见。
     *
     * 若前 6 名均为多字词，则找到列表中分数最高的单字（原列表中排名最靠前的），
     * 强制插入到位置 3（0-based index 2），确保用户滑动前就能看到单字入口。
     * 位置 3 是经验值：前 2 个留给最佳多字词，第 3 个给单字。
     */
    private fun ensureSingleCharVisible(
        list: ArrayList<Candidate>,
        scoreCache: Map<Candidate, CnT9CandidateScorer.CandidateScore?>
    ) {
        if (list.size < 2) return

        val checkCount = minOf(6, list.size)
        val hasVisibleSingle = list.take(checkCount).any { it.word.length == 1 }
        if (hasVisibleSingle) return

        // 找到首个单字候选的当前位置
        val singleIdx = list.indexOfFirst { it.word.length == 1 }
        if (singleIdx < 0) return   // 无任何单字候选，无需处理

        val insertAt = minOf(2, list.size - 1)
        if (singleIdx <= insertAt) return  // 已在目标位置前，无需移动

        val single = list.removeAt(singleIdx)
        list.add(insertAt, single)
    }

    // ── 私有：构建规划列表 ─────────────────────────────────────────────

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
