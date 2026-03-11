package com.example.myapp.dict.api

import com.example.myapp.dict.model.Candidate

interface Dictionary {
    val isLoaded: Boolean
    val debugInfo: String?

    fun setReady(ready: Boolean, info: String? = null)

    fun getPinyinPossibilities(digits: String): List<String>

    fun getSuggestionsFromPinyinStack(
        pinyinStack: List<String>,
        rawDigits: String
    ): List<Candidate>

    fun getSuggestions(
        input: String,
        isT9: Boolean,
        isChineseMode: Boolean
    ): List<Candidate>

    /**
     * 按拼音前缀查单字候选，供生僻字兜底使用。
     * [prefix] 为拼音字母前缀，如 "ni"、"zh"。
     */
    fun querySingleCharsWithPinyinPrefix(prefix: String): List<Candidate>
}
