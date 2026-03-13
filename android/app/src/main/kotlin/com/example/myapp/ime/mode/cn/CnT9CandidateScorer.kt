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
 *  15. adjustedLengthBoost   长度偏好加分（含 R-D02 频率差阈值修正）
 *  16. penaltyScore (↑小)    惩罚分（生僻词/低频长词）
 *  17. priority              字典频率权重
 *  18. wordLength            词语长度（长词优先）
 *  19. syllables             音节数
 *  20. inputLength           input 长度
 *  21. word (字典序)          最终保证决定性排序
 *
 * ── R-D02 修复（问题5）──────────────────────────────────────────────
 * 首位候选策略「最长词优先，但频率差超过 20% 则短词优先」：
 *  - 在批量 buildScoreCache 阶段，找出所有候选中最高词频 maxPriority。
 *  - 对每个候选：若其 priority >= maxPriority * (1 - FREQ_DIFF_THRESHOLD)，
 *    则视为「频率差在阈值内」，保留其 lengthBoost（长词优势生效）。
 *  - 否则将 adjustedLengthBoost 压低为单字基准分（20），
 *    让高频短词凭 priority 维度胜出。
 *
 * lockedIndices 语义：稀疏升序列表，来自 CnT9SegmentLockMap.lockedSnapshot。
 */
object CnT9CandidateScorer {

    /**
     * 频率差阈值：候选 priority 低于最高频率的此比例时，长词优势失效。
     * 0.20 = 20%。
     */
    private const val FREQ_DIFF_THRESHOLD = 0.20

