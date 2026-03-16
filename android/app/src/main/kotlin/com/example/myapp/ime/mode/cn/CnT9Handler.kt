package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionSnapshot
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120
    private const val SINGLE_CHAR_VISIBLE_WINDOW = 6
    private const val SINGLE_CHAR_INJECT_POSITION = 1

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
        val lockedIndices = sidebarState?.lockMap?.lockedSnapshot?.toList() ?: emptyList()
        val focusedIndex  = sidebarState?.focusedSegmentIndex ?: -1
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

    // 供 CnT9CandidateEngine 在会话结束/退格后主动清除缓存
    fun invalidateCaches() {
        CnT9SentencePlanner.invalidatePlanCache()
        CnT9CandidateFilter.invalidateQueryCache()
    }

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

        val sidebarResult = CnT9SidebarBuilder.buildFromSnapshot(
            dictEngine          = dictEngine,
            snapshot            = snapshot,
            focusedSegmentIndex = focusedIndex,
            rawDigits           = rawDigits
        )

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

        if (!singleCharMode) {
            ensureSingleCharVisible(finalList)
        }

        if (finalList.size > MAX_DISPLAY_CANDIDATES) {
            finalList.subList(MAX_DISPLAY_CANDIDATES, finalList.size).clear()
        }

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

    private fun ensureSingleCharVisible(finalList: ArrayList<Candidate>) {
        if (finalList.size <= SINGLE_CHAR_INJECT_POSITION) return

        val windowEnd = minOf(SINGLE_CHAR_VISIBLE_WINDOW, finalList.size)
        val alreadyVisible = (0 until windowEnd).any { finalList[it].word.length == 1 }
        if (alreadyVisible) return

        val singleCharIndex = (windowEnd until finalList.size)
            .firstOrNull { finalList[it].word.length == 1 }
            ?: return

        val singleChar = finalList.removeAt(singleCharIndex)
        finalList.add(SINGLE_CHAR_INJECT_POSITION, singleChar)
    }

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
