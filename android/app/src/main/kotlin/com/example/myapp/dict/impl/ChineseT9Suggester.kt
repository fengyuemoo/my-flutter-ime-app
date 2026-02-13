package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.model.Candidate

class ChineseT9Suggester(
    private val queries: SQLiteWordQueries
) {
    fun suggest(db: SQLiteDatabase, digits: String): List<Candidate> {
        val limit = 300
        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()

        fun addUnique(list: List<Candidate>) {
            for (c in list) {
                if (seenWords.add(c.word)) resultList.add(c)
                if (resultList.size >= limit) return
            }
        }

        val exactDigits = queries.queryDb(
            db = db,
            input = digits,
            isT9 = true,
            lang = 0,
            limit = 120,
            offset = 0,
            matchedLen = digits.length,
            exactMatch = true,
            pinyinFilter = null
        )
        addUnique(exactDigits)

        var cutLen = digits.length
        while (cutLen >= 1 && resultList.size < limit) {
            val prefix = digits.substring(0, cutLen)
            addUnique(queries.querySingleCharByT9Prefix(db, prefix, limit = if (cutLen == digits.length) 200 else 80))
            cutLen--
        }

        return resultList
    }
}
