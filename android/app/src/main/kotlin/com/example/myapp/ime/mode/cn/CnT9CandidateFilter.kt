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
 * 新增候选字数约束：候选词字数（word.length）不得超过当前 activePath
 * 的音节段数（plan.segments.size）。
 *
 * 理由：若候选词的汉字数多于可用音节数，则该词无论如何都无法被当前输入
 * 完整匹配，提前过滤可减少无效排序计算量，同时避免不合理的长词出现在顶部。
 *
 * 例外：pinyinStack（已物化段）参与构建 plan.segments，单字模糊兜底路径
 * 中 plan.segments 可能为空（stackSegs 为空 + autoPlans 为空），此时
 * 不施加约束，避免把 Fallback 候选过滤掉。
 */
object CnT9CandidateFilter {

    private const val MAX_QUERY_PER_PLAN = 80

    fun queryCandidates(
        dict: Dictionary,
        plans: List<PathPlan>
    ): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>(plans.size * MAX_QUERY_PER_PLAN)

        for (plan in plans) {
            if (plan.segments.isEmpty()) continue

            val exactByStack = dict.getSuggestionsFromPinyinStack(
                pinyinStack = plan.segments,
                rawDigits = ""
            )

            var taken = 0
            for (cand in exactByStack) {
                val normalized = normalizeCandidateAgainstPlan(cand, plan)
                if (!passesHardFilter(normalized, plan)) continue
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
                    if (!passesHardFilter(normalized, plan)) continue
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

    private fun passesHardFilter(cand: Candidate, plan: PathPlan): Boolean {
        // R-C02 修复：候选字数不得超过当前路径的音节段数
        // plan.segments 为空时跳过此约束（避免过滤 Fallback 路径下的兜底候选）
        if (plan.segments.isNotEmpty() && cand.word.length > plan.segments.size) {
            return false
        }

        val syllables = resolveCandidateSyllables(cand)
        if (syllables.isNotEmpty()) {
            // 1. 精确/前缀匹配（优先）
            if (matchesPlanHard(syllables, plan)) return true
            // 2. 模糊音兜底
            if (matchesPlanFuzzy(syllables, plan)) return true
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

    private fun matchesPlanFuzzy(
        candidateSyllables: List<String>,
        plan: PathPlan
    ): Boolean {
        val planSegments = plan.segments
        if (planSegments.isEmpty() || candidateSyllables.isEmpty()) return false

        val n = min(candidateSyllables.size, planSegments.size)
        for (i in 0 until n) {
            val expected = planSegments[i].lowercase(Locale.ROOT)
            val actual = candidateSyllables[i].lowercase(Locale.ROOT)
            if (!CnT9FuzzyPinyin.isFuzzyMatch(expected, actual)
                && !actual.startsWith(expected)
            ) {
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
