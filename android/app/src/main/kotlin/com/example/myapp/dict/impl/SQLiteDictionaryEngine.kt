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

    /**
     * 中文 T9（有 pinyinStack 时）：只做“整音节精准匹配”，并按音节数回退。
     * 关键点：不允许出现超过当前拼音串的候选（例如 hancheng 不应出现 hanchenglu）。
     */
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
                limit = if (i == pinyinStack.size) 80 else 50,
                offset = 0,
                matchedLen = pinyinStr.length,
                exactMatch = true,          // 改为严格等值匹配（见 queryDb）
                pinyinFilter = null
            )

            for (c in res) {
                // i==1 时（回退到单字拼音），只允许单字，避免“han”出来一堆多字词组
                if (i == 1 && c.word.length != 1) continue

                if (seenWords.add(c.word)) {
                    resultList.add(c.copy(pinyinCount = i))
                }
            }
            if (resultList.size >= limit) break
        }

        return resultList
    }

    /**
     * 候选规则（你这次的需求）：
     * - 中文 QWERTY：只给“输入串完全等值”的词组候选；然后按“整音节”回退；最后才给单字的前缀回退（ha/h）。
     * - 中文 T9 digits：只给“digits 完全等值”的候选；然后只给单字的 digits 前缀回退（避免长词联想）。
     * - 中文模式仍保留：完整英文单词（以及极少量可控变体）可以在前排（你之前那条需求），这里继续提供 queryExactEnglish 供上层排序层使用。
     */
    override fun getSuggestions(input: String, isT9: Boolean, isChineseMode: Boolean): List<Candidate> {
        if (!isLoaded) return emptyList()

        val db = dbHelper.readableDatabase
        val limit = 300

        // 你已有的撇号分词逻辑保持不变（它本身是一个“特殊输入模式”）
        if (isChineseMode && !isT9 && input.contains("'")) {
            return getSuggestionsWithApostrophe(db, input)
        }

        // 英文模式：保持原逻辑
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

        // ---------------- 中文模式：严格匹配 + 回退（无联想长词） ----------------

        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()

        fun addUnique(list: List<Candidate>) {
            for (c in list) {
                if (seenWords.add(c.word)) resultList.add(c)
                if (resultList.size >= limit) return
            }
        }

        // 0) 先把“完整英文单词（少量）”放进结果（上层会再做中文优先/英文精确词置顶策略）
        addUnique(queryExactEnglish(db, input, isT9))

        // 1) 中文 T9（raw digits）：只做 digits 等值匹配，禁止更长 digits 的联想词
        if (isT9) {
            addUnique(
                queryDb(
                    db = db,
                    input = input,
                    isT9 = true,
                    lang = 0,
                    limit = 120,
                    offset = 0,
                    matchedLen = input.length,
                    exactMatch = true,       // digits 等值匹配（见 queryDb）
                    pinyinFilter = null
                )
            )

            // digits 的回退：只给单字（word_len=1），按 digits 前缀逐步缩短
            var cutLen = input.length - 1
            while (cutLen >= 1 && resultList.size < limit) {
                val prefix = input.substring(0, cutLen)
                addUnique(querySingleCharByT9Prefix(db, prefix, limit = 60))
                cutLen--
            }

            return resultList
        }

        // 2) 中文 QWERTY（拼音串）：按整音节切分，做“整音节回退”
        val lower = input.lowercase()
        val syllables = splitConcatPinyinToSyllables(lower)

        if (syllables.isEmpty()) {
            // 不能切分成合法拼音音节（用户可能输入未完成）：只给单字前缀回退，避免长词联想
            addUnique(querySingleCharByInputPrefix(db, lower).take(120))
            return resultList
        }

        // 2.1 全长精准匹配：input 等值
        addUnique(
            queryDb(
                db = db,
                input = syllables.joinToString(""),
                isT9 = false,
                lang = 0,
                limit = 120,
                offset = 0,
                matchedLen = lower.length,
                exactMatch = true,          // 改为严格等值匹配（见 queryDb）
                pinyinFilter = null
            )
        )

        // 2.2 逐字（逐音节）回退：hancheng -> han；三音节就回退到前两音节，再到前一音节
        for (k in (syllables.size - 1) downTo 1) {
            if (resultList.size >= limit) break

            val prefix = syllables.take(k).joinToString("")

            if (k >= 2) {
                // 多音节：只给“词组精准匹配”（等值）
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
                // 单音节：只给单字精准匹配（han 只给“韩/喊/汗/汉...”这类，不给“汉城路”）
                addUnique(querySingleCharByInputExact(db, prefix, limit = 200))

                // 单音节匹配完后：才允许更短前缀单字回退（ha/h）
                var len = prefix.length - 1
                while (len >= 1 && resultList.size < limit) {
                    val p = prefix.substring(0, len)
                    addUnique(querySingleCharByInputPrefix(db, p).take(120))
                    len--
                }
            }
        }

        return resultList
    }

    // ---------------- 拼音切分：把 "hancheng" 切成 ["han","cheng"] ----------------

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

    // ---------------- 原有撇号模式逻辑（不改） ----------------

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

    // ---------------- queryDb：把“exactMatch=true”的中文从 LIKE 改成严格等值 ----------------

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
                    // 中文：严格等值匹配，禁止 input% 造成的“更长联想词”
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

    // ---------------- 英文完整词（少量）保持原逻辑 ----------------

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
