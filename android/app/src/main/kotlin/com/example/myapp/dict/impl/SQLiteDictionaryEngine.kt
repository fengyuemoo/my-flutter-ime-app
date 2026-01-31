package com.example.myapp.dict.impl

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.db.DictionaryDbHelper
import com.example.myapp.dict.model.Candidate

class SQLiteDictionaryEngine(private val context: Context) : Dictionary {

    private val dbHelper = DictionaryDbHelper(context)

    @Volatile
    override var isLoaded: Boolean = false
        private set

    override var debugInfo: String? = "等待部署..."
        private set

    private val allPinyins: List<String> = PinyinTable.allPinyins

    /**
     * 由 DictionaryInstaller 安装完 dictionary.db 后调用。
     */
    override fun setReady(ready: Boolean, info: String?) {
        isLoaded = ready
        if (info != null) debugInfo = info
    }

    override fun getPinyinPossibilities(digits: String): List<String> {
        val possiblePinyins = LinkedHashSet<String>()
        for (pinyin in allPinyins) {
            val t9 = T9Lookup.encodeLetters(pinyin)
            if (digits.startsWith(t9)) possiblePinyins.add(pinyin)
            else if (t9.startsWith(digits)) possiblePinyins.add(pinyin)
        }
        if (digits.isNotEmpty()) {
            val firstDigit = digits[0]
            possiblePinyins.addAll(T9Lookup.charsFromDigit(firstDigit))
        }
        return possiblePinyins.toList()
    }

    override fun getSuggestionsFromPinyinStack(pinyinStack: List<String>, rawDigits: String): List<Candidate> {
        if (!isLoaded) return emptyList()
        val db = dbHelper.readableDatabase
        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()
        val limit = 300

        for (i in pinyinStack.size downTo 1) {
            val currentStack = pinyinStack.subList(0, i)
            val pinyinStr = currentStack.joinToString("")

            val res = queryDb(
                db = db,
                input = pinyinStr,
                isT9 = false,
                lang = 0,
                limit = 50,
                offset = 0,
                matchedLen = 0,
                exactMatch = false,
                pinyinFilter = null
            )

            for (c in res) {
                if (seenWords.add(c.word)) {
                    resultList.add(c.copy(pinyinCount = i))
                }
            }
            if (resultList.size >= limit) break
        }

        return resultList
    }

    override fun getSuggestions(input: String, isT9: Boolean, isChineseMode: Boolean): List<Candidate> {
        if (!isLoaded) return emptyList()

        val db = dbHelper.readableDatabase
        val limit = 300

        if (isChineseMode && !isT9 && input.contains("'")) {
            return getSuggestionsWithApostrophe(db, input)
        }

        if (!isChineseMode) {
            return queryDb(
                db = db,
                input = input,
                isT9 = isT9,
                lang = 1,
                limit = limit,
                offset = 0,
                matchedLen = input.length,
                exactMatch = false,
                pinyinFilter = null
            )
        }

        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()

        val exactEn = queryExactEnglish(db, input, isT9)
        for (c in exactEn) {
            if (seenWords.add(c.word)) resultList.add(c)
        }

        var currentLen = input.length
        val minLen = 1

        while (currentLen >= minLen) {
            val subInput = input.substring(0, currentLen)
            val subLimit = if (currentLen == input.length) 50 else 30
            val res = queryDb(
                db = db,
                input = subInput,
                isT9 = isT9,
                lang = 0,
                limit = subLimit,
                offset = 0,
                matchedLen = currentLen,
                exactMatch = true,
                pinyinFilter = null
            )

            for (c in res) {
                if (seenWords.add(c.word)) resultList.add(c)
            }
            if (resultList.size >= limit) break
            currentLen--
        }

        if (resultList.size < limit) {
            val remainingCount = limit - resultList.size
            val others = queryDb(
                db = db,
                input = input,
                isT9 = isT9,
                lang = 0,
                limit = remainingCount,
                offset = 0,
                matchedLen = input.length,
                exactMatch = false,
                pinyinFilter = null
            )
            for (c in others) if (seenWords.add(c.word)) resultList.add(c)
        }

        if (resultList.size < limit) {
            val remainingCount = limit - resultList.size
            val otherEn = queryDb(
                db = db,
                input = input,
                isT9 = isT9,
                lang = 1,
                limit = remainingCount,
                offset = 0,
                matchedLen = input.length,
                exactMatch = false,
                pinyinFilter = null
            )
            for (c in otherEn) if (seenWords.add(c.word)) resultList.add(c)
        }

        return resultList
    }

