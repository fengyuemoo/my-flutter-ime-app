package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.model.Candidate

class ChineseApostropheSuggester(
    private val analyzer: PinyinInputAnalyzer,
    private val queries: SQLiteWordQueries
) {

    fun suggest(db: SQLiteDatabase, rawInputLower: String): List<Candidate> {
        val parts = rawInputLower
            .split("'")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (parts.isEmpty()) return emptyList()

        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()

        fun addAll(list: List<Candidate>) {
            for (c in list) if (seen.add(c.word)) result.add(c)
        }

        val n = parts.size

        // 多字词：按节数从长到短回退（n, n-1, ... 2）
        run {
            val strictPrefix = buildStrictInputPrefixFromParts(parts)
            val rawList = queries.queryMultiCharByAcronymPrefix(db, parts, wordLen = n, strictInputPrefix = strictPrefix)
            addAll(rawList.filter { matchesPartsStrict(parts, it.pinyin) })
        }

        for (k in (n - 1) downTo 2) {
            val prefixParts = parts.take(k)
            val strictPrefix = buildStrictInputPrefixFromParts(prefixParts)
            val rawList = queries.queryMultiCharByAcronymPrefix(db, prefixParts, wordLen = k, strictInputPrefix = strictPrefix)
            addAll(rawList.filter { matchesPartsStrict(prefixParts, it.pinyin) })
        }

        // 单字：仍然按“拼接前缀”做 input prefix 查（这里本身是严格前缀，不会出现你说的跳过问题）
        for (k in n downTo 1) {
            val prefix = parts.take(k).joinToString("")
            addAll(queries.querySingleCharByInputPrefix(db, prefix))
        }

        return result
    }

    // 当 apostrophe 路径的开头包含“强约束段”（完整音节，或 zh/ch/sh），返回一个 input 的严格前缀（用于 SQL 过滤）。
    // 规则：从 parts[0] 开始拼接；连续拼接完整音节；遇到第一个“非完整音节(如单字母节 g/j/w)”就停止。
    private fun buildStrictInputPrefixFromParts(parts: List<String>): String? {
        if (parts.isEmpty()) return null

        fun isInitialSeg(s: String): Boolean = (s == "zh" || s == "ch" || s == "sh")

        val first = parts[0].lowercase().trim()
        if (first.isEmpty()) return null

        // 第一段必须是“强约束段”，否则不启用 strict 前缀（避免首段是单字母 x/w 时误杀）
        if (!analyzer.isFullSyllable(first) && !isInitialSeg(first)) return null

        val sb = StringBuilder()
        sb.append(first)

        // 后续只继续拼接“完整音节”，一旦遇到单字母节/其它就停止
        for (i in 1 until parts.size) {
            val p = parts[i].lowercase().trim()
            if (p.isEmpty()) break
            if (!analyzer.isFullSyllable(p)) break
            sb.append(p)
        }

        return sb.toString().takeIf { it.isNotEmpty() }
    }

    // 逐节严格匹配：任意位置的完整音节都必须严格相等；zh/ch/sh 声母节严格要求 syllable 以其开头；
    // 单字母节按首字母匹配。
    // 策略 A：候选 pinyin 无法解析为合法音节时不过滤（返回 true）。
    private fun matchesPartsStrict(parts: List<String>, candPinyin: String?): Boolean {
        if (parts.isEmpty()) return true
        val py = candPinyin?.lowercase()?.trim() ?: return false
        if (py.isEmpty()) return false

        val syllables = analyzer.splitConcatPinyinToSyllables(py)
        if (syllables.isEmpty()) return true  // A: 不解析不过滤

        if (syllables.size < parts.size) return false

        fun isInitialSeg(s: String): Boolean = (s == "zh" || s == "ch" || s == "sh")

        for (i in parts.indices) {
            val seg = parts[i].lowercase().trim()
            val syl = syllables[i].lowercase()

            val ok = when {
                isInitialSeg(seg) -> syl.startsWith(seg)                     // zh/ch/sh 更严格
                analyzer.isFullSyllable(seg) -> syl == seg                  // 完整音节必须相等
                seg.length == 1 && seg[0] in 'a'..'z' -> syl.startsWith(seg) // 单字母节
                else -> false
            }

            if (!ok) return false
        }

        return true
    }
}
