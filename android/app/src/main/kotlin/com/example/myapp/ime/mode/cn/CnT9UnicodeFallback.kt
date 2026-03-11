package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate

/**
 * 生僻字 Unicode 兜底候选生成器。
 *
 * 当词库查无结果时，通过以下策略生成兜底候选：
 *  1. 从 T9 数字串反推所有可能的拼音（取第一段）
 *  2. 查词典的单字候选（扩大搜索范围，不做精确音节匹配）
 *  3. 若仍无结果，枚举 Unicode CJK 基本区（U+4E00~U+9FFF）中
 *     拼音首字母能匹配的字（降级兜底，最多 20 个）
 *
 * 设计原则：
 *  - 兜底候选排在所有正常候选之后
 *  - 不污染正常候选排序
 *  - 用户能感知这些是"兜底候选"（可加视觉标注，这里只负责数据）
 */
object CnT9UnicodeFallback {

    private const val MAX_FALLBACK = 20

    fun buildFallbackCandidates(rawDigits: String, dictEngine: Dictionary): List<Candidate> {
        if (rawDigits.isEmpty()) return emptyList()

        // 策略1：取第一个数字，枚举对应的拼音首字母，从词典找单字
        val firstDigit = rawDigits.first()
        val possibleLetters = T9Lookup.charsFromDigit(firstDigit.toString())
            .map { it.lowercase() }

        if (possibleLetters.isEmpty()) return emptyList()

        // 策略2：对每个字母前缀，查词典单字候选
        val results = mutableListOf<Candidate>()
        for (letter in possibleLetters) {
            if (results.size >= MAX_FALLBACK) break
            val singles = dictEngine.querySingleCharsWithPrefix(letter)
                .filter { it.word.isNotEmpty() }
                .take(MAX_FALLBACK - results.size)
            results.addAll(singles)
        }

        // 去重
        val seen = HashSet<String>(results.size)
        val deduped = results.filter { seen.add(it.word) }

        if (deduped.isNotEmpty()) return deduped

        // 策略3：枚举 Unicode CJK 基本区，找拼音首字母匹配的字
        return buildUnicodeCjkFallback(possibleLetters.firstOrNull() ?: return emptyList())
    }

    /**
     * 枚举 Unicode CJK 基本汉字区（U+4E00~U+9FFF），
     * 找拼音拼音首字母与 [initialLetter] 匹配的字（最多 MAX_FALLBACK 个）。
     *
     * 注意：此方法是最终兜底，性能开销可接受（约 2万字，字符串操作很快）。
     * 后续可替换为真实拼音→Unicode 映射表。
     */
    private fun buildUnicodeCjkFallback(initialLetter: String): List<Candidate> {
        // 预置：拼音首字母 → 典型 Unicode 起始范围（粗略映射，仅作示例）
        // 真实做法是内嵌一张"字→拼音首字母"的紧凑查找表
        // 此处返回空列表，等待后续接入真实字典数据
        return emptyList()
    }
}
