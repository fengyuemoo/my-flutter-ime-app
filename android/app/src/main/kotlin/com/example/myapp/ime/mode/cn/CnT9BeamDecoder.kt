package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import java.util.Locale

internal object CnT9BeamDecoder {

    private val normalizedPinyinSet: Set<String> by lazy {
        PinyinTable.allPinyins
            .map { it.lowercase(Locale.ROOT).replace("ü", "v") }
            .toHashSet()
    }

    // ── 性能优化：预计算拼音→T9编码映射，消除 buildChoices 中的重复 encodeLetters ──
    // 与 SQLiteDictionaryEngine.pinyinToT9Cache 独立，避免跨层依赖。
    // 启动时 lazy 初始化一次，热路径中直接 map 查找，O(1)。
    private val pinyinCodeMap: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(PinyinTable.allPinyins.size + 10)
        for (py in PinyinTable.allPinyins) {
            val norm = py.lowercase(Locale.ROOT).replace("ü", "v")
            val code = T9Lookup.encodeLetters(norm)
            if (code.isNotEmpty()) map[norm] = code.length
        }
        for (initial in listOf("zh", "ch", "sh")) {
            val code = T9Lookup.encodeLetters(initial)
            if (code.isNotEmpty()) map[initial] = code.length
        }
        map
    }

    internal const val PART_BEAM_WIDTH = 8
    internal const val PART_STEP_OPTIONS = 8

    data class DecodeState(
        val pos: Int,
        val segments: List<String>,
        val score: Int
    ) {
        // ── 性能优化：预计算 key，消除 distinctBy/thenBy 中的重复 joinToString ──
        // decodePart 主循环每步对最多 64 个状态做 distinctBy + 排序，
        // 原来每次都调用 segments.joinToString("'")，产生大量临时字符串。
        // 改为构建时计算一次，后续所有比较直接用 .key，零额外分配。
        val key: String = "$pos|${segments.joinToString("'")}"
    }

    internal data class Choice(
        val text: String,
        val codeLen: Int,
        val score: Int
    )

    // ── 公开入口 ──────────────────────────────────────────────────

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
                            pos      = nextPos,
                            segments = state.segments + choice.text,
                            score    = state.score + choice.score
                        )
                    )
                }
            }

            beam = next
                .sortedWith(
                    compareByDescending<DecodeState> { it.score }
                        .thenByDescending { it.pos }
                        .thenBy { it.key }           // 直接用预计算 key，无额外分配
                )
                .distinctBy { it.key }               // 直接用预计算 key
                .take(PART_BEAM_WIDTH)

            if (beam.isEmpty()) break
            if (beam.all { it.pos >= part.length }) break
        }

        return beam
            .filter { it.pos >= part.length }
            .sortedWith(
                compareByDescending<DecodeState> { it.score }
                    .thenBy { it.key }               // 直接用预计算 key
            )
            .distinctBy { it.segments.joinToString("'") }  // 最终去重仍按 segments
            .take(maxPlanCount)
    }

    fun buildChoices(digits: String, dict: Dictionary): List<Choice> {
        if (digits.isEmpty()) return emptyList()

        val out = ArrayList<Choice>()
        val seen = HashSet<String>()

        val items = dict.getPinyinPossibilities(digits)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        for (item in items) {
            if (!seen.add(item)) continue

            val normalized = item.replace("ü", "v")

            // ── 性能优化：优先从预计算 map 中取 codeLen，避免重复 encodeLetters ──
            val codeLen = (pinyinCodeMap[normalized]
                ?: T9Lookup.encodeLetters(item).length)
                .coerceAtLeast(1)
                .coerceAtMost(digits.length)

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
        T9Lookup.charsFromDigit(d)
            .firstOrNull()
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: ""
}
