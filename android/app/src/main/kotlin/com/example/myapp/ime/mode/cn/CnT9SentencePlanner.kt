package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import java.util.Locale

/**
 * CN-T9 句级路径规划器。
 *
 * 职责：把数字串（含手动切分点）解码为一组候选拼音路径（PathPlan），
 * 供 CnT9CandidateFilter / CnT9CandidateScorer 使用。
 * 本类不依赖任何 UI 或 Session，可独立测试。
 */
object CnT9SentencePlanner {

    private val normalizedPinyinSet: Set<String> by lazy {
        PinyinTable.allPinyins
            .map { it.lowercase(Locale.ROOT).replace("ü", "v") }
            .toHashSet()
    }

    private val maxPyLen: Int by lazy {
        normalizedPinyinSet.maxOfOrNull { it.length } ?: 8
    }

    private const val MAX_PLAN_COUNT = 12
    private const val PART_BEAM_WIDTH = 8
    private const val PART_STEP_OPTIONS = 8

    data class PathPlan(
        val rank: Int,
        val segments: List<String>,
        val consumedDigits: Int
    ) {
        val text: String = segments.joinToString("'")
    }

    private data class Choice(
        val text: String,
        val codeLen: Int,
        val score: Int
    )

    private data class PartState(
        val pos: Int,
        val segments: List<String>,
        val score: Int
    )

    /**
     * 对整串 digits 规划，返回最多 MAX_PLAN_COUNT 条路径，按分数降序。
     */
    fun planAll(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): List<PathPlan> {
        if (digits.isEmpty()) return emptyList()

        val parts = splitDigitsByCuts(digits, manualCuts)
        if (parts.isEmpty()) return emptyList()

        var combined = listOf(PartState(pos = 0, segments = emptyList(), score = 0))

        for (part in parts) {
            val decodedPart = decodePart(part, dict)
            if (decodedPart.isEmpty()) {
                combined = combined.map { state ->
                    state.copy(
                        segments = state.segments + defaultLetterForDigit(part.first()),
                        score = state.score + 10
                    )
                }
                continue
            }

            val next = ArrayList<PartState>()
            for (prefix in combined) {
                for (suffix in decodedPart) {
                    next.add(
                        PartState(
                            pos = 0,
                            segments = prefix.segments + suffix.segments,
                            score = prefix.score + suffix.score
                        )
                    )
                }
            }

            combined = next
                .sortedWith(
                    compareByDescending<PartState> { it.score }
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
     * 只解码 digits 的第一个音节（用于 materialize 时逐步推进）。
     */
    fun decodeNextSegment(
        digits: String,
        manualCuts: List<Int>,
        dict: Dictionary
    ): String? {
        if (digits.isEmpty()) return null
        return planAll(digits, manualCuts, dict).firstOrNull()?.segments?.firstOrNull()
    }

    private fun decodePart(part: String, dict: Dictionary): List<PartState> {
        if (part.isEmpty()) return emptyList()

        var beam = listOf(PartState(pos = 0, segments = emptyList(), score = 0))

        while (beam.any { it.pos < part.length }) {
            val next = ArrayList<PartState>()

            for (state in beam) {
                if (state.pos >= part.length) {
                    next.add(state)
                    continue
                }

                val remain = part.substring(state.pos)
                val choices = buildChoices(remain, dict)

                for (choice in choices.take(PART_STEP_OPTIONS)) {
                    val nextPos = (state.pos + choice.codeLen).coerceAtMost(part.length)
                    next.add(
                        PartState(
                            pos = nextPos,
                            segments = state.segments + choice.text,
                            score = state.score + choice.score
                        )
                    )
                }
            }

            beam = next
                .sortedWith(
                    compareByDescending<PartState> { it.score }
                        .thenByDescending { it.pos }
                        .thenBy { it.segments.joinToString("'") }
                )
                .distinctBy { "${it.pos}|${it.segments.joinToString("'")}" }
                .take(PART_BEAM_WIDTH)

            if (beam.isEmpty()) break
            if (beam.all { it.pos >= part.length }) break
        }

        return beam
            .filter { it.pos >= part.length }
            .sortedWith(
                compareByDescending<PartState> { it.score }
                    .thenBy { it.segments.joinToString("'") }
            )
            .distinctBy { it.segments.joinToString("'") }
            .take(MAX_PLAN_COUNT)
    }

    private fun buildChoices(digits: String, dict: Dictionary): List<Choice> {
        val out = ArrayList<Choice>()
        val seen = HashSet<String>()

        val items = dict.getPinyinPossibilities(digits)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        for (item in items) {
            if (!seen.add(item)) continue
            val codeLen = T9Lookup.encodeLetters(item)
                .length
                .coerceAtLeast(1)
                .coerceAtMost(digits.length)

            val normalized = item.replace("ü", "v")
            val score = when {
                normalizedPinyinSet.contains(normalized) -> 300 + codeLen * 30
                item == "zh" || item == "ch" || item == "sh" -> 240 + codeLen * 25
                item.length == 1 -> 80 + codeLen * 10
                else -> 120 + codeLen * 10
            }

            out.add(Choice(text = item, codeLen = codeLen, score = score))
        }

        val fallback = defaultLetterForDigit(digits.first())
        if (fallback.isNotEmpty() && seen.add(fallback)) {
            out.add(Choice(text = fallback, codeLen = 1, score = 20))
        }

        return out.sortedWith(
            compareByDescending<Choice> { it.score }
                .thenByDescending { it.codeLen }
                .thenByDescending { it.text.length }
                .thenBy { it.text }
        )
    }

    private fun splitDigitsByCuts(digits: String, manualCuts: List<Int>): List<String> {
        if (digits.isEmpty()) return emptyList()

        val cuts = manualCuts
            .asSequence()
            .filter { it in 1 until digits.length }
            .distinct()
            .sorted()
            .toList()

        if (cuts.isEmpty()) return listOf(digits)

        val out = ArrayList<String>()
        var prev = 0
        for (cut in cuts) {
            if (cut > prev) out.add(digits.substring(prev, cut))
            prev = cut
        }
        if (prev < digits.length) out.add(digits.substring(prev))
        return out
    }

    private fun defaultLetterForDigit(d: Char): String {
        return T9Lookup.charsFromDigit(d)
            .firstOrNull()
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    fun joinedCodeLength(segments: List<String>): Int {
        var total = 0
        for (seg in segments) total += T9Lookup.encodeLetters(seg).length
        return total
    }

    /** 把拼音字母串切分为音节列表，失败返回 emptyList */
    fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        if (rawLower.isEmpty()) return emptyList()

        val normalized = rawLower.replace("ü", "v")
        if (!normalized.all { it in 'a'..'z' || it == 'v' }) return emptyList()

        val out = ArrayList<String>()
        var i = 0

        while (i < normalized.length) {
            val remain = normalized.length - i
            val tryMax = kotlin.math.min(maxPyLen, remain)
            var matched: String? = null
            for (len in tryMax downTo 1) {
                val sub = normalized.substring(i, i + len)
                if (normalizedPinyinSet.contains(sub)) {
                    matched = sub
                    break
                }
            }
            if (matched == null) return emptyList()
            out.add(matched.replace("v", "ü"))
            i += matched.length
        }

        return out
    }
}
