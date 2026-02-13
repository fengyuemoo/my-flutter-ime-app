package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.db.DictionaryDbHelper
import com.example.myapp.dict.model.Candidate

class SQLiteWordQueries {

    fun queryMultiCharByAcronymPrefix(
        db: SQLiteDatabase,
        parts: List<String>,
        wordLen: Int,
        strictInputPrefix: String?
    ): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val acronymPrefix = parts.joinToString("") { it.take(1) }

            val (sql, args) = if (!strictInputPrefix.isNullOrEmpty()) {
                val s = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_ACRONYM} LIKE ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
                  AND ${DictionaryDbHelper.COL_INPUT} LIKE ?
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
                """.trimIndent()
                s to arrayOf("${acronymPrefix}%", wordLen.toString(), "${strictInputPrefix}%")
            } else {
                val s = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_ACRONYM} LIKE ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
                """.trimIndent()
                s to arrayOf("${acronymPrefix}%", wordLen.toString())
            }

            db.rawQuery(sql, args).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = acronymPrefix,
                            priority = freq,
                            matchedLength = 0,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun queryAcronymPrefixByWordLenEq(db: SQLiteDatabase, prefix: String, wordLen: Int, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_ACRONYM} LIKE ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
                LIMIT ?
            """.trimIndent()

            db.rawQuery(sql, arrayOf("${prefix}%", wordLen.toString(), limit.toString())).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = prefix,
                            priority = freq,
                            matchedLength = prefix.length,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun queryChinesePrefixWithWordLenEq(db: SQLiteDatabase, prefix: String, wordLen: Int, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE (${DictionaryDbHelper.COL_INPUT} LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?)
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
                ORDER BY length(${DictionaryDbHelper.COL_INPUT}) ASC, ${DictionaryDbHelper.COL_FREQ} DESC
                LIMIT ?
            """.trimIndent()

            db.rawQuery(sql, arrayOf("${prefix}%", "${prefix}%", wordLen.toString(), limit.toString())).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = prefix,
                            priority = freq,
                            matchedLength = prefix.length,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun queryChinesePrefixWithMaxWordLen(db: SQLiteDatabase, prefix: String, maxWordLen: Int, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE (${DictionaryDbHelper.COL_INPUT} LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?)
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} <= ?
                ORDER BY length(${DictionaryDbHelper.COL_INPUT}) ASC, ${DictionaryDbHelper.COL_FREQ} DESC
                LIMIT ?
            """.trimIndent()

            db.rawQuery(sql, arrayOf("${prefix}%", "${prefix}%", maxWordLen.toString(), limit.toString())).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = prefix,
                            priority = freq,
                            matchedLength = prefix.length,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun querySingleCharByInputPrefix(db: SQLiteDatabase, prefix: String): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT},
                       ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_INPUT} LIKE ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = 1
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
            """.trimIndent()

            db.rawQuery(sql, arrayOf("${prefix}%")).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = prefix,
                            priority = freq,
                            matchedLength = prefix.length,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun querySingleCharByInputExact(db: SQLiteDatabase, input: String, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT},
                       ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_INPUT} = ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = 1
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
                LIMIT ?
            """.trimIndent()

            db.rawQuery(sql, arrayOf(input, limit.toString())).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = input,
                            priority = freq,
                            matchedLength = input.length,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun querySingleCharByT9Prefix(db: SQLiteDatabase, digitsPrefix: String, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_T9} LIKE ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = 1
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
                LIMIT ?
            """.trimIndent()

            db.rawQuery(sql, arrayOf("${digitsPrefix}%", limit.toString())).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = digitsPrefix,
                            priority = freq,
                            matchedLength = digitsPrefix.length,
                            pinyinCount = 0,
                            pinyin = pinyin,
                            syllables = syllables,
                            acronym = acronym
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

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
        val list = ArrayList<Candidate>()
        try {
            val colToQuery = if (isT9) DictionaryDbHelper.COL_T9 else DictionaryDbHelper.COL_INPUT
            var selection = ""
            val argsList = ArrayList<String>()
            val orderBy = "length($colToQuery) ASC, ${DictionaryDbHelper.COL_FREQ} DESC"

            if (exactMatch) {
                if (lang == 0) {
                    if (!isT9) {
                        selection = "($colToQuery = ? OR ${DictionaryDbHelper.COL_ACRONYM} = ?) AND ${DictionaryDbHelper.COL_LANG} = ?"
                        argsList.add(input)
                        argsList.add(input)
                        argsList.add("$lang")
                    } else {
                        selection = "$colToQuery = ? AND ${DictionaryDbHelper.COL_LANG} = ?"
                        argsList.add(input)
                        argsList.add("$lang")
                    }
                } else {
                    selection = "$colToQuery = ? AND ${DictionaryDbHelper.COL_LANG} = ?"
                    argsList.add(input)
                    argsList.add("$lang")
                }
            } else {
                selection = "$colToQuery LIKE ? AND ${DictionaryDbHelper.COL_LANG} = ?"
                argsList.add("$input%")
                argsList.add("$lang")

                if (!isT9 && lang == 0) {
                    selection = "($colToQuery LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?) AND ${DictionaryDbHelper.COL_LANG} = ?"
                    argsList.clear()
                    argsList.add("$input%")
                    argsList.add("$input%")
                    argsList.add("$lang")
                }
            }

            if (isT9 && pinyinFilter != null) {
                if (selection.isNotEmpty()) selection += " AND "
                selection += "${DictionaryDbHelper.COL_INPUT} LIKE ?"
                argsList.add("$pinyinFilter%")

                val t9Cond = "(${DictionaryDbHelper.COL_T9} LIKE ? OR ? LIKE ${DictionaryDbHelper.COL_T9} || '%')"
                selection += " AND $t9Cond"
                argsList.add("$input%")
                argsList.add(input)
            }

            db.query(
                DictionaryDbHelper.TABLE_NAME,
                arrayOf(
                    DictionaryDbHelper.COL_WORD,
                    DictionaryDbHelper.COL_FREQ,
                    DictionaryDbHelper.COL_INPUT,
                    DictionaryDbHelper.COL_SYLLABLES,
                    DictionaryDbHelper.COL_ACRONYM
                ),
                selection,
                argsList.toTypedArray(),
                null,
                null,
                orderBy,
                "$offset, $limit"
            ).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    val pinyin = it.getString(2)
                    val syllables = try { it.getInt(3) } catch (_: Exception) { 0 }
                    val acronym = try { it.getString(4) } catch (_: Exception) { null }

                    list.add(
                        Candidate(
                            word = word,
                            input = input,
                            priority = freq,
                            matchedLength = matchedLen,
                            pinyinCount = 0,
                            pinyin = if (lang == 0) pinyin else null,
                            syllables = if (lang == 0) syllables else 0,
                            acronym = if (lang == 0) acronym else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun queryExactEnglish(
        db: SQLiteDatabase,
        input: String,
        isT9: Boolean,
        minWordLen: Int,
        exactWordLen: Int?
    ): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val col = if (isT9) DictionaryDbHelper.COL_T9 else DictionaryDbHelper.COL_INPUT

            val selectionSb = StringBuilder()
            val args = ArrayList<String>()

            selectionSb.append("lower($col) = ?")
            args.add(input.lowercase())

            selectionSb.append(" AND ${DictionaryDbHelper.COL_LANG} = 1")
            selectionSb.append(" AND ${DictionaryDbHelper.COL_WORD_LEN} >= ?")
            args.add(minWordLen.toString())

            if (exactWordLen != null) {
                selectionSb.append(" AND ${DictionaryDbHelper.COL_WORD_LEN} = ?")
                args.add(exactWordLen.toString())
            }

            db.query(
                DictionaryDbHelper.TABLE_NAME,
                arrayOf(DictionaryDbHelper.COL_WORD, DictionaryDbHelper.COL_FREQ),
                selectionSb.toString(),
                args.toTypedArray(),
                null,
                null,
                "${DictionaryDbHelper.COL_FREQ} DESC",
                "3"
            ).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    list.add(
                        Candidate(
                            word = word,
                            input = input,
                            priority = freq,
                            matchedLength = input.length,
                            pinyinCount = 0,
                            pinyin = null,
                            syllables = 0,
                            acronym = null
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}
