package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner.PathPlan
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * CN-T9 候选打分与排序。
 *
 * 规则：分层打分（locked精确 > locked前缀 > 精确 > 前缀 > 覆盖数字 > 音节距离 > ...），
 * 相同输入下顺序稳定（stable sort + 多维 thenBy 保证决定性排序）。
 */
object CnT9CandidateScorer {

    data class CandidateScore(
        val lockedExactSegments: Int,
        val lockedExactChars: Int,
        val lockedPrefixSegments: Int,
        val exactSegments: Int,
        val exactChars: Int,
        val prefixSegments: Int,
        val prefixChars: Int,
        val consumedDigits: Int,
        val uncoveredDigits: Int,
        val syllableDistance: Int,
        val exactInput: Boolean,
        val planRank: Int,
        val priority: Int,
        val syllables: Int,
        val wordLength: Int,
        val inputLength: Int
    )

    fun buildScoreCache(
        candidates: List<Candidate>,
        plans: List<PathPlan>,
        rawDigits: String,
        lockedSegmentCount: Int
    ): Map<Candidate, CandidateScore?> {
        if (candidates.isEmpty() || plans.isEmpty()) return emptyMap()

        val out = HashMap<Candidate, CandidateScore?>(candidates.size)
        for (cand in candidates) {
            out[cand] = bestScore(cand, plans, rawDigits, lockedSegmentCount)
        }
        return out
    }

    fun sortCandidates(
        candidates: ArrayList<Candidate>,
        scoreCache: Map<Candidate, CandidateScore?>
    ) {
        if (candidates.isEmpty()) return

        candidates.sortWith(
            compareByDescending<Candidate> { scoreCache[it]?.lockedExactSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.lockedExactChars ?: 0 }
                .thenByDescending { scoreCache[it]?.lockedPrefixSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.exactSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.exactChars ?: 0 }
                .thenByDescending { scoreCache[it]?.prefixSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.prefixChars ?: 0 }
                .thenByDescending { scoreCache[it]?.consumedDigits ?: 0 }
                .thenBy { scoreCache[it]?.uncoveredDigits ?: Int.MAX_VALUE }
                .thenBy { scoreCache[it]?.syllableDistance ?: Int.MAX_VALUE }
                .thenByDescending { if (scoreCache[it]?.exactInput == true) 1 else 0 }
                .thenBy { scoreCache[it]?.planRank ?: Int.MAX_VALUE }
                .thenByDescending { scoreCache[it]?.priority ?: it.priority }
                .thenByDescending { scoreCache[it]?.wordLength ?: it.word.length }
                .thenByDescending { scoreCache[it]?.syllables ?: it.syllables }
                .thenByDescending { scoreCache[it]?.inputLength ?: it.input.length }
                .thenBy { it.word }
        )
    }

    private fun bestScore(
        cand: Candidate,
        plans: List<PathPlan>,
        rawDigits: String,
        lockedSegmentCount: Int
    ): CandidateScore? {
        val syllables = CnT9CandidateFilter.resolveCandidateSyllables(cand)
        if (syllables.isEmpty()) return null

        var best: CandidateScore? = null
        for (plan in plans) {
            val score = scoreAgainstPlan(cand, plan, syllables, rawDigits, lockedSegmentCount)
            if (best == null || isBetter(score, best)) best = score
        }
        return best
    }

    private fun scoreAgainstPlan(
        cand: Candidate,
        plan: PathPlan,
        candidateSyllables: List<String>,
        rawDigits: String,
        lockedSegmentCount: Int
    ): CandidateScore {
        val planSegs = plan.segments
        val n = min(planSegs.size, candidateSyllables.size)
        val effectiveLocked = lockedSegmentCount.coerceAtMost(planSegs.size)

        var lockedExactSegments = 0
        var lockedExactChars = 0
        var lockedPrefixSegments = 0
        var exactSegments = 0
        var exactChars = 0
        var prefixSegments = 0
        var prefixChars = 0
        var consumedDigits = 0

        for (i in 0 until n) {
            val expected = planSegs[i].lowercase(Locale.ROOT)
            val actual = candidateSyllables[i].lowercase(Locale.ROOT)
            val segDigits = T9Lookup.encodeLetters(expected).length.coerceAtLeast(1)

            when {
                expected == actual -> {
                    exactSegments++; exactChars += expected.length
                    prefixSegments++; prefixChars += expected.length
                    consumedDigits += segDigits
                    if (i < effectiveLocked) {
                        lockedExactSegments++; lockedExactChars += expected.length; lockedPrefixSegments++
                    }
                }
                actual.startsWith(expected) -> {
                    prefixSegments++; prefixChars += expected.length
                    consumedDigits += segDigits
                    if (i < effectiveLocked) lockedPrefixSegments++
                }
                else -> break
            }
        }

        val planConcat = CnT9CandidateFilter.normalizePinyinConcat(planSegs.joinToString(""))
        val candConcat = CnT9CandidateFilter.normalizePinyinConcat(cand.pinyin ?: cand.input)

        return CandidateScore(
            lockedExactSegments = lockedExactSegments,
            lockedExactChars = lockedExactChars,
            lockedPrefixSegments = lockedPrefixSegments,
            exactSegments = exactSegments,
            exactChars = exactChars,
            prefixSegments = prefixSegments,
            prefixChars = prefixChars,
            consumedDigits = consumedDigits,
            uncoveredDigits = (rawDigits.length - consumedDigits).coerceAtLeast(0),
            syllableDistance = abs(candidateSyllables.size - planSegs.size),
            exactInput = candConcat.isNotEmpty() && candConcat == planConcat,
            planRank = plan.rank,
            priority = cand.priority,
            syllables = if (cand.syllables > 0) cand.syllables else candidateSyllables.size,
            wordLength = cand.word.length,
            inputLength = cand.input.length
        )
    }

    private fun isBetter(a: CandidateScore, b: CandidateScore): Boolean {
        return when {
            a.lockedExactSegments != b.lockedExactSegments -> a.lockedExactSegments > b.lockedExactSegments
            a.lockedExactChars != b.lockedExactChars -> a.lockedExactChars > b.lockedExactChars
            a.lockedPrefixSegments != b.lockedPrefixSegments -> a.lockedPrefixSegments > b.lockedPrefixSegments
            a.exactSegments != b.exactSegments -> a.exactSegments > b.exactSegments
            a.exactChars != b.exactChars -> a.exactChars > b.exactChars
            a.prefixSegments != b.prefixSegments -> a.prefixSegments > b.prefixSegments
            a.prefixChars != b.prefixChars -> a.prefixChars > b.prefixChars
            a.consumedDigits != b.consumedDigits -> a.consumedDigits > b.consumedDigits
            a.uncoveredDigits != b.uncoveredDigits -> a.uncoveredDigits < b.uncoveredDigits
            a.syllableDistance != b.syllableDistance -> a.syllableDistance < b.syllableDistance
            a.exactInput != b.exactInput -> a.exactInput
            a.planRank != b.planRank -> a.planRank < b.planRank
            a.priority != b.priority -> a.priority > b.priority
            a.wordLength != b.wordLength -> a.wordLength > b.wordLength
            a.syllables != b.syllables -> a.syllables > b.syllables
            else -> a.inputLength > b.inputLength
        }
    }
}
