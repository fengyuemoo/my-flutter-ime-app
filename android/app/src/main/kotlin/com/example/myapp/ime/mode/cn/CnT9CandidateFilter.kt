package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner.PathPlan
import java.util.Locale
import kotlin.math.min

/**
 * CN-T9 候选硬过滤器。
 *
 * ── R-C02 修复 ──────────────────────────────────────────────────────
 * 候选词字数（word.length）不得超过当前 activePath 的音节段数。
 *
 * ── R-S07 修复（问题4）──────────────────────────────────────────────
 * 锁定段（lockedIndices）的模糊音不生效：对锁定段的匹配必须是精确匹配
 * 或前缀匹配，不允许仅靠 CnT9FuzzyPinyin 模糊通过。
 * 此规则在硬过滤层（passesHardFilter）中强制执行，不依赖排序偏置。
 *
 * lockedIndices 为空时退化为原有逻辑，不影响无锁定段的场景。
 */
object CnT9CandidateFilter {

    private const val MAX_QUERY_PER_PLAN = 80

    /**
     * @param lockedIndices 已锁定段的稀疏下标集合（来自 CnT9SegmentLockMap.lockedSnapshot）。
     *                      空列表表示无锁定段，过滤行为与修复前完全一致。
     */
    fun queryCandidates(
        dict: Dictionary,
        plans: List<PathPlan>,
        lockedIndices: List<Int> = emptyList()
    ): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>(plans.size * MAX_QUERY_PER_PLAN)
        val lockedSet = lockedIndices.toHashSet()

        for (plan in plans) {
            if (plan.segments.isEmpty()) continue

            val exactByStack = dict.getSuggestionsFromPinyinStack(
                pinyinStack = plan.segments,
                rawDigits = ""
            )

            var taken = 0
            for (cand in exactByStack) {
                val normalized = normalizeCandidateAgainstPlan(cand, plan)
                if (!passesHardFilter(normalized, plan, lockedSet)) continue
                if (!out.containsKey(normalized.word)) {
                    out[normalized.word] = normalized
                    taken++
                    if (taken >= MAX_QUERY_PER_PLAN) break
                }
            }

            if (taken < MAX_QUERY_PER_PLAN) {
                val exactByJoined = dict.getSuggestions(
                    input = plan.text,
                    isT9 = false,
                    isChineseMode = true
                )
                for (cand in exactByJoined) {
                    val normalized = normalizeCandidateAgainstPlan(cand, plan)
                    if (!passesHardFilter(normalized, plan, lockedSet)) continue
                    if (!out.containsKey(normalized.word)) {
                        out[normalized.word] = normalized
                        taken++
                        if (taken >= MAX_QUERY_PER_PLAN) break
                    }
                }
            }
        }

