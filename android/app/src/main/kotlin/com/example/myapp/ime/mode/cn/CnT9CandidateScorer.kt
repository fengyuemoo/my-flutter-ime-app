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
        val lengthBoost: Int,       // 新增：词长偏好加分（CnT9LengthPolicy）
        val penaltyScore: Int,      // 新增：惩罚分（CnT9PenaltyPolicy，越小越好）
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
                .thenBy { scoreCache[it]?.uncoveredDigits ?: Int.MAX_VALUE }
                .thenBy { scoreCache[it]?.syllableDistance ?: Int.MAX_VALUE }
                .thenByDescending { if (scoreCache[it]?.exactInput == true) 1 else 0 }
                .thenBy { scoreCache[it]?.planRank ?: Int.MAX_VALUE }
                .thenByDescending { scoreCache[it]?.contextBoost ?: 0 }
                .thenByDescending { scoreCache[it]?.userBoost ?: 0 }
                .thenByDescending { scoreCache[it]?.lengthBoost ?: 0 }   // 新增
                .thenBy { scoreCache[it]?.penaltyScore ?: 0 }             // 新增（越小越好）
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
        lockedSegmentCount: Int,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?
    ): CandidateScore? {
        val syllables = CnT9CandidateFilter.resolveCandidateSyllables(cand)
        if (syllables.isEmpty()) return null

        val contextBoost = contextWindow?.getContextBoost(cand.word) ?: 0

        // 词长偏好分与惩罚分只依赖候选本身，在 plan 循环外计算一次
        val lengthBoost = CnT9LengthPolicy.score(
            wordLength = cand.word.length,
            digitLen = rawDigits.length
        )

        var best: CandidateScore? = null
        for (plan in plans) {
            val userBoost = userChoiceStore?.getBoost(
                CnT9PinyinSplitter.normalize(plan.text),
                cand.word
            ) ?: 0

            // 惩罚分需要 userBoost（有学习支撑时减半），在 plan 循环内计算
            val penaltyScore = CnT9PenaltyPolicy.penalty(
                priority = cand.priority,
                wordLength = cand.word.length,
                userBoost = userBoost
            )

            val score = scoreAgainstPlan(
                cand = cand,
                plan = plan,
                candidateSyllables = syllables,
                rawDigits = rawDigits,
                lockedSegmentCount = lockedSegmentCount,
                contextBoost = contextBoost,
                userBoost = userBoost,
                lengthBoost = lengthBoost,
                penaltyScore = penaltyScore
            )
            if (best == null || isBetter(score, best)) best = score
        }
        return best
    }

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
                        lockedExactSegments++; lockedExactChars += expected.length
                        lockedPrefixSegments++
                    }
                }
                actual.startsWith(expected) -> {
                    prefixSegments++; prefixChars +=
