package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import java.util.Locale

/**
 * 生僻字兜底候选生成器。
 *
 * 规则清单对应条目：
 *   "拼音能打到但词库没有的字，要至少能通过'单字候选 + 部首/笔画/Unicode'兜底。"
 *
 * 三级兜底策略（按优先级依次尝试，有结果则停止）：
 *
 *  Level 1 — 拼音路径单字查询（最精准）
 *    利用 CnT9SentencePlanner 已规划出的拼音路径，
 *    用完整音节序列查字典单字（getSuggestionsFromPinyinStack 限定单字）。
 *
 *  Level 2 — 拼音前缀单字查询（次精准）
 *    取路径第一个音节的前 1–2 个字母做前缀，
 *    调用 querySingleCharsWithPinyinPrefix 宽松匹配。
 *
 *  Level 3 — T9 首字母拼音前缀（最宽松）
 *    直接取第一个数字键对应的所有字母，逐一做前缀查询；
 *    兜底覆盖字典路径完全无结果的极端情况。
 *
 * 不做 Unicode 码位扫描兜底（那样噪声太大，实际无意义）。
 */
object CnT9UnicodeFallback {

    private const val MAX_FALLBACK = 20

    fun buildFallbackCandidates(
        rawDigits: String,
        dictEngine: Dictionary,
        plans: List<CnT9SentencePlanner.PathPlan> = emptyList()
    ): List<Candidate> {
        if (rawDigits.isEmpty() || !dictEngine.isLoaded) return emptyList()

        val l1 = buildLevel1(plans, dictEngine)
        if (l1.isNotEmpty()) return l1

        val l2 = buildLevel2(plans, rawDigits, dictEngine)
        if (l2.isNotEmpty()) return l2

        return buildLevel3(rawDigits, dictEngine)
    }

    // ── Level 1 ───────────────────────────────────────────────────

    private fun buildLevel1(
        plans: List<CnT9SentencePlanner.PathPlan>,
        dictEngine: Dictionary
    ): List<Candidate> {
        if (plans.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val results = mutableListOf<Candidate>()

        for (plan in plans) {
            if (plan.segments.isEmpty()) continue

            val singles = dictEngine.getSuggestionsFromPinyinStack(
                pinyinStack = plan.segments,
                rawDigits   = ""
            ).filter { it.word.length == 1 && seen.add(it.word) }

            results.addAll(singles)
            if (results.size >= MAX_FALLBACK) break
        }

        return results.take(MAX_FALLBACK)
    }

    // ── Level 2 ───────────────────────────────────────────────────

    private fun buildLevel2(
        plans: List<CnT9SentencePlanner.PathPlan>,
        rawDigits: String,
        dictEngine: Dictionary
    ): List<Candidate> {
        val prefixes = extractPinyinPrefixes(plans, rawDigits)
        if (prefixes.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val results = mutableListOf<Candidate>()

        for (prefix in prefixes) {
            if (results.size >= MAX_FALLBACK) break
            val singles = dictEngine.querySingleCharsWithPinyinPrefix(prefix)
                .filter { it.word.length == 1 && seen.add(it.word) }
                .take(MAX_FALLBACK - results.size)
            results.addAll(singles)
        }

        return results
    }

    private fun extractPinyinPrefixes(
        plans: List<CnT9SentencePlanner.PathPlan>,
        rawDigits: String
    ): List<String> {
        val firstPlan = plans.firstOrNull { it.segments.isNotEmpty() }

        if (firstPlan != null) {
            val firstSyllable = firstPlan.segments.first()
                .lowercase(Locale.ROOT).trim()
            return (1..minOf(3, firstSyllable.length))
                .map { firstSyllable.substring(0, it) }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        return buildLetterPrefixes(rawDigits, maxLen = 2)
    }

    // ── Level 3 ───────────────────────────────────────────────────

    private fun buildLevel3(rawDigits: String, dictEngine: Dictionary): List<Candidate> {
        val prefixes = buildLetterPrefixes(rawDigits, maxLen = 1)
        if (prefixes.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val results = mutableListOf<Candidate>()

        for (prefix in prefixes) {
            if (results.size >= MAX_FALLBACK) break
            val singles = dictEngine.querySingleCharsWithPinyinPrefix(prefix)
                .filter { it.word.length == 1 && seen.add(it.word) }
                .take(MAX_FALLBACK - results.size)
            results.addAll(singles)
        }

        return results
    }

    // ── 工具函数 ──────────────────────────────────────────────────

    // 修复：T9Lookup.charsFromDigit 接受 Char，不需要 .toString()
    private fun buildLetterPrefixes(rawDigits: String, maxLen: Int): List<String> {
        if (rawDigits.isEmpty()) return emptyList()

        val digits = rawDigits.take(maxLen)
        var prefixes = listOf("")

        for (d in digits) {
            val letters = T9Lookup.charsFromDigit(d)
                .map { it.lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
            if (letters.isEmpty()) break
            prefixes = prefixes.flatMap { prev -> letters.map { prev + it } }
        }

        return prefixes.filter { it.isNotEmpty() }.distinct()
    }
}
