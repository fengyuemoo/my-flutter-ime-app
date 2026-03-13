package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner.PathPlan
import java.util.Locale
import kotlin.math.min

/**
 * CN-T9 候选硬过滤器。
 *
 * 职责：
 *  1. 从字典查询原始候选
 *  2. 用"读音必须与当前拼音路径匹配"的硬规则过滤
 *     - 精确/前缀匹配（严格）
 *     - 模糊音匹配（CnT9FuzzyPinyin，宽松兜底）
 * 不做软排序，不依赖 UI。
 *
 * ── 候选来源分层说明（对应规则清单「来源优先级」）────────────────────────
 *
 * 规则清单描述的"用户词库 > 系统词库高频词 > 短语成语 > 单字"分层，
 * 在当前架构中**不通过数据库分表**实现，而是由两层机制共同保证：
 *
 *  1. 【数据库层】单张表，所有词条靠 COL_FREQ（词频）区分高低：
 *       高频系统词（priority > 40000）→ CnT9CandidateScorer 无惩罚
 *       低频/生僻词（priority < 5000） → penaltyScore 施压降排名
 *
 *  2. 【排序层】CnT9UserChoiceStore 运行时加分（userBoost，最高 +200）：
 *       用户选过的词在 Scorer 第 14 维获得加权，效果等价于"用户词库优先"。
 *       含时间衰减（30/90/180 天三档）+ streak 加乘（最多 ×1.4）。
 *
 * 因此本类只负责"硬过滤"（读音匹配），不需要感知词条来源。
 * 软排序完全交由 CnT9CandidateScorer 处理。
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

    /**
     * 模糊音匹配：对每个 plan 音节，检查候选对应音节是否模糊等价。
     * 要求至少第一个音节模糊匹配，整体才通过。
     */
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
                return i > 0  // 至少匹配了 1 段才算通过
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
