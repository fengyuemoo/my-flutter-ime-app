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
 *
 * ── splitConcatPinyinToSyllables 清理说明 ────────────────────────
 * 原实现在返回值中执行 .map { it.replace("v", "ü") }，
 * 与系统"内部统一用 v 表示 ü 声母"的约定相悖，是维护陷阱。
 * 所有调用方（CnT9CandidateFilter / CnT9CandidateScorer）在比较时
 * 都已做了 .replace("ü","v") 来抵消此反转，造成双重转换的混乱。
 *
 * 修复：去掉 v→ü 反转，直接返回 CnT9PinyinSplitter.splitToSyllables()
 * 的原始结果（全程 v 格式）。调用方的 .replace("ü","v") 仍保留
 * （对已经是 v 的字符串无害，且保持防御性）。
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
     * 直接委托给 CnT9PinyinSplitter.splitToSyllables()。
     * 返回值全程使用 v 表示 ü 声母（与系统内部约定一致）。
     *
     * 历史说明：
     *  原实现在返回值中执行 .map { it.replace("v", "ü") }，
     *  导致调用方需要反向 replace("ü","v") 来抵消，造成双重转换。
     *  现已去掉该反转，调用方的 replace("ü","v") 对 v 字符串无害，
     *  可安全保留作为防御性代码，无需修改调用方。
     */
    fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        return CnT9PinyinSplitter.splitToSyllables(rawLower)
        // 注意：不再执行 .map { it.replace("v", "ü") }
        // 调用方（CnT9CandidateFilter / CnT9CandidateScorer）中
        // 已有的 .replace("ü","v") 对全 v 字符串无害，可安全保留。
    }
}
