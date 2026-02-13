package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.model.Candidate

class ChineseQwertySuggester(
    private val analyzer: PinyinInputAnalyzer,
    private val queries: SQLiteWordQueries
) {

    fun suggest(db: SQLiteDatabase, normLower: String): List<Candidate> {
        val limit = 300

        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()

        fun addUnique(list: List<Candidate>) {
            for (c in list) {
                if (seenWords.add(c.word)) resultList.add(c)
                if (resultList.size >= limit) return
            }
        }

        val norm = normLower.lowercase()

        val isAsciiLetters = norm.isNotEmpty() && norm.all { it in 'a'..'z' }
        val isAcronymLikeQwerty = isAsciiLetters
                && norm.length in 2..6
                && norm.none { it == 'a' || it == 'e' || it == 'i' || it == 'o' || it == 'u' || it == 'v' }

        val allowEnglishExactInChineseQwerty =
            isAsciiLetters && !isAcronymLikeQwerty && norm.length >= 4

        // 0) 中文模式：英文精确匹配（用于中英混输）
        var exactEn = if (allowEnglishExactInChineseQwerty) {
            queries.queryExactEnglish(
                db = db,
                input = norm,
                isT9 = false,
                minWordLen = 1,
                exactWordLen = null
            )
        } else {
            emptyList()
        }

        // 若输入是“完整拼音串”且确实存在中文候选，则屏蔽英文精确候选（以及 s/es 变体）
        var suppressEnglishBecausePinyin = false
        if (allowEnglishExactInChineseQwerty && isAsciiLetters) {
            val syl = analyzer.splitConcatPinyinToSyllables(norm)
            val isCompletePinyin = syl.isNotEmpty() && syl.joinToString("") == norm
            if (isCompletePinyin) {
                val chineseProbe = queries.queryDb(
                    db = db,
                    input = norm,
                    isT9 = false,
                    lang = 0,
                    limit = 1,
                    offset = 0,
                    matchedLen = norm.length,
                    exactMatch = true,
                    pinyinFilter = null
                )
                if (chineseProbe.isNotEmpty()) {
                    suppressEnglishBecausePinyin = true
                    exactEn = emptyList()
                }
            }
        }

        addUnique(exactEn)

        if (!suppressEnglishBecausePinyin && allowEnglishExactInChineseQwerty && isAsciiLetters && norm.length in 2..32) {
            addUnique(
                queries.queryExactEnglish(
                    db = db,
                    input = "${norm}s",
                    isT9 = false,
                    minWordLen = 1,
                    exactWordLen = null
                )
            )
            addUnique(
                queries.queryExactEnglish(
                    db = db,
                    input = "${norm}es",
                    isT9 = false,
                    minWordLen = 1,
                    exactWordLen = null
                )
            )
        }

        val syllables = analyzer.splitConcatPinyinToSyllables(norm)
        val isCompletePinyin = syllables.isNotEmpty() && syllables.joinToString("") == norm

        if (isCompletePinyin) {
            addUnique(
                queries.queryDb(
                    db = db,
                    input = norm,
                    isT9 = false,
                    lang = 0,
                    limit = 120,
                    offset = 0,
                    matchedLen = norm.length,
                    exactMatch = true,
                    pinyinFilter = null
                )
            )

            for (k in (syllables.size - 1) downTo 1) {
                if (resultList.size >= limit) break

                val prefix = syllables.take(k).joinToString("")
                if (k >= 2) {
                    addUnique(
                        queries.queryDb(
                            db = db,
                            input = prefix,
                            isT9 = false,
                            lang = 0,
                            limit = 80,
                            offset = 0,
                            matchedLen = prefix.length,
                            exactMatch = true,
                            pinyinFilter = null
                        )
                    )
                } else {
                    addUnique(queries.querySingleCharByInputExact(db, prefix, limit = 220))

                    var len = prefix.length - 1
                    while (len >= 1 && resultList.size < limit) {
                        val p = prefix.substring(0, len)
                        addUnique(queries.querySingleCharByInputPrefix(db, p).take(150))
                        len--
                    }
                }
            }

            return resultList
        }

        val partial = analyzer.splitPartialPinyinPrefix(norm)
        if (partial != null) {
            val fullSyllablePrefix = partial.fullSyllables.joinToString("")
            val desiredWordLen = partial.fullSyllables.size + 1

            addUnique(
                queries.queryChinesePrefixWithWordLenEq(
                    db = db,
                    prefix = norm,
                    wordLen = desiredWordLen,
                    limit = 140
                )
            )

            if (fullSyllablePrefix.isNotEmpty()) {
                addUnique(queries.querySingleCharByInputExact(db, fullSyllablePrefix, limit = 220))
                var len = fullSyllablePrefix.length - 1
                while (len >= 1 && resultList.size < limit) {
                    val p = fullSyllablePrefix.substring(0, len)
                    addUnique(queries.querySingleCharByInputPrefix(db, p).take(150))
                    len--
                }
            }

            return resultList
        }

        if (exactEn.isNotEmpty() && isAsciiLetters && norm.length >= 4) {
            val first = analyzer.bestPinyinPrefix(norm)
            if (first.isNotEmpty()) {
                addUnique(queries.querySingleCharByInputPrefix(db, first).take(200))
            }
            return resultList
        }

        if (isAsciiLetters && norm.length >= 2) {
            addUnique(queries.queryAcronymPrefixByWordLenEq(db, prefix = norm, wordLen = norm.length, limit = 160))
        }

        addUnique(queries.queryChinesePrefixWithMaxWordLen(db, prefix = norm, maxWordLen = 2, limit = 120))

        val fallbackPrefix = analyzer.bestPinyinPrefix(norm)
        if (fallbackPrefix.isNotEmpty()) {
            addUnique(queries.querySingleCharByInputPrefix(db, fallbackPrefix).take(200))
        }

        return resultList
    }
}