    /** 频率差超阈值时，长词 adjustedLengthBoost 退化为此基准分（单字级别）。 */
    private const val FALLBACK_LENGTH_BOOST = 20

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
        /** R-D02 修复：频率差阈值修正后的长度加分，用于排序。 */
        val adjustedLengthBoost: Int,
        val penaltyScore: Int,
        val priority: Int,
        val syllables: Int,
        val wordLength: Int,
        val inputLength: Int
    )

    /**
     * 为候选列表批量构建得分缓存。
     *
     * R-D02：在此阶段统一计算 maxPriority，然后为每个候选修正
     * adjustedLengthBoost，实现「频率差超 20% 则长词优势失效」。
     *
     * @param lockedIndices  已锁定段的稀疏下标集合；空列表表示无锁定段。
     */
    fun buildScoreCache(
        candidates: List<Candidate>,
        plans: List<PathPlan>,
        rawDigits: String,
        lockedIndices: List<Int>,
        userChoiceStore: CnT9UserChoiceStore? = null,
        contextWindow: CnT9ContextWindow? = null
    ): Map<Candidate, CandidateScore?> {
        if (candidates.isEmpty() || plans.isEmpty()) return emptyMap()

        // R-D02：找出所有候选中的最高词频（priority 最大值）
        val maxPriority = candidates.maxOf { it.priority }.coerceAtLeast(1)
        val freqFloor = (maxPriority * (1.0 - FREQ_DIFF_THRESHOLD)).toInt()

        val out = HashMap<Candidate, CandidateScore?>(candidates.size)
        for (cand in candidates) {
            val raw = bestScore(cand, plans, rawDigits, lockedIndices, userChoiceStore, contextWindow)
            if (raw == null) {
                out[cand] = null
                continue
            }
            // R-D02：若候选词频低于阈值下限，压低其 adjustedLengthBoost
            val adjusted = if (cand.priority >= freqFloor) {
                raw.lengthBoost
            } else {
                // 频率差超过 20%，长词优势退化为基准分
                minOf(raw.lengthBoost, FALLBACK_LENGTH_BOOST)
            }
            out[cand] = raw.copy(adjustedLengthBoost = adjusted)
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
                // 第15维：使用 adjustedLengthBoost（含频率差修正）
                .thenByDescending { scoreCache[it]?.adjustedLengthBoost ?: 0 }
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
        lockedIndices: List<Int>,
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
                lockedIndices      = lockedIndices,
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
        lockedIndices: List<Int>,
        contextBoost: Int,
        userBoost: Int,
        lengthBoost: Int,
        penaltyScore: Int
    ): CandidateScore {
        val planSegs = plan.segments
        val n        = min(planSegs.size, candidateSyllables.size)

        val lockedSet = if (lockedIndices.isEmpty()) emptySet<Int>()
                        else lockedIndices.toHashSet()

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
            val isLocked  = lockedSet.contains(i)

            when {
                expected == actual -> {
                    exactSegments++;  exactChars    += expected.length
                    prefixSegments++; prefixChars   += expected.length
                    consumedDigits += segDigits
                    if (isLocked) {
                        lockedExactSegments++;  lockedExactChars += expected.length
                        lockedPrefixSegments++
                    }
                }
                actual.startsWith(expected) -> {
                    prefixSegments++; prefixChars += expected.length
                    consumedDigits += segDigits
                    if (isLocked) lockedPrefixSegments++
                }
                expected.startsWith(actual) -> {
                    prefixSegments++; prefixChars += actual.length
                    consumedDigits += T9Lookup.encodeLetters(actual).length.coerceAtLeast(1)
                    if (isLocked) lockedPrefixSegments++
                }
                else -> { /* 无匹配 */ }
            }
        }

        val totalPlanDigits = planSegs.sumOf {
            T9Lookup.encodeLetters(it).length.coerceAtLeast(1)
        }
        val uncoveredDigits  = (totalPlanDigits - consumedDigits).coerceAtLeast(0)
        val syllableDistance = abs(planSegs.size - candidateSyllables.size)

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
            adjustedLengthBoost  = lengthBoost,   // 初始值与 lengthBoost 相同，由 buildScoreCache 修正
            penaltyScore         = penaltyScore,
            priority             = cand.priority,
            syllables            = cand.syllables,
            wordLength           = cand.word.length,
            inputLength          = cand.input.length
        )
    }

    // ── 私有：两个分数比较（与 sortCandidates 维度完全对齐）────────

    private fun isBetter(a: CandidateScore, b: CandidateScore): Boolean {
        if (a.lockedExactSegments  != b.lockedExactSegments)
            return a.lockedExactSegments  > b.lockedExactSegments
        if (a.lockedExactChars     != b.lockedExactChars)
            return a.lockedExactChars     > b.lockedExactChars
        if (a.lockedPrefixSegments != b.lockedPrefixSegments)
            return a.lockedPrefixSegments > b.lockedPrefixSegments
        if (a.exactSegments        != b.exactSegments)
            return a.exactSegments        > b.exactSegments
        if (a.exactChars           != b.exactChars)
            return a.exactChars           > b.exactChars
        if (a.prefixSegments       != b.prefixSegments)
            return a.prefixSegments       > b.prefixSegments
        if (a.prefixChars          != b.prefixChars)
            return a.prefixChars          > b.prefixChars
        if (a.consumedDigits       != b.consumedDigits)
            return a.consumedDigits       > b.consumedDigits
        if (a.uncoveredDigits      != b.uncoveredDigits)
            return a.uncoveredDigits      < b.uncoveredDigits
        if (a.syllableDistance     != b.syllableDistance)
            return a.syllableDistance     < b.syllableDistance
        val aExact = if (a.exactInput) 1 else 0
        val bExact = if (b.exactInput) 1 else 0
        if (aExact != bExact) return aExact > bExact
        if (a.planRank             != b.planRank)
            return a.planRank             < b.planRank
        if (a.contextBoost         != b.contextBoost)
            return a.contextBoost         > b.contextBoost
        if (a.userBoost            != b.userBoost)
            return a.userBoost            > b.userBoost
        // 第15维对齐：isBetter 中使用 adjustedLengthBoost
        if (a.adjustedLengthBoost  != b.adjustedLengthBoost)
            return a.adjustedLengthBoost  > b.adjustedLengthBoost
        if (a.penaltyScore         != b.penaltyScore)
            return a.penaltyScore         < b.penaltyScore
        if (a.priority             != b.priority)
            return a.priority             > b.priority
        if (a.wordLength           != b.wordLength)
            return a.wordLength           > b.wordLength
        if (a.syllables            != b.syllables)
            return a.syllables            > b.syllables
        if (a.inputLength          != b.inputLength)
            return a.inputLength          > b.inputLength
        return false
    }
}