        return out.values.toList()
    }

    private fun normalizeCandidateAgainstPlan(cand: Candidate, plan: PathPlan): Candidate {
        val count = CnT9SentencePlanner
            .splitConcatPinyinToSyllables(
                (cand.pinyin ?: cand.input).lowercase(Locale.ROOT).trim()
                    .replace("'", "").replace("ü", "v")
            )
            .size
            .coerceAtLeast(if (cand.syllables > 0) cand.syllables else 0)
            .coerceAtLeast(1)

        return cand.copy(
            input = plan.text,
            matchedLength = plan.consumedDigits,
            pinyinCount = count
        )
    }

    /**
     * 硬过滤主入口。
     *
     * @param lockedSet 已锁定段下标的 HashSet（由调用方从 lockedIndices 构建，避免重复转换）
     */
    private fun passesHardFilter(
        cand: Candidate,
        plan: PathPlan,
        lockedSet: Set<Int> = emptySet()
    ): Boolean {
        // R-C02：候选字数不得超过当前路径音节段数
        if (plan.segments.isNotEmpty() && cand.word.length > plan.segments.size) {
            return false
        }

        val syllables = resolveCandidateSyllables(cand)
        if (syllables.isNotEmpty()) {
            // 1. 精确/前缀硬匹配（优先，不受锁定影响）
            if (matchesPlanHard(syllables, plan)) return true
            // 2. 模糊音兜底 —— R-S07：锁定段必须精确通过，拒绝仅靠模糊音匹配锁定段
            if (matchesPlanFuzzy(syllables, plan, lockedSet)) return true
            return false
        }

        val candConcat = normalizePinyinConcat(cand.pinyin ?: cand.input)
        if (candConcat.isEmpty()) return false
        val planConcat = normalizePinyinConcat(plan.segments.joinToString(""))
        return planConcat.isNotEmpty() && candConcat == planConcat
    }

    private fun matchesPlanHard(
        candidateSyllables: List<String>,
        plan: PathPlan
    ): Boolean {
        val planSegments = plan.segments
        if (planSegments.isEmpty() || candidateSyllables.isEmpty()) return false

        val matchedPrefix = countMatchingPrefixSegments(candidateSyllables, planSegments)
        if (matchedPrefix >= 1) return true

        val candConcat = normalizePinyinConcat(candidateSyllables.joinToString(""))
        val planConcat = normalizePinyinConcat(planSegments.joinToString(""))
        return candConcat.isNotEmpty() && candConcat == planConcat
    }

    /**
     * 模糊音匹配（R-S07 修复版）。
     *
     * 对于位于 lockedSet 中的段（锁定段），仅允许精确匹配或前缀匹配通过；
     * 模糊音扩展（CnT9FuzzyPinyin.isFuzzyMatch）在锁定段上被明确禁止。
     * 对于未锁定段，行为与修复前相同（允许模糊音兜底）。
     */
    private fun matchesPlanFuzzy(
        candidateSyllables: List<String>,
        plan: PathPlan,
        lockedSet: Set<Int>
    ): Boolean {
        val planSegments = plan.segments
        if (planSegments.isEmpty() || candidateSyllables.isEmpty()) return false

        val n = min(candidateSyllables.size, planSegments.size)
        for (i in 0 until n) {
            val expected = planSegments[i].lowercase(Locale.ROOT)
            val actual   = candidateSyllables[i].lowercase(Locale.ROOT)

            // 精确或前缀匹配：无论是否锁定都允许
            if (expected == actual || actual.startsWith(expected) || expected.startsWith(actual)) {
                continue
            }

            // R-S07：锁定段不允许仅凭模糊音通过——直接判定不匹配
            if (lockedSet.contains(i)) {
                return i > 0   // 已有前置匹配段则截断通过，否则彻底拒绝
            }

            // 未锁定段：允许模糊音兜底
            if (!CnT9FuzzyPinyin.isFuzzyMatch(expected, actual)) {
                return i > 0
            }
        }
        return true
    }

    private fun countMatchingPrefixSegments(
        candidateSyllables: List<String>,
        planSegments: List<String>
    ): Int {
        val n = min(candidateSyllables.size, planSegments.size)
        var matched = 0
        for (i in 0 until n) {
            val expected = planSegments[i].lowercase(Locale.ROOT)
            val actual = candidateSyllables[i].lowercase(Locale.ROOT)
            if (expected == actual || actual.startsWith(expected)) {
                matched++
            } else {
                break
            }
        }
        return matched
    }

    fun resolveCandidateSyllables(cand: Candidate): List<String> {
        val py = cand.pinyin?.lowercase(Locale.ROOT)?.trim()
        if (!py.isNullOrEmpty()) {
            val split = CnT9SentencePlanner.splitConcatPinyinToSyllables(
                py.replace("'", "").replace("ü", "v")
            )
            if (split.isNotEmpty()) return split
        }

        val input = cand.input.lowercase(Locale.ROOT).trim()
            .replace("'", "").replace("ü", "v")
        if (input.isNotEmpty()) {
            val split = CnT9SentencePlanner.splitConcatPinyinToSyllables(input)
            if (split.isNotEmpty()) return split
        }

        return emptyList()
    }

    fun normalizePinyinConcat(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace("'", "").replace("ü", "v")
}
