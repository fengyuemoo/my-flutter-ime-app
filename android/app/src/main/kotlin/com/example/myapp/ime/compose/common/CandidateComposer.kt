package com.example.myapp.ime.compose.common

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate

class CandidateComposer(private val dictEngine: Dictionary) {

    data class Result(
        val candidates: List<Candidate>,
        val pinyinSidebar: List<String> = emptyList()
    )

    fun compose(
        session: ComposingSession,
        isChinese: Boolean,
        useT9Layout: Boolean,
        isT9Keyboard: Boolean,
        singleCharMode: Boolean
    ): Result {

        // 1) 侧边栏拼音列表（仅：中文 + T9 + CnT9Keyboard 且 digits 非空）
        val sidebar = if (isT9Keyboard && isChinese && session.rawT9Digits.isNotEmpty()) {
            dictEngine.getPinyinPossibilities(session.rawT9Digits)
        } else {
            emptyList()
        }

        val candidates = ArrayList<Candidate>()

        // 2) 词典候选
        if (dictEngine.isLoaded) {
            if (isT9Keyboard && isChinese && session.pinyinStack.isNotEmpty()) {
                candidates.addAll(
                    dictEngine.getSuggestionsFromPinyinStack(session.pinyinStack, session.rawT9Digits)
                )
            } else {
                val input = if (isT9Keyboard) session.rawT9Digits else session.qwertyInput
                if (input.isNotEmpty()) {
                    candidates.addAll(dictEngine.getSuggestions(input, isT9Keyboard, isChinese))
                }
            }
        }

        // 3) 单字过滤
        val filtered = if (singleCharMode && isChinese) {
            candidates.filter { it.word.length == 1 }
        } else {
            candidates
        }

        // 4) 中文模式英文排序策略（仅：中文 + 非T9 + 输入为纯字母）
        val rawInput = if (isT9Keyboard) session.rawT9Digits else session.qwertyInput
        val finalCandidates = if (isChinese && !isT9Keyboard) {
            reorderForChineseModePreferHan(filtered, rawInput)
        } else {
            filtered
        }

        // 5) 兜底：没有候选时，展示原始输入
        val finalList =
            if (finalCandidates.isEmpty() && rawInput.isNotEmpty()) {
                listOf(Candidate(rawInput, rawInput, 0, 0, 0))
            } else {
                finalCandidates
            }

        return Result(candidates = finalList, pinyinSidebar = sidebar)
    }

    private fun reorderForChineseModePreferHan(
        list: List<Candidate>,
        rawInput: String
    ): List<Candidate> {
        if (list.isEmpty()) return list
        if (rawInput.isEmpty()) return list

        val isAlphaInput = rawInput.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (!isAlphaInput) {
            return groupChineseFirst(list, englishTailLimit = 5)
        }

        val inputLower = rawInput.lowercase()

        val (english, nonEnglish) = list.partition { isAsciiWord(it.word) }

        val englishExact = ArrayList<Candidate>()
        val englishVariants = ArrayList<Candidate>()
        val englishOthers = ArrayList<Candidate>()

        val variantWhitelist = englishVariantsFor(inputLower).toHashSet()

        for (c in english) {
            val w = c.word.lowercase()
            when {
                w == inputLower -> englishExact.add(c)
                w in variantWhitelist -> englishVariants.add(c)
                else -> englishOthers.add(c)
            }
        }

        val shouldPromoteEnglish = inputLower.length >= 4

        val promotedEnglish = if (shouldPromoteEnglish && englishExact.isNotEmpty()) {
            englishExact + englishVariants.take(2)
        } else {
            emptyList()
        }

        val promotedSet = promotedEnglish.toHashSet()

        val nonEnglishKept = nonEnglish.filter { it !in promotedSet }
        val englishVariantsKept = englishVariants.filter { it !in promotedSet }
        val englishExactKept = englishExact.filter { it !in promotedSet }
        val englishOthersKept = englishOthers.filter { it !in promotedSet }

        val tail = (englishExactKept + englishVariantsKept + englishOthersKept).take(5)

        return promotedEnglish + nonEnglishKept + tail
    }

    private fun englishVariantsFor(inputLower: String): List<String> {
        if (inputLower.length < 2) return emptyList()

        fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

        val out = ArrayList<String>()

        if (inputLower.endsWith("y") && inputLower.length >= 2) {
            val prev = inputLower[inputLower.length - 2]
            if (!isVowel(prev)) {
                out.add(inputLower.dropLast(1) + "ies")
                return out
            }
        }

        out.add("${inputLower}s")

        val needEs =
            inputLower.endsWith("s")
                    || inputLower.endsWith("x")
                    || inputLower.endsWith("z")
                    || inputLower.endsWith("ch")
                    || inputLower.endsWith("sh")
                    || inputLower.endsWith("o")

        if (needEs) out.add("${inputLower}es")

        return out
    }

    private fun groupChineseFirst(list: List<Candidate>, englishTailLimit: Int): List<Candidate> {
        val (english, nonEnglish) = list.partition { isAsciiWord(it.word) }
        return nonEnglish + english.take(englishTailLimit)
    }

    private fun isAsciiWord(word: String): Boolean {
        if (word.isEmpty()) return false
        return word.all { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