    private fun getSuggestionsWithApostrophe(db: SQLiteDatabase, rawInput: String): List<Candidate> {
        val parts = rawInput.split("'").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()

        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()

        fun addAll(list: List<Candidate>) {
            for (c in list) if (seen.add(c.word)) result.add(c)
        }

        val n = parts.size

        addAll(queryMultiCharByAcronymPrefix(db, parts, wordLen = n))
        for (k in (n - 1) downTo 2) addAll(queryMultiCharByAcronymPrefix(db, parts.take(k), wordLen = k))
        for (k in n downTo 1) addAll(querySingleCharByInputPrefix(db, parts.take(k).joinToString("")))

        return result
    }

    private fun queryMultiCharByAcronymPrefix(db: SQLiteDatabase, parts: List<String>, wordLen: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val acronymPrefix = parts.joinToString("") { it.take(1) }
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
                FROM ${DictionaryDbHelper.TABLE_NAME}
                WHERE ${DictionaryDbHelper.COL_ACRONYM} LIKE ?
                  AND ${DictionaryDbHelper.COL_LANG} = 0
                  AND ${DictionaryDbHelper.COL_WORD_LEN} = ?
                ORDER BY ${DictionaryDbHelper.COL_FREQ} DESC
            """.trimIndent()

            db.rawQuery(sql, arrayOf("${acronymPrefix}%", wordLen.toString())).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    list.add(Candidate(word, acronymPrefix, freq, matchedLength = 0, pinyinCount = 0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun querySingleCharByInputPrefix(db: SQLiteDatabase, prefix: String): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
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
                    list.add(Candidate(word, prefix, freq, matchedLength = prefix.length, pinyinCount = 0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun queryDb(
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
                        selection =
                            "($colToQuery LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?) AND ${DictionaryDbHelper.COL_LANG} = ?"
                        argsList.add("$input%")
                        argsList.add("$input%")
                        argsList.add("$lang")
                    } else {
                        selection = "$colToQuery LIKE ? AND ${DictionaryDbHelper.COL_LANG} = ?"
                        argsList.add("$input%")
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
                    selection =
                        "($colToQuery LIKE ? OR ${DictionaryDbHelper.COL_ACRONYM} LIKE ?) AND ${DictionaryDbHelper.COL_LANG} = ?"
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
                arrayOf(DictionaryDbHelper.COL_WORD, DictionaryDbHelper.COL_FREQ),
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
                    list.add(Candidate(word, input, freq, matchedLen, 0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun queryExactEnglish(db: SQLiteDatabase, input: String, isT9: Boolean): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val col = if (isT9) DictionaryDbHelper.COL_T9 else DictionaryDbHelper.COL_INPUT
            db.query(
                DictionaryDbHelper.TABLE_NAME,
                arrayOf(DictionaryDbHelper.COL_WORD, DictionaryDbHelper.COL_FREQ),
                "$col = ? AND ${DictionaryDbHelper.COL_LANG} = 1",
                arrayOf(input),
                null,
                null,
                "${DictionaryDbHelper.COL_FREQ} DESC",
                "3"
            ).use {
                while (it.moveToNext()) {
                    val word = it.getString(0)
                    val freq = it.getInt(1)
                    list.add(Candidate(word, input, freq, input.length, 0))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}
