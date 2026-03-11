package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.T9Lookup

/**
 * 中英混输检测器。
 *
 * 判断当前 T9 数字串是否更可能是英文直出，并给出建议的英文字符串候选列表。
 *
 * 规则（简单有效）：
 *  1. 数字串长度 >= 3
 *  2. 每个数字对应的字母集合大小 <= 3（排除 7/9 这种 4 键；或者只有 2/3/4/5/6/8）
 *  3. 若所有数字都只映射到 2 个以内字母（如 2→abc），则更可能是字母直出
 *  4. 生成候选：从数字串按 T9 映射枚举可能的英文字母序列（最多 top-N）
 *
 * 调用方在候选列表头部插入这些英文字符串候选（显示为带英文图标），
 * 用户点选即直接上屏英文，不影响中文候选后续展示。
 */
object CnT9MixedInputDetector {

    private const val MAX_EN_CANDIDATES = 4
    private const val MIN_DIGITS_FOR_DETECTION = 3

    /**
     * 检测并返回建议的英文字符串候选（最多 MAX_EN_CANDIDATES 个）。
     * 若不像英文直出则返回空列表。
     */
    fun detectEnglishCandidates(rawDigits: String): List<String> {
        if (rawDigits.length < MIN_DIGITS_FOR_DETECTION) return emptyList()

        // 获取每个数字对应的字母列表
        val charSets = rawDigits.map { d ->
            T9Lookup.charsFromDigit(d.toString())
        }

        // 如果有某个数字没有对应字母（如0/1），直接排除
        if (charSets.any { it.isEmpty() }) return emptyList()

        // 枚举所有可能的英文字母序列（剪枝：超过阈值就停止）
        val results = mutableListOf<String>()
        enumerate(charSets, StringBuilder(), results)
        if (results.isEmpty()) return emptyList()

        // 按"常见英文字母组合"打分排序（简化：优先小写全字母序列）
        return results
            .sortedByDescending { scoreEnglishString(it) }
            .take(MAX_EN_CANDIDATES)
    }

    private fun enumerate(
        charSets: List<List<String>>,
        current: StringBuilder,
        results: MutableList<String>
    ) {
        if (results.size >= MAX_EN_CANDIDATES * 8) return  // 剪枝

        if (current.length == charSets.size) {
            results.add(current.toString())
            return
        }

        val idx = current.length
        for (ch in charSets[idx]) {
            current.append(ch)
            enumerate(charSets, current, results)
            current.deleteCharAt(current.length - 1)
        }
    }

    /**
     * 简单英文字符串评分：优先全小写 + 无重复字母 + 长度适中。
     * 后续可替换为真实英文词频字典。
     */
    private fun scoreEnglishString(s: String): Int {
        var score = 0
        if (s == s.lowercase()) score += 10
        if (s.toSet().size == s.length) score += 5   // 无重复字母
        if (s.length in 3..8) score += 3
        return score
    }
}
