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

    private val pinyinPrefixSet: Set<String> by lazy {
        val set = HashSet<String>(allPinyins.size * 3)
        for (py in allPinyins) {
            val s = py.lowercase()
            for (i in 1..s.length) set.add(s.substring(0, i))
        }
        set
    }

    private fun bestPinyinPrefix(rawLower: String): String {
        val s = rawLower.lowercase()
        if (s.isEmpty()) return ""
        if (!s.all { it in 'a'..'z' }) return s.take(1)
        val max = minOf(maxPinyinLen, s.length)
        for (l in max downTo 1) {
            val sub = s.substring(0, l)
            if (pinyinPrefixSet.contains(sub)) return sub
        }
        return s.take(1)
    }

    override fun setReady(ready: Boolean, info: String?) {
        isLoaded = ready
        if (info != null) debugInfo = info
    }

    override fun getPinyinPossibilities(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()

        data class Item(val pinyin: String, val code: String)

        val items = ArrayList<Item>()

        for (pinyin in allPinyins) {
            val t9 = T9Lookup.encodeLetters(pinyin)
            if (digits.startsWith(t9) || t9.startsWith(digits)) {
                items.add(Item(pinyin, t9))
            }
        }

        val sorted = items.sortedWith(
            compareBy<Item>(
                { if (it.code == digits) 0 else 1 },
                { it.code.length },
                { it.pinyin }
            )
        )

        val out = LinkedHashSet<String>()

        if (digits.length == 1) {
            val firstDigit = digits[0]
            for (s in T9Lookup.charsFromDigit(firstDigit)) out.add(s)
        }

        for (it in sorted) out.add(it.pinyin)

        return out.toList()
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

        val isAsciiLetters = !isT9 && norm.isNotEmpty() && norm.all { it in 'a'..'z' }
        val isAcronymLikeQwerty = !isT9
                && isAsciiLetters
                && norm.length in 2..6
                && norm.none { it == 'a' || it == 'e' || it == 'i' || it == 'o' || it == 'u' || it == 'v' }

        val allowEnglishExactInChineseQwerty =
            !isT9 && isAsciiLetters && !isAcronymLikeQwerty && norm.length >= 4

        // 0) 中文模式：英文精确匹配
        var exactEn = if (isT9) {
            if (norm.length >= 4) {
                queryExactEnglish(
                    db = db,
                    input = norm,
                    isT9 = true,
                    minWordLen = 4,
                    exactWordLen = norm.length
                )
            } else {
                emptyList()
            }
        } else {
            if (allowEnglishExactInChineseQwerty) {
                queryExactEnglish(
                    db = db,
                    input = norm,
                    isT9 = false,
                    minWordLen = 1,
                    exactWordLen = null
                )
            } else {
                emptyList()
            }
        }

        // 严格过滤（但不一刀切）：
        // 若输入是“完整拼音串”且确实存在中文候选，则屏蔽英文精确候选（以及 s/es 变体），避免 zhuan/zhuang 这种纯拼音串出现在候选里。
        var suppressEnglishBecausePinyin = false
        if (!isT9 && allowEnglishExactInChineseQwerty && isAsciiLetters) {
            val syl = splitConcatPinyinToSyllables(norm)
            val isCompletePinyin = syl.isNotEmpty() && syl.joinToString("") == norm
            if (isCompletePinyin) {
                val chineseProbe = queryDb(
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
                queryExactEnglish(
                    db = db,
                    input = "${norm}s",
                    isT9 = false,
                    minWordLen = 1,
                    exactWordLen = null
                )
            )
            addUnique(
                queryExactEnglish(
                    db = db,
                    input = "${norm}es",
                    isT9 = false,
                    minWordLen = 1,
                    exactWordLen = null
                )
            )
        }

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

        val syllables = splitConcatPinyinToSyllables(norm)
        val isCompletePinyin = syllables.isNotEmpty() && syllables.joinToString("") == norm

        if (isCompletePinyin) {
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

        val partial = splitPartialPinyinPrefix(norm)
        if (partial != null) {
            val fullSyllablePrefix = partial.fullSyllables.joinToString("")
            val desiredWordLen = partial.fullSyllables.size + 1

            addUnique(
                queryChinesePrefixWithWordLenEq(
                    db = db,
                    prefix = norm,
                    wordLen = desiredWordLen,
                    limit = 140
                )
            )

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

        if (exactEn.isNotEmpty() && isAsciiLetters && norm.length >= 4) {
            val first = bestPinyinPrefix(norm)
            if (first.isNotEmpty()) {
                addUnique(querySingleCharByInputPrefix(db, first).take(200))
            }
            return resultList
        }

        if (isAsciiLetters && norm.length >= 2) {
            addUnique(queryAcronymPrefixByWordLenEq(db, prefix = norm, wordLen = norm.length, limit = 160))
        }

        addUnique(queryChinesePrefixWithMaxWordLen(db, prefix = norm, maxWordLen = 2, limit = 120))

        val fallbackPrefix = bestPinyinPrefix(norm)
        if (fallbackPrefix.isNotEmpty()) {
            addUnique(querySingleCharByInputPrefix(db, fallbackPrefix).take(200))
        }

        return resultList
    }

    private data class PartialPinyinPrefix(
        val fullSyllables: List<String>,
        val remainder: String
    )

    private fun splitPartialPinyinPrefix(rawLower: String): PartialPinyinPrefix? {
        if (rawLower.isEmpty()) return null
        if (!rawLower.all { it in 'a'..'z' }) return null

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
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_INPUT}, ${DictionaryDbHelper.COL_SYLLABLES}, ${DictionaryDbHelper.COL_ACRONYM}
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

    private fun queryAcronymPrefixByWordLenEq(db: SQLiteDatabase, prefix: String, wordLen: Int, limit: Int): List<Candidate> {
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

    private fun queryChinesePrefixWithWordLenEq(db: SQLiteDatabase, prefix: String, wordLen: Int, limit: Int): List<Candidate> {
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

    private fun queryChinesePrefixWithMaxWordLen(db: SQLiteDatabase, prefix: String, maxWordLen: Int, limit: Int): List<Candidate> {
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

    private fun querySingleCharByInputPrefix(db: SQLiteDatabase, prefix: String): List<Candidate> {
        val list = ArrayList<Candidate>()
        try {
            val sql = """
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_ACRONYM}
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
                    val acronym = try { it.getString(2) } catch (_: Exception) { null }
                    list.add(Candidate(word, prefix, freq, matchedLength = prefix.length, pinyinCount = 0, acronym = acronym))
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
                SELECT ${DictionaryDbHelper.COL_WORD}, ${DictionaryDbHelper.COL_FREQ}, ${DictionaryDbHelper.COL_ACRONYM}
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
                    val acronym = try { it.getString(2) } catch (_: Exception) { null }
                    list.add(Candidate(word, input, freq, matchedLength = input.length, pinyinCount = 0, acronym = acronym))
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

    private fun queryExactEnglish(
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
