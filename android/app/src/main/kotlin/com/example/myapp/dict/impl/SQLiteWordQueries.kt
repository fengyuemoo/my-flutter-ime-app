package com.example.myapp.dict.impl

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.db.DictionaryDbHelper
import com.example.myapp.dict.model.Candidate
import java.util.Locale

class SQLiteWordQueries {

    fun queryDb(
        db: SQLiteDatabase,
        input: String,
        isT9: Boolean,
        lang: Int,
        limit: Int,
        offset: Int,
        matchedLen: Int,
        exactMatch: Boolean,
        pinyinFilter: String?
    ): List<Candidate> {
        val norm = input.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val targetCol = if (isT9) {
            DictionaryDbHelper.COL_T9
        } else {
            DictionaryDbHelper.COL_INPUT
        }

        val where = ArrayList<String>()
        val args = ArrayList<String>()

        where += "${DictionaryDbHelper.COL_LANG} = ?"
        args += lang.toString()

        where += if (exactMatch) {
            "$targetCol = ?"
        } else {
            "$targetCol LIKE ?"
        }
        args += if (exactMatch) norm else "${norm}%"

        val pyFilter = pinyinFilter?.trim()?.lowercase(Locale.ROOT)
        if (!pyFilter.isNullOrEmpty()) {
            where += "${DictionaryDbHelper.COL_INPUT} LIKE ?"
            args += "${pyFilter}%"
        }

        val orderBy = buildString {
            if (!exactMatch) {
                append("length($targetCol) ASC, ")
            }
            append("${DictionaryDbHelper.COL_FREQ} DESC, ")
            append("${DictionaryDbHelper.COL_WORD_LEN} DESC, ")
            append("length(${DictionaryDbHelper.COL_INPUT}) ASC, ")
            append("${DictionaryDbHelper.COL_WORD} ASC")
        }

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${where.joinToString(" AND ")}
            ORDER BY $orderBy
            LIMIT ? OFFSET ?
        """.trimIndent()

        args += limit.coerceAtLeast(1).toString()
        args += offset.coerceAtLeast(0).toString()

        return runQuery(
            db = db,
            sql = sql,
            args = args,
            matchedLength = matchedLen
        )
    }

    fun queryExactEnglish(
        db: SQLiteDatabase,
        input: String,
        isT9: Boolean,
        minWordLen: Int,
        exactWordLen: Int?
    ): List<Candidate> {
        val norm = input.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val where = ArrayList<String>()
        val args = ArrayList<String>()

        where += "${DictionaryDbHelper.COL_LANG} = 1"
        where += "${DictionaryDbHelper.COL_WORD} = ?"
        args += norm

        where += "${DictionaryDbHelper.COL_WORD_LEN} >= ?"
        args += minWordLen.coerceAtLeast(1).toString()

        if (exactWordLen != null && exactWordLen > 0) {
            where += "${DictionaryDbHelper.COL_WORD_LEN} = ?"
            args += exactWordLen.toString()
        }

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${where.joinToString(" AND ")}
            ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC, ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT 80
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = args,
            matchedLength = if (isT9) T9Lookup.encodeLetters(norm).length else norm.length
        )
    }

    fun querySingleCharByInputPrefix(
        db: SQLiteDatabase,
        prefix: String
    ): List<Candidate> {
        val norm = prefix.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${DictionaryDbHelper.COL_INPUT} LIKE ?
              AND ${DictionaryDbHelper.COL_LANG} = 0
              AND ${DictionaryDbHelper.COL_WORD_LEN} = 1
            ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC, ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT 220
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = listOf("${norm}%"),
            matchedLength = norm.length
        )
    }

    fun querySingleCharByInputExact(
        db: SQLiteDatabase,
        input: String,
        limit: Int
    ): List<Candidate> {
        val norm = input.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${DictionaryDbHelper.COL_INPUT} = ?
              AND ${DictionaryDbHelper.COL_LANG} = 0
              AND ${DictionaryDbHelper.COL_WORD_LEN} = 1
            ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC, ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT ?
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = listOf(norm, limit.coerceAtLeast(1).toString()),
            matchedLength = norm.length
        )
    }

    fun querySingleCharByT9Prefix(
        db: SQLiteDatabase,
        digitsPrefix: String,
        limit: Int
    ): List<Candidate> {
        val norm = digitsPrefix.filter { it in '0'..'9' }
        if (norm.isEmpty()) return emptyList()

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${DictionaryDbHelper.COL_T9} LIKE ?
              AND ${DictionaryDbHelper.COL_LANG} = 0
              AND ${DictionaryDbHelper.COL_WORD_LEN} = 1
            ORDER BY length(${DictionaryDbHelper.COL_T9}) ASC,
                     ${DictionaryDbHelper.COL_FREQ} DESC,
                     ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT ?
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = listOf("${norm}%", limit.coerceAtLeast(1).toString()),
            matchedLength = norm.length
        )
    }

    fun queryAcronymPrefixByWordLenEq(
        db: SQLiteDatabase,
        prefix: String,
        wordLen: Int,
        limit: Int
    ): List<Candidate> {
        val norm = prefix.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${DictionaryDbHelper.COL_ACRONYM} LIKE ?
              AND ${DictionaryDbHelper.COL_LANG} = 0
              AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
            ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC,
                     length(${DictionaryDbHelper.COL_INPUT}) ASC,
                     ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT ?
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = listOf("${norm}%", wordLen.coerceAtLeast(1).toString(), limit.coerceAtLeast(1).toString()),
            matchedLength = norm.length
        )
    }

    fun queryMultiCharByAcronymPrefix(
        db: SQLiteDatabase,
        parts: List<String>,
        wordLen: Int,
        strictInputPrefix: String?
    ): List<Candidate> {
        val acronymPrefix = parts
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .joinToString("") { it.take(1) }

        if (acronymPrefix.isEmpty()) return emptyList()

        val where = ArrayList<String>()
        val args = ArrayList<String>()

        where += "${DictionaryDbHelper.COL_ACRONYM} LIKE ?"
        args += "${acronymPrefix}%"

        where += "${DictionaryDbHelper.COL_LANG} = 0"
        where += "${DictionaryDbHelper.COL_WORD_LEN} = ?"
        args += wordLen.coerceAtLeast(1).toString()

        val strict = strictInputPrefix?.trim()?.lowercase(Locale.ROOT)
        if (!strict.isNullOrEmpty()) {
            where += "${DictionaryDbHelper.COL_INPUT} LIKE ?"
            args += "${strict}%"
        }

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE ${where.joinToString(" AND ")}
            ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC,
                     length(${DictionaryDbHelper.COL_INPUT}) ASC,
                     ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT 160
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = args,
            matchedLength = acronymPrefix.length
        ).map { it.copy(input = acronymPrefix) }
    }

    fun queryChinesePrefixWithWordLenEq(
        db: SQLiteDatabase,
        prefix: String,
        wordLen: Int,
        limit: Int
    ): List<Candidate> {
        val norm = prefix.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE (${DictionaryDbHelper.COL_INPUT} LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?)
              AND ${DictionaryDbHelper.COL_LANG} = 0
              AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
            ORDER BY length(${DictionaryDbHelper.COL_INPUT}) ASC,
                     ${DictionaryDbHelper.COL_FREQ} DESC,
                     ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT ?
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = listOf(
                "${norm}%",
                "${norm}%",
                wordLen.coerceAtLeast(1).toString(),
                limit.coerceAtLeast(1).toString()
            ),
            matchedLength = norm.length
        )
    }

    fun queryChinesePrefixWithMaxWordLen(
        db: SQLiteDatabase,
        prefix: String,
        maxWordLen: Int,
        limit: Int
    ): List<Candidate> {
        val norm = prefix.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()

        val sql = """
            SELECT
                ${DictionaryDbHelper.COL_WORD},
                ${DictionaryDbHelper.COL_FREQ},
                ${DictionaryDbHelper.COL_INPUT},
                ${DictionaryDbHelper.COL_SYLLABLES},
                ${DictionaryDbHelper.COL_ACRONYM}
            FROM ${DictionaryDbHelper.TABLE_NAME}
            WHERE (${DictionaryDbHelper.COL_INPUT} LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?)
              AND ${DictionaryDbHelper.COL_LANG} = 0
              AND ${DictionaryDbHelper.COL_WORD_LEN} <= ?
            ORDER BY ${DictionaryDbHelper.COL_WORD_LEN} ASC,
                     length(${DictionaryDbHelper.COL_INPUT}) ASC,
                     ${DictionaryDbHelper.COL_FREQ} DESC,
                     ${DictionaryDbHelper.COL_WORD} ASC
            LIMIT ?
        """.trimIndent()

        return runQuery(
            db = db,
            sql = sql,
            args = listOf(
                "${norm}%",
                "${norm}%",
                maxWordLen.coerceAtLeast(1).toString(),
                limit.coerceAtLeast(1).toString()
            ),
            matchedLength = norm.length
        )
    }

    private fun runQuery(
        db: SQLiteDatabase,
        sql: String,
        args: List<String>,
        matchedLength: Int
    ): List<Candidate> {
        val out = ArrayList<Candidate>()

        try {
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(cursor.toCandidate(matchedLength))
                }
            }
        } catch (_: Exception) {
        }

        return out
    }

    private fun Cursor.toCandidate(matchedLength: Int): Candidate {
        val word = getString(0)
        val freq = getIntOrZero(1)
        val input = getStringOrEmpty(2)
        val syllables = getIntOrZero(3)
        val acronym = getNullableString(4)

        return Candidate(
            word = word,
            input = input,
            priority = freq,
            matchedLength = matchedLength.coerceAtLeast(0),
            pinyinCount = 0,
            pinyin = input,
            syllables = syllables,
            acronym = acronym
        )
    }

    private fun Cursor.getStringOrEmpty(index: Int): String {
        return try {
            if (isNull(index)) "" else getString(index).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun Cursor.getNullableString(index: Int): String? {
        return try {
            if (isNull(index)) null else getString(index)
        } catch (_: Exception) {
            null
        }
    }

    private fun Cursor.getIntOrZero(index: Int): Int {
        return try {
            if (isNull(index)) 0 else getInt(index)
        } catch (_: Exception) {
            0
        }
    }
}
