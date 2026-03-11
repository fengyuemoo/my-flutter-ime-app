package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120
    private const val MAX_SIDEBAR_ITEMS = 24

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output = build(session, dictEngine, singleCharMode, null, null)

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?
    ): ImeModeHandler.Output {
        val rawDigits = session.rawT9Digits
        val stackSegs = session.pinyinStack.map { it.lowercase(Locale.ROOT) }

        val sidebar = buildSidebar(dictEngine, rawDigits)

        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else emptyList()

        val plans = buildPlans(stackSegs, autoPlans)

        val queried = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            CnT9CandidateFilter.queryCandidates(dictEngine, plans)
        } else emptyList()

        val filtered = if (singleCharMode) queried.filter { it.word.length == 1 } else queried

        val finalList = ArrayList<Candidate>(filtered)
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

        // Fallback：词库查无结果时，提供 Unicode CJK 兜底
        if (finalList.isEmpty() && rawDigits.isNotEmpty()) {
            val unicodeFallbacks = CnT9UnicodeFallback.buildFallbackCandidates(rawDigits, dictEngine)
            if (unicodeFallbacks.isNotEmpty()) {
                finalList.addAll(unicodeFallbacks)
            } else {
                // 最终兜底：直出原始数字串（保证用户不会卡死）
                finalList.add(
                    Candidate(
                        word = rawDigits,
                        input = rawDigits,
                        priority = 0,
                        matchedLength = 0,
                        pinyinCount = 0,
                        pinyin = null,
                        syllables = 0,
                        acronym = null
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

    private fun buildSidebar(dictEngine: Dictionary, rawDigits: String): List<String> {
        if (!dictEngine.isLoaded || rawDigits.isEmpty()) return emptyList()

        val fromDict = dictEngine.getPinyinPossibilities(rawDigits)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        return (fromDict + com.example.myapp.dict.impl.T9Lookup
            .charsFromDigit(rawDigits.first())
            .map { it.lowercase(Locale.ROOT) })
            .distinct()
            .take(MAX_SIDEBAR_ITEMS)
    }

    private fun buildPlans(
        stackSegs: List<String>,
        autoPlans: List<CnT9SentencePlanner.PathPlan>
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
