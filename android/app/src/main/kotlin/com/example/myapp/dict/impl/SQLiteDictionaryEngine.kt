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
    private val pinyinSet: Set<String> = allPinyins.toHashSet()
    private val maxPinyinLen: Int = allPinyins.maxOfOrNull { it.length } ?: 0

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
            val pinyinStr = currentStack.joinToString("").lowercase()

            // 这里保持“严格等值匹配”，避免 hancheng -> hanchenglu 这种长词联想
            val res = queryDb(
                db = db,
                input = pinyinStr,
                isT9 = false,
                lang = 0,
                limit = if (i == pinyinStack.size) 80 else 50,
                offset = 0,
                matchedLen = pinyinStr.length,
                exactMatch = true,
                pinyinFilter = null
            )

            for (c in res) {
                // 回退到单音节时，只给单字，避免“han”出现一堆多字词组
                if (i == 1 && c.word.length != 1) continue

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

        // 对字母输入统一用小写去查库（键盘输入是大写时也能命中 year/yg/...）
        val norm = if (!isT9) input.lowercase() else input

        if (isChineseMode && !isT9 && norm.contains("'")) {
            return getSuggestionsWithApostrophe(db, norm)
        }

        if (!isChineseMode) {
            return queryDb(
                db = db,
                input = norm,
                isT9 = isT9,
                lang = 1,
                limit = limit,
                offset = 0,
                matchedLen = norm.length,
                exactMatch = false,
                pinyinFilter = null
            )
        }

        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()

        fun addUnique(list: List<Candidate>) {
            for (c in list) {
                if (seenWords.add(c.word)) resultList.add(c)
                if (resultList.size >= limit) return
            }
        }

        // 0) 中文模式仍保留：完整英文词 + 少量可控变体（s / es）
        val exactEn = queryExactEnglish(db, norm, isT9)
        addUnique(exactEn)

        val isAsciiLetters = !isT9 && norm.isNotEmpty() && norm.all { it in 'a'..'z' }
        if (isAsciiLetters && norm.length in 2..32) {
            addUnique(queryExactEnglish(db, "${norm}s", false))
            addUnique(queryExactEnglish(db, "${norm}es", false))
        }

        // 1) 中文 T9：整串 digits + 单字回退（保持你已实现的“无长词联想”）
        if (isT9) {
            val exactDigits = queryDb(
                db = db,
                input = norm,
                isT9 = true,
                lang = 0,
                limit = 120,
                offset = 0,
                matchedLen = norm.length,
                exactMatch = true,
                pinyinFilter = null
            )
            addUnique(exactDigits)

            var cutLen = norm.length
            while (cutLen >= 1 && resultList.size < limit) {
                val prefix = norm.substring(0, cutLen)
                addUnique(querySingleCharByT9Prefix(db, prefix, limit = if (cutLen == norm.length) 200 else 80))
                cutLen--
            }

            return resultList
        }

        // 2) 中文 QWERTY：判断是否“完整拼音音节串”
        val syllables = splitConcatPinyinToSyllables(norm)
        val isCompletePinyin = syllables.isNotEmpty() && syllables.joinToString("") == norm

        if (isCompletePinyin) {
            // 2.1 全长严格等值匹配（禁止出现 hanchenglu 这类更长词）
            addUnique(
                queryDb(
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

            // 2.2 按音节回退（hancheng -> han）
            for (k in (syllables.size - 1) downTo 1) {
                if (resultList.size >= limit) break

                val prefix = syllables.take(k).joinToString("")
                if (k >= 2) {
                    addUnique(
                        queryDb(
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
                    addUnique(querySingleCharByInputExact(db, prefix, limit = 220))

                    var len = prefix.length - 1
                    while (len >= 1 && resultList.size < limit) {
                        val p = prefix.substring(0, len)
                        addUnique(querySingleCharByInputPrefix(db, p).take(150))
                        len--
                    }
                }
            }

            return resultList
        }

        // 3) 非完整拼音：分两种情况处理，避免“hanc/year”又冒出长词联想

        // 3.A) “部分拼音 + 下一音节首字母”：例如 hanc / hanch / hanche
        val partial = splitPartialPinyinPrefix(norm)
        if (partial != null) {
            val fullSyllablePrefix = partial.fullSyllables.joinToString("")
            val desiredWordLen = partial.fullSyllables.size + 1

            // 只给“2字词”（或 3 字、取决于 desiredWordLen）——直接砍掉 韩村河/汉城路/汉初三杰 等长词
            addUnique(
                queryChinesePrefixWithWordLenEq(
                    db = db,
                    prefix = norm,
                    wordLen = desiredWordLen,
                    limit = 140
                )
            )

            // 再追加：回退到“已完成的最后一个整音节”的单字（han -> 韩/喊/汗/汉...），以及更短前缀单字（ha/h）
            if (fullSyllablePrefix.isNotEmpty()) {
                addUnique(querySingleCharByInputExact(db, fullSyllablePrefix, limit = 220))
                var len = fullSyllablePrefix.length - 1
                while (len >= 1 && resultList.size < limit) {
                    val p = fullSyllablePrefix.substring(0, len)
                    addUnique(querySingleCharByInputPrefix(db, p).take(150))
                    len--
                }
            }

            return resultList
        }

        // 3.B) 命中“完整英文词”（例如 year）：英文放前排后，中文只给单字回退，避免出现联想长词
        if (exactEn.isNotEmpty() && isAsciiLetters) {
            val first = norm.take(1)
            if (first.isNotEmpty()) {
                addUnique(querySingleCharByInputPrefix(db, first).take(200))
            }
            return resultList
        }

        // 3.C) 其他缩写/前缀（yg 等）：允许 2 字缩写词组，但仍限制最长词长，避免长词刷屏
        if (isAsciiLetters && norm.length >= 2) {
            addUnique(queryAcronymPrefixByWordLenEq(db, prefix = norm, wordLen = 2, limit = 100))
        }

        // 再给少量“前缀匹配”的中文词，但限制 word_len <= 2（避免韩村河/汉初三杰等长词）
        addUnique(queryChinesePrefixWithMaxWordLen(db, prefix = norm, maxWordLen = 2, limit = 120))

        // 最后补单字（按首字母/前缀）
        addUnique(querySingleCharByInputPrefix(db, norm.take(1)).take(200))

        return resultList
    }

    private data class PartialPinyinPrefix(
        val fullSyllables: List<String>,
        val remainder: String
    )

    /**
     * 在无法完整切分时，尝试找到“最长的可切分前缀”。
     * 例：hanc -> fullSyllables=[han], remainder="c"
     */
    private fun splitPartialPinyinPrefix(rawLower: String): PartialPinyinPrefix? {
        if (rawLower.isEmpty()) return null
        if (!rawLower.all { it in 'a'..'z' }) return null

        // 从长到短找一个能完整切分的前缀（且必须留下 remainder）
        for (cut in (rawLower.length - 1) downTo 1) {
            val prefix = rawLower.substring(0, cut)
            val syl = splitConcatPinyinToSyllables(prefix)
            if (syl.isNotEmpty() && syl.joinToString("") == prefix) {
                val rem = rawLower.substring(cut)
                if (rem.isNotEmpty()) {
                    return PartialPinyinPrefix(fullSyllables = syl, remainder = rem)
                }
            }
        }
        return null
    }

    // -------- 拼音切分：hancheng -> [han, cheng]；无法完全切分则返回 empty --------

    private fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        if (rawLower.isEmpty()) return emptyList()
        if (!rawLower.all { it in 'a'..'z' }) return emptyList()
        if (maxPinyinLen <= 0) return emptyList()

        val out = ArrayList<String>()
        var i = 0
        while (i < rawLower.length) {
            val remain = rawLower.length - i
            val tryMax = minOf(maxPinyinLen, remain)
            var matched: String? = null
            var l = tryMax
            while (l >= 1) {
                val sub = rawLower.substring(i, i + l)
                if (pinyinSet.contains(sub)) {
                    matched = sub
                    break
                }
                l--
            }
            if (matched == null) return emptyList()
            out.add(matched)
            i += matched.length
        }
        return out
    }

    // ---------------- 撇号模式（保持不变） ----------------

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

    private fun queryAcronymPrefixByWordLenEq(db: SQLiteDatabase, prefix: String, wordLen: Int, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
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
                    list.add(Candidate(word, prefix, freq, matchedLength = prefix.length, pinyinCount = 0))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun queryChinesePrefixWithWordLenEq(db: SQLiteDatabase, prefix: String, wordLen: Int, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
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
                    list.add(Candidate(word, prefix, freq, matchedLength = prefix.length, pinyinCount = 0))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun queryChinesePrefixWithMaxWordLen(db: SQLiteDatabase, prefix: String, maxWordLen: Int, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
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
                    list.add(Candidate(word, prefix, freq, matchedLength = prefix.length, pinyinCount = 0))
                }
            }
        } catch (_: Exception) {
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

    private fun querySingleCharByInputExact(db: SQLiteDatabase, input: String, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
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
                    list.add(Candidate(word, input, freq, matchedLength = input.length, pinyinCount = 0))
                }
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun querySingleCharByT9Prefix(db: SQLiteDatabase, digitsPrefix: String, limit: Int): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}
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
                    list.add(Candidate(word, digitsPrefix, freq, matchedLength = digitsPrefix.length, pinyinCount = 0))
                }
            }
        } catch (_: Exception) {
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
                    // 中文：严格等值匹配（用于“完整拼音/完整 digits”场景，杜绝长词联想）
                    if (!isT9) {
                        selection =
                            "($colToQuery = ? OR ${DictionaryDbHelper.COL_ACRONYM} = ?) AND ${DictionaryDbHelper.COL_LANG} = ?"
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
                "lower($col) = ? AND ${DictionaryDbHelper.COL_LANG} = 1",
                arrayOf(input.lowercase()),
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
