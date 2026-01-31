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
}
