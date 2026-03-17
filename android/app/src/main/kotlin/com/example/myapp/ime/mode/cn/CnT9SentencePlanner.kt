package com.example.myapp.ime.mode.cn

import android.util.LruCache
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import java.util.Locale

object CnT9SentencePlanner {

    private const val MAX_PLAN_COUNT = 12

    data class PathPlan(
        val rank: Int,
        val segments: List<String>,
        val consumedDigits: Int
    ) {
        val text: String = segments.joinToString("'")

        // ── 性能优化：预计算每段的 T9 编码长度 ──────────────────────
        // scoreAgainstPlan 和 sumOf(totalPlanDigits) 需要对每个 segment 调用
        // T9Lookup.encodeLetters()，plan 构建后 segments 不再变化，
        // 提前计算一次，消除 CnT9CandidateScorer 热路径中的重复调用。
        val segDigitLengths: List<Int> = segments.map {
            T9Lookup.encodeLetters(it).length.coerceAtLeast(1)
        }

        val totalDigitLength: Int = segDigitLengths.sum()
    }

    private data class PlanState(
        val segments: List<String>,
        val score: Int
    )

    // ── 性能优化：planAll 结果缓存 ──────────────────────────────────
    // key = "$digits|${manualCuts.joinToString(",")}"
    // 相同输入状态的 planAll 结果完全确定，缓存后消除 Beam Search 重复计算。
    // 容量 32 覆盖用户连续输入时所有活跃前缀（最长8位 × 多切分组合）。
    // 注意：dict 是无状态查询器，planAll 结果只依赖 digits 和 manualCuts。
    private val planCache = LruCache<String, List<PathPlan>>(32)

    fun invalidatePlanCache() {
        planCache.evictAll()
    }

    // ── 公开 API ──────────────────────────────────────────────────

    fun planAll(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): List<PathPlan> {
        if (digits.isEmpty()) return emptyList()

        val cacheKey = if (manualCuts.isEmpty()) digits
                       else "$digits|${manualCuts.sorted().joinToString(",")}"

        planCache.get(cacheKey)?.let { return it }

        val result = planAllInternal(digits, manualCuts, dict)
        planCache.put(cacheKey, result)
        return result
    }

    private fun planAllInternal(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): List<PathPlan> {
        val parts = CnT9BeamDecoder.splitDigitsByCuts(digits, manualCuts)
        if (parts.isEmpty()) return emptyList()

        var combined = listOf(PlanState(segments = emptyList(), score = 0))

        for (part in parts) {
            val decodedPart = CnT9BeamDecoder.decodePart(part, dict, MAX_PLAN_COUNT)

            if (decodedPart.isEmpty()) {
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

    fun decodeNextSegment(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): String? {
        if (digits.isEmpty()) return null
        return planAll(digits, manualCuts, dict).firstOrNull()?.segments?.firstOrNull()
    }

    // ── 工具函数 ──────────────────────────────────────────────────

    fun joinedCodeLength(segments: List<String>): Int {
        var total = 0
        for (seg in segments) total += T9Lookup.encodeLetters(seg).length
        return total
    }

    fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        return CnT9PinyinSplitter.splitToSyllables(rawLower)
    }
}
