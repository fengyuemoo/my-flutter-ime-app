package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.model.Candidate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class ChineseT9Suggester(
    private val queries: SQLiteWordQueries
) {

    private data class ScoredCandidate(
        val candidate: Candidate,
        val sourceRank: Int,
        val exactT9: Boolean,
        val exactPinyinT9: Boolean,
        val startsWithT9: Boolean,
        val fullPinyinMatch: Boolean,
        val syllableDistance: Int,
        val charLength: Int,
        val score: Int
    )

    fun suggest(db: SQLiteDatabase, digits: String): List<Candidate> {
        val normalized = digits.filter { it in '0'..'9' }
        if (normalized.isEmpty()) return emptyList()

        val result = LinkedHashMap<String, ScoredCandidate>()
        val estimatedSyllables = estimateSyllables(normalized)

        fun pushAll(
            list: List<Candidate>,
            sourceRank: Int,
            exactT9: Boolean,
            singleCharFallback: Boolean
        ) {
            for (cand in list) {
                val scored = scoreCandidate(
                    cand = cand,
                    digits = normalized,
                    estimatedSyllables = estimatedSyllables,
                    sourceRank = sourceRank,
                    singleCharFallback = singleCharFallback,
                    exactT9 = exactT9
                )

                val existing = result[scored.candidate.word]
                if (existing == null || isBetter(scored, existing)) {
                    result[scored.candidate.word] = scored
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
            sourceRank = 0,
            exactT9 = true,
            singleCharFallback = false
        )

        val prefixPhrase = queries.queryDb(
            db = db,
            input = normalized,
            isT9 = true,
            lang = 0,
            limit = 180,
            offset = 0,
            matchedLen = normalized.length,
            exactMatch = false,
            pinyinFilter = null
        )
        pushAll(
            list = prefixPhrase,
            sourceRank = 1,
            exactT9 = false,
            singleCharFallback = false
        )

        val exactSingle = queries.querySingleCharByT9Prefix(
            db = db,
            digitsPrefix = normalized,
            limit = 80
        )
        pushAll(
            list = exactSingle,
            sourceRank = 2,
            exactT9 = false,
            singleCharFallback = true
        )

        if (normalized.length >= 2) {
            var cut = normalized.length - 1
            while (cut >= 1 && result.size < 280) {
                val prefix = normalized.substring(0, cut)
                val shorterPrefix = queries.queryDb(
                    db = db,
                    input = prefix,
                    isT9 = true,
                    lang = 0,
                    limit = 50,
                    offset = 0,
                    matchedLen = prefix.length,
                    exactMatch = true,
                    pinyinFilter = null
                )

                pushAll(
                    list = shorterPrefix,
                    sourceRank = 3 + (normalized.length - cut),
                    exactT9 = false,
                    singleCharFallback = false
                )
                cut--
            }
        }

        return result.values
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenByDescending { if (it.exactT9) 1 else 0 }
                    .thenByDescending { if (it.exactPinyinT9) 1 else 0 }
                    .thenByDescending { if (it.startsWithT9) 1 else 0 }
                    .thenByDescending { if (it.fullPinyinMatch) 1 else 0 }
                    .thenBy { it.syllableDistance }
                    .thenBy { it.sourceRank }
                    .thenByDescending { it.candidate.priority }
                    .thenByDescending { it.charLength }
                    .thenByDescending { it.candidate.syllables }
                    .thenBy { it.candidate.word }
            )
            .map { it.candidate }
            .take(300)
    }

    private fun scoreCandidate(
        cand: Candidate,
        digits: String,
        estimatedSyllables: Int,
        sourceRank: Int,
        singleCharFallback: Boolean,
        exactT9: Boolean
    ): ScoredCandidate {
        val normPinyin = normalizePinyin(cand.pinyin ?: cand.input)
        val candT9 = T9Lookup.encodeLetters(normPinyin)
        val syllables = resolveSyllables(cand, normPinyin)
        val charLength = cand.word.length

        val exactPinyinT9 = candT9.isNotEmpty() && candT9 == digits
        val startsWithT9 = candT9.isNotEmpty() && candT9.startsWith(digits)
        val fullPinyinMatch = normPinyin.isNotEmpty() && candT9.isNotEmpty()

        val sourceBonus = when (sourceRank) {
            0 -> 4800
            1 -> 3200
            2 -> 1200
            else -> max(300, 900 - sourceRank * 90)
        }

        val exactT9Bonus = if (exactT9) 1200 else 0
        val exactPinyinBonus = if (exactPinyinT9) 900 else 0
        val prefixBonus = if (startsWithT9) 320 else 0
        val phraseBonus = if (charLength > 1) 260 else 0
        val singlePenalty = if (singleCharFallback) 420 else 0
        val t9DistancePenalty = abs(candT9.length - digits.length) * 55
        val syllableDistancePenalty = abs(syllables - estimatedSyllables) * 85
        val overLongPenalty = if (candT9.length > digits.length) (candT9.length - digits.length) * 25 else 0
        val freqBonus = cand.priority.coerceAtLeast(0) / 1000
        val charBonus = charLength * 24
        val syllableBonus = syllables * 18

        val score = sourceBonus +
            exactT9Bonus +
            exactPinyinBonus +
            prefixBonus +
            phraseBonus +
            freqBonus +
            charBonus +
            syllableBonus -
            singlePenalty -
            t9DistancePenalty -
            syllableDistancePenalty -
            overLongPenalty

        val normalizedCandidate = cand.copy(
            matchedLength = digits.length,
            pinyin = cand.pinyin ?: cand.input,
            pinyinCount = 0
        )

        return ScoredCandidate(
            candidate = normalizedCandidate,
            sourceRank = sourceRank,
            exactT9 = exactT9,
            exactPinyinT9 = exactPinyinT9,
            startsWithT9 = startsWithT9,
            fullPinyinMatch = fullPinyinMatch,
            syllableDistance = abs(syllables - estimatedSyllables),
            charLength = charLength,
            score = score
        )
    }

    private fun isBetter(
        a: ScoredCandidate,
        b: ScoredCandidate
    ): Boolean {
        return when {
            a.score != b.score -> a.score > b.score
            a.exactT9 != b.exactT9 -> a.exactT9
            a.exactPinyinT9 != b.exactPinyinT9 -> a.exactPinyinT9
            a.startsWithT9 != b.startsWithT9 -> a.startsWithT9
            a.fullPinyinMatch != b.fullPinyinMatch -> a.fullPinyinMatch
            a.syllableDistance != b.syllableDistance -> a.syllableDistance < b.syllableDistance
            a.sourceRank != b.sourceRank -> a.sourceRank < b.sourceRank
            a.candidate.priority != b.candidate.priority -> a.candidate.priority > b.candidate.priority
            a.charLength != b.charLength -> a.charLength > b.charLength
            else -> a.candidate.word < b.candidate.word
        }
    }

    private fun estimateSyllables(digits: String): Int {
        if (digits.isEmpty()) return 0
        return when {
            digits.length <= 2 -> 1
            digits.length <= 5 -> 2
            digits.length <= 8 -> 3
            digits.length <= 11 -> 4
            digits.length <= 14 -> 5
            else -> (digits.length + 2) / 3
        }
    }

    private fun resolveSyllables(cand: Candidate, normalizedPinyin: String): Int {
        if (cand.syllables > 0) return cand.syllables

        val bySplit = normalizedPinyin
            .split("'")
            .map { it.trim() }
            .count { it.isNotEmpty() }

        if (bySplit > 0) return bySplit

        return estimateSyllablesFromConcatPinyin(normalizedPinyin)
    }

    private fun estimateSyllablesFromConcatPinyin(pinyin: String): Int {
        if (pinyin.isBlank()) return 0

        var count = 0
        var i = 0
        val normalized = pinyin.replace("ü", "v")

        while (i < normalized.length) {
            var matched = false
            val tryMax = minOf(6, normalized.length - i)
            for (len in tryMax downTo 1) {
                val sub = normalized.substring(i, i + len)
                if (isPinyinLike(sub)) {
                    count++
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                i++
            }
        }

        return count.coerceAtLeast(1)
    }

    private fun isPinyinLike(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.all { it in 'a'..'z' || it == 'v' }
    }

    private fun normalizePinyin(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("ü", "v")
    }
}
