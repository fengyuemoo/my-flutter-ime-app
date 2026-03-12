package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import java.util.Locale

/**
 * CN-T9 单段数字串重切分路径枚举器。
 *
 * 对应规则清单「音节栏的重新切分」：
 *  - 给定一段 digitChunk（如 "94664"），枚举所有合法的拼音切分路径
 *  - 每条路径是一个音节列表（如 ["zhong"] 或 ["zhi", "ong"] 等）
 *  - 切换路径后立即刷新候选
 *
 * 算法：DFS + 剪枝
 *  1. 用 T9Lookup 把每个 digit 展开成对应字母集合
 *  2. 逐位枚举，尝试从当前位置起取 1..MAX_SYLLABLE_LEN 个 digit
 *  3. 每段生成所有字母组合，过滤出合法拼音
 *  4. 递归处理剩余 digits，直到消费完整段
 *  5. 剪枝：中途无合法拼音前缀时立即回溯
 */
object CnT9ResegmentResolver {

    /** 单次枚举最多返回的路径数（防止组合爆炸）。*/
    private const val MAX_PATHS = 32

    /** 单个音节最大 digit 长度（zh/ch/sh 最长音节约 6 个字母 → 对应 digits 不超过 6）。*/
    private const val MAX_SYLLABLE_DIGITS = 6

    /** 合法拼音集合（小写，ü→v）。*/
    private val pinyinSet: Set<String> by lazy {
        PinyinTable.allPinyins
            .map { it.lowercase(Locale.ROOT).replace("ü", "v") }
            .toHashSet()
    }

    /**
     * 合法拼音前缀集合，用于剪枝。
     * 如果当前已拼出的字母串不是任何合法拼音的前缀，立即回溯。
     */
    private val pinyinPrefixSet: Set<String> by lazy {
        val set = HashSet<String>()
        for (py in pinyinSet) {
            for (i in 1..py.length) {
                set.add(py.substring(0, i))
            }
        }
        set
    }

    /**
     * 枚举给定 digitChunk 的所有合法拼音切分路径。
     *
     * @param digitChunk 纯数字串，如 "94664"
     * @return 路径列表，每条路径是音节列表；若无合法路径则返回空列表
     */
    fun resolve(digitChunk: String): List<List<String>> {
        val digits = digitChunk.filter { it in '0'..'9' }
        if (digits.isEmpty()) return emptyList()

        val results = ArrayList<List<String>>(MAX_PATHS)
        dfs(digits, 0, ArrayList(), results)
        return results
    }

    /**
     * 返回去重后的扁平音节候选列表（每条路径的所有音节合并、去重）。
     * 用于 sidebar 展示「该段可能对应哪些音节」。
     *
     * @param digitChunk 纯数字串
     * @return 所有合法路径中出现过的音节，按路径顺序去重
     */
    fun resolveAsSyllableList(digitChunk: String): List<String> {
        return resolve(digitChunk)
            .flatten()
            .distinct()
    }

    /**
     * 返回「最优首选路径」（路径列表第 0 条，即最长匹配优先的路径）。
     * 若无合法路径返回 null。
     */
    fun resolveBestPath(digitChunk: String): List<String>? {
        return resolve(digitChunk).firstOrNull()
    }

    // ── DFS 核心 ────────────────────────────────────────────────────────────

    private fun dfs(
        digits: String,
        start: Int,
        current: ArrayList<String>,
        results: ArrayList<List<String>>
    ) {
        if (results.size >= MAX_PATHS) return

        if (start == digits.length) {
            if (current.isNotEmpty()) {
                results.add(current.toList())
            }
            return
        }

        val remaining = digits.length - start
        val tryMax = minOf(MAX_SYLLABLE_DIGITS, remaining)

        // 优先尝试较长的 digit 段（最长匹配优先，使首选路径是最贪婪切分）
        for (len in tryMax downTo 1) {
            val segDigits = digits.substring(start, start + len)
            val letterCombinations = expandDigits(segDigits)

            for (letters in letterCombinations) {
                if (!pinyinPrefixSet.contains(letters)) continue
                if (!pinyinSet.contains(letters)) continue

                current.add(letters)
                dfs(digits, start + len, current, results)
                current.removeAt(current.lastIndex)

                if (results.size >= MAX_PATHS) return
            }
        }
    }

    /**
     * 将数字串展开为所有可能的字母组合。
     *
     * 例："2" → ["a","b","c"]，"23" → ["ad","ae","af","bd","be","bf",...]
     * 使用剪枝：生成过程中只保留是合法拼音前缀的组合。
     */
    private fun expandDigits(segDigits: String): List<String> {
        if (segDigits.isEmpty()) return emptyList()

        var current = listOf("")

        for (digit in segDigits) {
            val letters = T9Lookup.charsFromDigit(digit)
            if (letters.isEmpty()) return emptyList()

            val next = ArrayList<String>(current.size * letters.size)
            for (prefix in current) {
                for (letter in letters) {
                    val candidate = prefix + letter
                    // 剪枝：只保留是合法拼音前缀的组合
                    if (pinyinPrefixSet.contains(candidate)) {
                        next.add(candidate)
                    }
                }
            }
            if (next.isEmpty()) return emptyList()
            current = next
        }

        return current
    }
}
