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

        // 4) 兜底：没有候选时，展示原始输入（行为沿用 SimpleIME 里的做法）
        val rawInput = if (isT9Keyboard) session.rawT9Digits else session.qwertyInput
        val finalList =
            if (filtered.isEmpty() && rawInput.isNotEmpty()) {
                listOf(Candidate(rawInput, rawInput, 0, 0, 0))
            } else {
                filtered
            }

        return Result(candidates = finalList, pinyinSidebar = sidebar)
    }
}
