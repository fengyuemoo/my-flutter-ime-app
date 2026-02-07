package com.example.myapp.dict.model

data class Candidate(
    val word: String,
    val input: String,
    val priority: Int,
    val matchedLength: Int,
    val pinyinCount: Int,
    // NEW: 数据库里的拼音 input（中文模式特别有用；英文可为空）
    val pinyin: String? = null,
    // NEW: 数据库字段（如果词库里有填）；没有就为 0
    val syllables: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Candidate
        return word == other.word
    }

    override fun hashCode(): Int = word.hashCode()
}
