package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import java.util.Locale

/**
 * CN-T9 单段 Beam Search 解码器。
 *
 * 职责：
 *  1. splitDigitsByCuts  — 按手动切分点把数字串切为若干段
 *  2. decodePart         — 对单段数字串做 Beam Search，输出所有有效拼音路径
 *  3. buildChoices       — 对剩余数字串的当前前缀，查字典拼音可能性并打分
 *
 * 本类不依赖 UI / Session，可独立单元测试。
 * 被 CnT9SentencePlanner 调用，不直接暴露给其他模块。
 */
internal object CnT9BeamDecoder {

    private val normalizedPinyinSet: Set<String> by lazy {
        PinyinTable.allPinyins
            .map { it.lowercase(Locale.ROOT).replace("ü", "v") }
            .toHashSet()
    }

    internal const val PART_BEAM_WIDTH = 8
    internal const val PART_STEP_OPTIONS = 8

    data class DecodeState(
        val pos: Int,
        val segments: List<String>,
        val score: Int
    )

    internal data class Choice(
        val text: String,
        val codeLen: Int,
        val score: Int
    )

    // ── 公开入口 ──────────────────────────────────────────────────

    /**
     * 按手动切分点把数字串拆分为若干子段。
     * 切分点必须在 1..(digits.length-1) 之间，超界或重复的切分点会被忽略。
     */
    fun splitDigitsByCuts(digits: String, manualCuts: List<Int>): List<String> {
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

    /**
     * 对单段数字串做 Beam Search，返回所有到达终止位置的有效解码路径。
     * 结果按分数降序，去重，最多 maxPlanCount 条。
     */
    fun decodePart(
        part: String,
        dict: Dictionary,
        maxPlanCount: Int = 12
    ): List<DecodeState> {
        if (part.isEmpty()) return emptyList()

        var beam = listOf(DecodeState(pos = 0, segments = emptyList(), score = 0))

        while (beam.any { it.pos < part.length }) {
            val next = ArrayList<DecodeState>()

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
                        DecodeState(
                            pos = nextPos,
                            segments = state.segments + choice.text,
                            score = state.score + choice.score
                        )
                    )
                }
            }

            beam = next
                .sortedWith(
                    compareByDescending<DecodeState> { it.score }
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
                compareByDescending<DecodeState> { it.score }
                    .thenBy { it.segments.joinToString("'") }
            )
            .distinctBy { it.segments.joinToString("'") }
            .take(maxPlanCount)
    }

    /**
     * 对当前剩余数字串的前缀，查字典拼音可能性并打分，返回有序 Choice 列表。
     */
    fun buildChoices(digits: String, dict: Dictionary): List<Choice> {
        if (digits.isEmpty()) return emptyList()

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

    // ── 工具函数 ──────────────────────────────────────────────────

    private fun defaultLetterForDigit(d: Char): String =
        T9Lookup.charsFromDigit(d.toString())
            .firstOrNull()
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ""
}
