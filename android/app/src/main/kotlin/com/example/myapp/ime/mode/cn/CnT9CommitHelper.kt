package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession

/**
 * 候选提交辅助：负责计算候选消费的音节数/数字数，以及按需物化音节段。
 */
object CnT9CommitHelper {

    fun resolveConsumeSyllables(cand: Candidate): Int {
        if (cand.pinyinCount > 0) return cand.pinyinCount
        if (cand.syllables > 0) return cand.syllables

        cand.pinyin?.let { py ->
            val n = CnT9PinyinSplitter.countSyllables(py)
            if (n > 0) return n
        }

        val n = CnT9PinyinSplitter.countSyllables(cand.input)
        return if (n > 0) n else 1
    }

    fun resolveDigitsToConsume(cand: Candidate): Int {
        val source = CnT9PinyinSplitter.normalize(cand.pinyin ?: cand.input)
        val digits = T9Lookup.encodeLetters(source)
        if (digits.isNotEmpty()) return digits.length
        return T9Lookup.encodeLetters(cand.input.replace("'", "")).length
    }

    fun materializeSegmentsIfNeeded(
        session: ComposingSession,
        targetSyllables: Int,
        dict: Dictionary
    ) {
        if (!dict.isLoaded) return

        while (session.pinyinStack.size < targetSyllables
            && session.rawT9Digits.isNotEmpty()
        ) {
            val next = CnT9SentencePlanner.decodeNextSegment(
                digits = session.rawT9Digits,
                manualCuts = session.t9ManualCuts,
                dict = dict
            ) ?: break

            val code = T9Lookup.encodeLetters(next)
            if (code.isEmpty()) break

            session.onPinyinSidebarClick(next, code)
        }
    }
}
