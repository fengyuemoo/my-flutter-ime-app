package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.model.Candidate

class ChineseT9Suggester(
    private val queries: SQLiteWordQueries
) {
    private data class ScoredCandidate(
        val candidate: Candidate,
        val exact: Boolean,
        val singleChar: Boolean,
        val score: Int
    )

    fun suggest(db: SQLiteDatabase, digits: String): List<Candidate> {
        val normalized = digits.filter { it in '0'..'9' }
        if (normalized.isEmpty()) return emptyList()

        val result = LinkedHashMap<String, ScoredCandidate>()
        val estimatedSyllables = estimateSyllables(normalized)

        fun pushAll(
            list: List<Candidate>,
            exact: Boolean,
            singleChar: Boolean,
            baseScore: Int
        ) {
            for (cand in list) {
                val score = buildScore(
                    cand = cand,
                    digits = normalized,
                    exact = exact,
                    singleChar = singleChar,
                    estimatedSyllables = estimatedSyllables,
                    baseScore = baseScore
                )

                val normalizedCand = cand.copy(
                    matchedLength = normalized.length
                )

                val incoming = ScoredCandidate(
                    candidate = normalizedCand,
                    exact = exact,
                    singleChar = singleChar,
                    score = score
                )

                val existing = result[normalizedCand.word]
                if (existing == null || incoming.score > existing.score) {
                    result[normalizedCand.word] = incoming
                }
            }
        }

        val exactPhrase = queries.queryDb(
            db = db,
            input = normalized,
            isT9 = true,
            lang = 0,
            limit = 120,
            offset = 0,
            matchedLen = normalized.length,
            exactMatch = true,
            pinyinFilter = null
        )
        pushAll(
            list = exactPhrase,
            exact = true,
            singleChar = false,
            baseScore = 4000
        )

        val prefixPhrase = queries.queryDb(
            db = db,
            input = normalized,
            isT9 = true,
            lang = 0,
            limit = 160,
            offset = 0,
            matchedLen = normalized.length,
            exactMatch = false,
            pinyinFilter = null
        )
        pushAll(
            list = prefixPhrase,
            exact = false,
            singleChar = false,
            baseScore = 2600
        )

        val exactSingle = queries.querySingleCharByT9Prefix(
            db = db,
            digitsPrefix = normalized,
            limit = 80
        )
        pushAll(
            list = exactSingle,
            exact = false,
            singleChar = true,
            baseScore = 1200
        )

        if (normalized.length >= 2) {
            var cut = normalized.length - 1
            while (cut >= 1 && result.size < 260) {
                val prefix = normalized.substring(0, cut)

                val shorterPrefix = queries.queryDb(
                    db = db,
                    input = prefix,
                    isT9 = true,
                    lang = 0,
                    limit = 40,
                    offset = 0,
                    matchedLen = prefix.length,
                    exactMatch = true,
                    pinyinFilter = null
                )

                pushAll(
                    list = shorterPrefix,
                    exact = false,
                    singleChar = false,
                    baseScore = 800 - (normalized.length - cut) * 40
                )

                cut--
            }
        }

        return result.values
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenByDescending { if (it.exact) 1 else 0 }
                    .thenByDescending { if (it.singleChar) 0 else 1 }
                    .thenByDescending { it.candidate.priority }
                    .thenByDescending { it.candidate.syllables }
                    .thenByDescending { it.candidate.word.length }
                    .thenBy { it.candidate.word }
            )
            .map { it.candidate }
            .take(300)
    }

    private fun buildScore(
        cand: Candidate,
        digits: String,
        exact: Boolean,
        singleChar: Boolean,
        estimatedSyllables: Int,
        baseScore: Int
    ): Int {
        val pinyin = cand.pinyin.orEmpty()
        val t9Len = T9Lookup.encodeLetters(pinyin).length
        val syllables = cand.syllables.coerceAtLeast(estimateSyllablesFromPinyin(pinyin))
        val lengthBonus = cand.word.length * 20
        val freqBonus = cand.priority.coerceAtLeast(0) / 1000
        val exactBonus = if (exact) 500 else 0
        val singlePenalty = if (singleChar) 300 else 0
        val t9DistancePenalty = kotlin.math.abs(t9Len - digits.length) * 35
        val syllableDistancePenalty = kotlin.math.abs(syllables - estimatedSyllables) * 60

        return baseScore +
            freqBonus +
            lengthBonus +
            exactBonus -
            singlePenalty -
            t9DistancePenalty -
            syllableDistancePenalty
    }

    private fun estimateSyllables(digits: String): Int {
        if (digits.isEmpty()) return 0
        return when {
            digits.length <= 2 -> 1
            digits.length <= 5 -> 2
            digits.length <= 8 -> 3
            digits.length <= 11 -> 4
            else -> (digits.length + 2) / 3
        }
    }

    private fun estimateSyllablesFromPinyin(pinyin: String): Int {
        if (pinyin.isBlank()) return 0
        return pinyin.split("'")
            .map { it.trim() }
            .count { it.isNotEmpty() }
            .takeIf { it > 0 }
            ?: 0
    }
}
