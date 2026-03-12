package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.PinyinTable
import java.util.Locale
import kotlin.math.min

/**
 * 拼音字符串规范化与音节切分工具。
 *
 * 职责：
 *  1. 将候选的 pinyin/input 字符串规范化（lowercase、去隔音符、ü→v）
 *  2. 将连写拼音串切分为音节列表，返回音节数
 *
 * 无副作用，纯函数，可跨模块复用。
 */
object CnT9PinyinSplitter {

    private val pinyinSet: Set<String> by lazy {
        PinyinTable.allPinyins
            .map { it.lowercase(Locale.ROOT).replace("ü", "v") }
            .toHashSet()
    }

    private val maxPinyinLen: Int by lazy {
        pinyinSet.maxOfOrNull { it.length } ?: 8
    }

    /** 规范化拼音串：lowercase、去隔音符、ü→v */
    fun normalize(raw: String): String =
        raw.trim()
            .lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("ü", "v")

    /** 规范化候选的 pinyin 或 input 字段 */
    fun normalizeCandidate(pinyin: String?, input: String): String =
        normalize(pinyin ?: input)

    /**
     * 贪婪最长匹配切分拼音串，返回音节数。
     * 无法完整切分时返回 0（而非部分结果，避免歧义）。
     */
    fun countSyllables(raw: String): Int {
        val s = normalize(raw)
        if (s.isBlank()) return 0

        var i = 0
        var count = 0

        while (i < s.length) {
            val tryMax = min(maxPinyinLen, s.length - i)
            var found = false
            for (len in tryMax downTo 1) {
                if (pinyinSet.contains(s.substring(i, i + len))) {
                    i += len
                    count++
                    found = true
                    break
                }
            }
            if (!found) return 0
        }

        return count
    }

    /**
     * 贪婪最长匹配切分拼音串，返回音节列表。
     * 无法完整切分时返回空列表。
     */
    fun splitToSyllables(raw: String): List<String> {
        val s = normalize(raw)
        if (s.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        var i = 0

        while (i < s.length) {
            val tryMax = min(maxPinyinLen, s.length - i)
            var found = false
            for (len in tryMax downTo 1) {
                val sub = s.substring(i, i + len)
                if (pinyinSet.contains(sub)) {
                    result.add(sub)
                    i += len
                    found = true
                    break
                }
            }
            if (!found) return emptyList()
        }

        return result
    }

    /**
     * 估算数字串对应的大致音节数（用于置信度模型，非精确值）。
     */
    fun estimateDigitSyllables(digits: String): Int {
        if (digits.isEmpty()) return 0
        return when {
            digits.length <= 2  -> 1
            digits.length <= 5  -> 2
            digits.length <= 8  -> 3
            digits.length <= 11 -> 4
            digits.length <= 14 -> 5
            else                -> (digits.length + 2) / 3
        }
    }
}
