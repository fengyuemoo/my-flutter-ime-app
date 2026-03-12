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
 * 排序维度（优先级从高到低）：
 *  1.  lockedExactSegments   锁定段精确匹配数
 *  2.  lockedExactChars      锁定段精确字符数
 *  3.  lockedPrefixSegments  锁定段前缀匹配数
 *  4.  exactSegments         全局精确匹配段数
 *  5.  exactChars            全局精确字符数
 *  6.  prefixSegments        前缀匹配段数
 *  7.  prefixChars           前缀字符数
 *  8.  consumedDigits        覆盖的数字位数
 *  9.  uncoveredDigits (↑小) 未覆盖数字数
 *  10. syllableDistance (↑小) 音节数差距
 *  11. exactInput            完整拼音精确匹配
 *  12. planRank (↑小)        路径规划排名
 *  13. contextBoost          上下文加分（bigram 偏置）
 *  14. userBoost             用户学习权重加分
 *  15. lengthBoost           词长偏好加分（2–4 字优先）
 *  16. penaltyScore (↑小)    惩罚分（生僻词/低频长词）
 *  17. priority              字典频率权重
 *  18. wordLength            词语长度（长词优先）
 *  19. syllables             音节数
 *  20. inputLength           input 长度
 *  21. word (字典序)          最终保证决定性排序
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
        val contextBoost: Int,
        val userBoost: Int,
        val lengthBoost: Int,
        val penaltyScore: Int,
        val priority: Int,
        val syllables: Int,
        val wordLength: Int,
        val inputLength: Int
    )

    fun buildScoreCache(
        candidates: List<Candidate>,
        plans: List<PathPlan>,
        rawDigits: String,
        lockedSegmentCount: Int,
        userChoiceStore: CnT9UserChoiceStore? = null,
        contextWindow: CnT9ContextWindow? = null
    ): Map<Candidate, CandidateScore?> {
        if (candidates.isEmpty() || plans.isEmpty()) return emptyMap()
        val out = HashMap<Candidate, CandidateScore?>(candidates.size)
        for (cand in candidates) {
            out[cand] = bestScore(
                cand, plans, rawDigits, lockedSegmentCount, userChoiceStore, contextWindow
            )
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
                .thenBy           { scoreCache[it]?.uncoveredDigits ?: Int.MAX_VALUE }
                .thenBy           { scoreCache[it]?.syllableDistance ?: Int.MAX_VALUE }
                .thenByDescending { if (scoreCache[it]?.exactInput == true) 1 else 0 }
                .thenBy           { scoreCache[it]?.planRank ?: Int.MAX_VALUE }
                .thenByDescending { scoreCache[it]?.contextBoost ?: 0 }
                .thenByDescending { scoreCache[it]?.userBoost ?: 0 }
                .thenByDescending { scoreCache[it]?.lengthBoost ?: 0 }
                .thenBy           { scoreCache[it]?.penaltyScore ?: 0 }
                .thenByDescending { scoreCache[it]?.priority ?: it.priority }
                .thenByDescending { scoreCache[it]?.wordLength ?: it.word.length }
                .thenByDescending { scoreCache[it]?.syllables ?: it.syllables }
                .thenByDescending { scoreCache[it]?.inputLength ?: it.input.length }
                .thenBy           { it.word }
        )
    }

    // ── 私有：最佳分数选取 ─────────────────────────────────────────

    private fun bestScore(
        cand: Candidate,
        plans: List<PathPlan>,
        rawDigits: String,
        lockedSegmentCount: Int,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?
    ): CandidateScore? {
        val syllables = CnT9CandidateFilter.resolveCandidateSyllables(cand)
        if (syllables.isEmpty()) return null

        val contextBoost = contextWindow?.getContextBoost(cand.word) ?: 0
        val lengthBoost  = CnT9LengthPolicy.score(
            wordLength = cand.word.length,
            digitLen   = rawDigits.length
        )

        var best: CandidateScore? = null
        for (plan in plans) {
            val userBoost    = userChoiceStore?.getBoost(
                CnT9PinyinSplitter.normalize(plan.text), cand.word
            ) ?: 0
            val penaltyScore = CnT9PenaltyPolicy.penalty(
                priority   = cand.priority,
                wordLength = cand.word.length,
                userBoost  = userBoost
            )
            val score = scoreAgainstPlan(
                cand               = cand,
                plan               = plan,
                candidateSyllables = syllables,
                rawDigits          = rawDigits,
                lockedSegmentCount = lockedSegmentCount,
                contextBoost       = contextBoost,
                userBoost          = userBoost,
                lengthBoost        = lengthBoost,
                penaltyScore       = penaltyScore
            )
            if (best == null || isBetter(score, best)) best = score
        }
        return best
    }

    // ── 私有：单 plan 打分 ─────────────────────────────────────────

    private fun scoreAgainstPlan(
        cand: Candidate,
        plan: PathPlan,
        candidateSyllables: List<String>,
        rawDigits: String,
        lockedSegmentCount: Int,
        contextBoost: Int,
        userBoost: Int,
        lengthBoost: Int,
        penaltyScore: Int
    ): CandidateScore {
        val planSegs      = plan.segments
        val n             = min(planSegs.size, candidateSyllables.size)
        val effectiveLocked = lockedSegmentCount.coerceAtMost(planSegs.size)

        var lockedExactSegments  = 0
        var lockedExactChars     = 0
        var lockedPrefixSegments = 0
        var exactSegments        = 0
        var exactChars           = 0
        var prefixSegments       = 0
        var prefixChars          = 0
        var consumedDigits       = 0

        for (i in 0 until n) {
            val expected  = planSegs[i].lowercase(Locale.ROOT)
            val actual    = candidateSyllables[i].lowercase(Locale.ROOT)
            val segDigits = T9Lookup.encodeLetters(expected).length.coerceAtLeast(1)

            when {
                expected == actual -> {
                    exactSegments++;  exactChars    += expected.length
                    prefixSegments++; prefixChars   += expected.length
                    consumedDigits += segDigits
                    if (i < effectiveLocked) {
                        lockedExactSegments++;  lockedExactChars += expected.length
                        lockedPrefixSegments++
                    }
                }
                actual.startsWith(expected) -> {
                    // 候选音节以规划段为前缀（如规划 "zh" 候选 "zhong"）
                    prefixSegments++; prefixChars += expected.length
                    consumedDigits += segDigits
                    if (i < effectiveLocked) lockedPrefixSegments++
                }
                expected.startsWith(actual) -> {
                    // 规划段以候选音节为前缀（如规划 "zhong" 候选 "zh"）
                    prefixSegments++; prefixChars += actual.length
                    consumedDigits += T9Lookup.encodeLetters(actual).length.coerceAtLeast(1)
                    if (i < effectiveLocked) lockedPrefixSegments++
                }
                else -> {
                    // 无匹配：不计入 consumedDigits，直接跳过
                }
            }
        }

        // 未覆盖数字数：rawDigits 长度减去实际覆盖
        val totalPlanDigits = planSegs.sumOf {
            T9Lookup.encodeLetters(it).length.coerceAtLeast(1)
        }
        val uncoveredDigits  = (totalPlanDigits - consumedDigits).coerceAtLeast(0)
        val syllableDistance = abs(planSegs.size - candidateSyllables.size)

        // 完整拼音精确匹配：候选所有音节与规划段一一对应且完全相等
        val exactInput = candidateSyllables.size == planSegs.size &&
            candidateSyllables.zip(planSegs).all { (a, b) ->
                a.lowercase(Locale.ROOT) == b.lowercase(Locale.ROOT)
            }

        return CandidateScore(
            lockedExactSegments  = lockedExactSegments,
            lockedExactChars     = lockedExactChars,
            lockedPrefixSegments = lockedPrefixSegments,
            exactSegments        = exactSegments,
            exactChars           = exactChars,
            prefixSegments       = prefixSegments,
            prefixChars          = prefixChars,
            consumedDigits       = consumedDigits,
            uncoveredDigits      = uncoveredDigits,
            syllableDistance     = syllableDistance,
            exactInput           = exactInput,
            planRank             = plan.rank,
            contextBoost         = contextBoost,
            userBoost            = userBoost,
            lengthBoost          = lengthBoost,
            penaltyScore         = penaltyScore,
            priority             = cand.priority,
            syllables            = cand.syllables,
            wordLength           = cand.word.length,
            inputLength          = cand.input.length
        )
    }

    // ── 私有：两个分数比较（与 sortCandidates 维度完全对齐）────────

    /**
     * 判断 candidate a 的得分是否优于 b。
     * 与 sortCandidates 中的 Comparator 维度顺序完全一致，
     * 保证 bestScore 选出的最优分和排序结果一致。
     */
    private fun isBetter(a: CandidateScore, b: CandidateScore): Boolean {
        // 1. lockedExactSegments ↑
        if (a.lockedExactSegments  != b.lockedExactSegments)
            return a.lockedExactSegments  > b.lockedExactSegments
        // 2. lockedExactChars ↑
        if (a.lockedExactChars     != b.lockedExactChars)
            return a.lockedExactChars     > b.lockedExactChars
        // 3. lockedPrefixSegments ↑
        if (a.lockedPrefixSegments != b.lockedPrefixSegments)
            return a.lockedPrefixSegments > b.lockedPrefixSegments
        // 4. exactSegments ↑
        if (a.exactSegments        != b.exactSegments)
            return a.exactSegments        > b.exactSegments
        // 5. exactChars ↑
        if (a.exactChars           != b.exactChars)
            return a.exactChars           > b.exactChars
        // 6. prefixSegments ↑
        if (a.prefixSegments       != b.prefixSegments)
            return a.prefixSegments       > b.prefixSegments
        // 7. prefixChars ↑
        if (a.prefixChars          != b.prefixChars)
            return a.prefixChars          > b.prefixChars
        // 8. consumedDigits ↑
        if (a.consumedDigits       != b.consumedDigits)
            return a.consumedDigits       > b.consumedDigits
        // 9. uncoveredDigits ↓
        if (a.uncoveredDigits      != b.uncoveredDigits)
            return a.uncoveredDigits      < b.uncoveredDigits
        // 10. syllableDistance ↓
        if (a.syllableDistance     != b.syllableDistance)
            return a.syllableDistance     < b.syllableDistance
        // 11. exactInput ↑
        val aExact = if (a.exactInput) 1 else 0
        val bExact = if (b.exactInput) 1 else 0
        if (aExact != bExact) return aExact > bExact
        // 12. planRank ↓
        if (a.planRank             != b.planRank)
            return a.planRank             < b.planRank
        // 13. contextBoost ↑
        if (a.contextBoost         != b.contextBoost)
            return a.contextBoost         > b.contextBoost
        // 14. userBoost ↑
        if (a.userBoost            != b.userBoost)
            return a.userBoost            > b.userBoost
        // 15. lengthBoost ↑
        if (a.lengthBoost          != b.lengthBoost)
            return a.lengthBoost          > b.lengthBoost
        // 16. penaltyScore ↓
        if (a.penaltyScore         != b.penaltyScore)
            return a.penaltyScore         < b.penaltyScore
        // 17. priority ↑
        if (a.priority             != b.priority)
            return a.priority             > b.priority
        // 18. wordLength ↑
        if (a.wordLength           != b.wordLength)
            return a.wordLength           > b.wordLength
        // 19. syllables ↑
        if (a.syllables            != b.syllables)
            return a.syllables            > b.syllables
        // 20. inputLength ↑
        if (a.inputLength          != b.inputLength)
            return a.inputLength          > b.inputLength
        // 21. 完全相同 → a 不优于 b
        return false
    }
}
