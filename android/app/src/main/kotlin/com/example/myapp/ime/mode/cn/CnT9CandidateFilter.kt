package com.example.myapp.ime.mode.cn

import android.util.LruCache
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner.PathPlan
import java.util.Locale
import kotlin.math.min

object CnT9CandidateFilter {

    private const val MAX_QUERY_PER_PLAN = 80

    // ── 性能优化：queryCandidates 结果缓存 ──────────────────────────
    // key = plans 的 text 列表 + lockedIndices 序列化字符串
    // 相同 plans + locked 状态下查询结果完全确定，缓存消除重复 SQL。
    // 容量 16：用户单次输入会话中活跃的 plan 组合数远小于此值。
    private val queryCache = LruCache<String, List<Candidate>>(16)

    fun invalidateQueryCache() {
        queryCache.evictAll()
    }

    fun queryCandidates(
        dict: Dictionary,
        plans: List<PathPlan>,
        lockedIndices: List<Int> = emptyList()
    ): List<Candidate> {
        if (plans.isEmpty()) return emptyList()

        // 构建缓存 key：plans 的 text 组合 + 锁定段下标
        val planKey = plans.joinToString(";") { it.text }
        val lockKey = if (lockedIndices.isEmpty()) "" else lockedIndices.sorted().joinToString(",")
        val cacheKey = if (lockKey.isEmpty()) planKey else "$planKey|L$lockKey"

        queryCache.get(cacheKey)?.let { return it }

        val result = queryCandidatesInternal(dict, plans, lockedIndices)
        queryCache.put(cacheKey, result)
        return result
    }

    private fun queryCandidatesInternal(
        dict: Dictionary,
        plans: List<PathPlan>,
        lockedIndices: List<Int>
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

    private fun passesHardFilter(
        cand: Candidate,
        plan: PathPlan,
        lockedSet: Set<Int> = emptySet()
    ): Boolean {
        if (plan.segments.isNotEmpty() && cand.word.length > plan.segments.size) {
            return false
        }

        val syllables = resolveCandidateSyllables(cand)
        if (syllables.isNotEmpty()) {
            if (matchesPlanHard(syllables, plan)) return true
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
            val actual   = candidateSyllables[i].lowercase(Locale.ROOT).replace("ü", "v")

            if (expected == actual || actual.startsWith(expected) || expected.startsWith(actual)) {
                continue
            }

            if (lockedSet.contains(i)) {
                return false
            }

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
            val actual   = candidateSyllables[i].lowercase(Locale.ROOT).replace("ü", "v")
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
