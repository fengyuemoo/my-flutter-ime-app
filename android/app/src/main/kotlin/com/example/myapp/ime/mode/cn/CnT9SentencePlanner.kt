package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import java.util.Locale

/**
 * CN-T9 句级路径规划器。
 *
 * 职责：
 *  把数字串（含手动切分点）解码为一组候选拼音路径（PathPlan），
 *  供 CnT9CandidateFilter / CnT9CandidateScorer 使用。
 *
 * 算法细节（Beam Search 单段解码）委托给 CnT9BeamDecoder。
 * 拼音切分工具委托给 CnT9PinyinSplitter。
 * 本类不依赖任何 UI 或 Session，可独立测试。
 */
object CnT9SentencePlanner {

    private const val MAX_PLAN_COUNT = 12

    data class PathPlan(
        val rank: Int,
        val segments: List<String>,
        val consumedDigits: Int
    ) {
        val text: String = segments.joinToString("'")
    }

    private data class PlanState(
        val segments: List<String>,
        val score: Int
    )

    // ── 公开 API ──────────────────────────────────────────────────

    /**
     * 对整串 digits 规划，返回最多 MAX_PLAN_COUNT 条路径，按分数降序。
     */
    fun planAll(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): List<PathPlan> {
        if (digits.isEmpty()) return emptyList()

        val parts = CnT9BeamDecoder.splitDigitsByCuts(digits, manualCuts)
        if (parts.isEmpty()) return emptyList()

        var combined = listOf(PlanState(segments = emptyList(), score = 0))

        for (part in parts) {
            val decodedPart = CnT9BeamDecoder.decodePart(part, dict, MAX_PLAN_COUNT)

            if (decodedPart.isEmpty()) {
                // 无法解码的段：用首字母占位
                val fallback = CnT9BeamDecoder.buildChoices(part, dict)
                    .firstOrNull()?.text ?: part.first().toString()
                combined = combined.map { state ->
                    state.copy(segments = state.segments + fallback, score = state.score + 10)
                }
                continue
            }

            val next = ArrayList<PlanState>()
            for (prefix in combined) {
                for (suffix in decodedPart) {
                    next.add(
                        PlanState(
                            segments = prefix.segments + suffix.segments,
                            score = prefix.score + suffix.score
                        )
                    )
                }
            }

            combined = next
                .sortedWith(
                    compareByDescending<PlanState> { it.score }
                        .thenByDescending { joinedCodeLength(it.segments) }
                        .thenBy { it.segments.joinToString("'") }
                )
                .distinctBy { it.segments.joinToString("'") }
                .take(MAX_PLAN_COUNT)
        }

        return combined.mapIndexed { index, state ->
            PathPlan(
                rank = index,
                segments = state.segments,
                consumedDigits = joinedCodeLength(state.segments).coerceAtMost(digits.length)
            )
        }
    }

    /**
     * 只解码 digits 的第一个音节（materialize 逐步推进时使用）。
     */
    fun decodeNextSegment(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): String? {
        if (digits.isEmpty()) return null
        return planAll(digits, manualCuts, dict).firstOrNull()?.segments?.firstOrNull()
    }

    // ── 工具函数（供内部 + CnT9CandidateFilter 使用）────────────

    fun joinedCodeLength(segments: List<String>): Int {
        var total = 0
        for (seg in segments) total += T9Lookup.encodeLetters(seg).length
        return total
    }

    /**
     * 把连写拼音字母串切分为音节列表。
     *
     * 委托给 CnT9PinyinSplitter.splitToSyllables()，
     * 并把 'v' 还原为 'ü' 与历史行为保持一致。
     *
     * @deprecated 优先直接调用 CnT9PinyinSplitter.splitToSyllables()；
     *             此函数仅为向后兼容保留，后续版本将移除。
     */
    fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        return CnT9PinyinSplitter.splitToSyllables(rawLower)
            .map { it.replace("v", "ü") }
    }
}
