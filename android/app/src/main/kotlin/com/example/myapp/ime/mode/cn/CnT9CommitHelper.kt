package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession

/**
 * 候选提交辅助：负责计算候选消费的音节数/数字数，以及按需物化音节段。
 *
 * 职责：
 *  1. resolveConsumeSyllables   — 决定提交时消费几个已物化音节
 *  2. resolveDigitsToConsume    — 决定提交时消费几个 rawDigits
 *  3. materializeSegmentsIfNeeded — 在提交前按需把 rawDigits 物化为音节
 */
object CnT9CommitHelper {

    /**
     * 计算候选应消费的音节数（从 pinyinStack 里弹出多少段）。
     * 优先读 cand.pinyinCount，其次 cand.syllables，最后用拼音串切分。
     */
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

    /**
     * 计算候选应消费的 rawDigits 位数（stack 为空时走此路径）。
     */
    fun resolveDigitsToConsume(cand: Candidate): Int {
        val source = CnT9PinyinSplitter.normalize(cand.pinyin ?: cand.input)
        val digits = T9Lookup.encodeLetters(source)
        if (digits.isNotEmpty()) return digits.length
        return T9Lookup.encodeLetters(cand.input.replace("'", "")).length
    }

    /**
     * 按需将 rawDigits 物化为音节，直到 pinyinStack 达到 targetSyllables 个。
     * 需要字典已加载且 dict 参数非空。
     */
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
