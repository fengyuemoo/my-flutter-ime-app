package com.example.myapp.dict.impl

class PinyinInputAnalyzer(allPinyins: List<String>) {

    private val allPinyinsLower: List<String> = allPinyins.map { it.lowercase() }
    private val pinyinSet: Set<String> = allPinyinsLower.toHashSet()
    private val maxPinyinLen: Int = allPinyinsLower.maxOfOrNull { it.length } ?: 0

    private val pinyinPrefixSet: Set<String> by lazy {
        val set = HashSet<String>(allPinyinsLower.size * 3)
        for (py in allPinyinsLower) {
            for (i in 1..py.length) set.add(py.substring(0, i))
        }
        set
    }

    data class PartialPinyinPrefix(
        val fullSyllables: List<String>,
        val remainder: String
    )

    fun isFullSyllable(s: String): Boolean = pinyinSet.contains(s.lowercase())

    fun bestPinyinPrefix(rawLower: String): String {
        val s = rawLower.lowercase()
        if (s.isEmpty()) return ""
        if (!s.all { it in 'a'..'z' }) return s.take(1)

        val max = minOf(maxPinyinLen, s.length)
        for (l in max downTo 1) {
            val sub = s.substring(0, l)
            if (pinyinPrefixSet.contains(sub)) return sub
        }
        return s.take(1)
    }

    fun splitPartialPinyinPrefix(rawLower: String): PartialPinyinPrefix? {
        if (rawLower.isEmpty()) return null
        if (!rawLower.all { it in 'a'..'z' }) return null

        for (cut in (rawLower.length - 1) downTo 1) {
            val prefix = rawLower.substring(0, cut)
            val syl = splitConcatPinyinToSyllables(prefix)
            if (syl.isNotEmpty() && syl.joinToString("") == prefix) {
                val rem = rawLower.substring(cut)
                if (rem.isNotEmpty()) {
                    return PartialPinyinPrefix(fullSyllables = syl, remainder = rem)
                }
            }
        }
        return null
    }

    fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        if (rawLower.isEmpty()) return emptyList()
        if (!rawLower.all { it in 'a'..'z' }) return emptyList()
        if (maxPinyinLen <= 0) return emptyList()

        val out = ArrayList<String>()
        var i = 0
        while (i < rawLower.length) {
            val remain = rawLower.length - i
            val tryMax = minOf(maxPinyinLen, remain)

            var matched: String? = null
            var l = tryMax
            while (l >= 1) {
                val sub = rawLower.substring(i, i + l)
                if (pinyinSet.contains(sub)) {
                    matched = sub
                    break
                }
                l--
            }

            if (matched == null) return emptyList()
            out.add(matched)
            i += matched.length
        }
        return out
    }
}
