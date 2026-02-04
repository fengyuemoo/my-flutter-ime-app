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

        // 5) 兜底：没有候选时，展示原始输入（行为沿用 SimpleIME 里的做法）
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

        // 只在“纯英文字母输入”时启用该策略，避免影响包含符号/数字的输入
        val isAlphaInput = rawInput.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (!isAlphaInput) {
            // 非纯字母：直接按“中文优先”把英文压后（如果能识别）
            return groupChineseFirst(list, englishTailLimit = 5)
        }

        val inputLower = rawInput.lowercase()

        // 1) 分组：中文 vs 英文（英文这里定义为“纯ASCII字母”）
        val (english, nonEnglish) = list.partition { isAsciiWord(it.word) }

        // 2) 英文精确词与少量变体置顶（只提非常少的词，避免 yearn/yearly 等跑到汉字前面）
        val englishExact = ArrayList<Candidate>()
        val englishVariants = ArrayList<Candidate>()
        val englishOthers = ArrayList<Candidate>()

        val variantWhitelist = setOf("${inputLower}s", "${inputLower}es")

        for (c in english) {
            val w = c.word.lowercase()
            when {
                w == inputLower -> englishExact.add(c)
                w in variantWhitelist -> englishVariants.add(c)
                else -> englishOthers.add(c)
            }
        }

        // 保持词典原有顺序：只做“相对分组”移动，不在分组内重新排序
        // 规则：
        // - 若存在精确英文词：把 (精确 + 变体最多2个) 放到最前
        // - 否则：不提升任何英文词，中文候选优先
        val promotedEnglish = if (englishExact.isNotEmpty()) {
            // 变体只取前两个，避免太多英文挤前排
            englishExact + englishVariants.take(2)
        } else {
            emptyList()
        }

        // 3) 中文优先：非英文（通常是汉字/中文候选）放在中间
        // 4) 英文扩展压后并限量，避免刷屏
        val tailEnglish = (englishOthers).take(5)

        // 注意：如果 promotedEnglish 里包含的项也出现在 englishOthers/englishVariants 里，需要去重
        val promotedSet = promotedEnglish.toHashSet()

        val nonEnglishKept = nonEnglish.filter { it !in promotedSet }
        val englishVariantsKept = englishVariants.filter { it !in promotedSet }
        val englishExactKept = englishExact.filter { it !in promotedSet }
        val englishOthersKept = englishOthers.filter { it !in promotedSet }

        val tail = (englishExactKept + englishVariantsKept + englishOthersKept).take(5)

        return promotedEnglish + nonEnglishKept + tail
    }

    private fun groupChineseFirst(list: List<Candidate>, englishTailLimit: Int): List<Candidate> {
        val (english, nonEnglish) = list.partition { isAsciiWord(it.word) }
        return nonEnglish + english.take(englishTailLimit)
    }

    private fun isAsciiWord(word: String): Boolean {
        if (word.isEmpty()) return false
        // “英文候选”判定：全部是 ASCII 字母（A-Z/a-z）
        return word.all { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
