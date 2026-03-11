package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate

/**
 * 生僻字 Unicode 兜底候选生成器。
 *
 * 当 T9 候选列表为空时，按以下优先级依次兜底：
 *  1. 对每个可能的拼音字母（从第一个数字推导），查词典单字前缀候选
 *  2. 若仍为空，对整串数字用 T9 前缀直接查单字（按 COL_T9 LIKE digits%）
 *
 * 设计原则：
 *  - 兜底候选仅在正常候选为空时出现
 *  - 结果排在末尾，不污染正常候选
 */
object CnT9UnicodeFallback {

    private const val MAX_FALLBACK = 20

    fun buildFallbackCandidates(rawDigits: String, dictEngine: Dictionary): List<Candidate> {
        if (rawDigits.isEmpty() || !dictEngine.isLoaded) return emptyList()

        // 策略 1：从第一个数字推导可能的拼音首字母，查单字前缀候选
        val firstDigit = rawDigits.first()
        val possibleLetters = T9Lookup.charsFromDigit(firstDigit.toString())
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }

        if (possibleLetters.isEmpty()) return emptyList()

        val results = mutableListOf<Candidate>()
        val seen = HashSet<String>()

        for (letter in possibleLetters) {
            if (results.size >= MAX_FALLBACK) break
            val singles = dictEngine.querySingleCharsWithPinyinPrefix(letter)
                .filter { it.word.isNotEmpty() && seen.add(it.word) }
                .take(MAX_FALLBACK - results.size)
            results.addAll(singles)
        }

        if (results.isNotEmpty()) return results

        // 策略 2：直接用完整数字串做拼音前缀搜索（覆盖更长输入场景）
        for (letter in possibleLetters) {
            // 尝试用更长的可能拼音前缀（如 263 → "an"/"bm"/"bo" 等）
            val twoLetterPrefixes = possibleLetters.flatMap { a ->
                T9Lookup.charsFromDigit(rawDigits.getOrNull(1)?.toString() ?: "")
                    .map { b -> a + b.lowercase() }
            }.filter { it.length == 2 }

            for (prefix in twoLetterPrefixes) {
                if (results.size >= MAX_FALLBACK) break
                val singles = dictEngine.querySingleCharsWithPinyinPrefix(prefix)
                    .filter { it.word.isNotEmpty() && seen.add(it.word) }
                    .take(MAX_FALLBACK - results.size)
                results.addAll(singles)
            }
            break  // 只取一次
        }

        return results.take(MAX_FALLBACK)
    }
}
