package com.example.myapp.dict.impl

import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.model.Candidate

class EnglishSuggester(
    private val queries: SQLiteWordQueries
) {
    fun suggest(db: SQLiteDatabase, input: String, isT9: Boolean): List<Candidate> {
        return queries.queryDb(
            db = db,
            input = input,
            isT9 = isT9,
            lang = 1,
            limit = 300,
            offset = 0,
            matchedLen = input.length,
            exactMatch = false,
            pinyinFilter = null
        )
    }
}
