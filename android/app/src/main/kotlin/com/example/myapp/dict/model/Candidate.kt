package com.example.myapp.dict.model

data class Candidate(
    val word: String,
    val input: String,
    val priority: Int,
    val matchedLength: Int,
    val pinyinCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Candidate
        return word == other.word
    }

    override fun hashCode(): Int = word.hashCode()
}
