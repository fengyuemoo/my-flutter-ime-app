package com.example.myapp.dict.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DictionaryDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "dictionary.db"
        const val DATABASE_VERSION = 7

        const val TABLE_NAME = "words"
        const val COL_INPUT = "input"
        const val COL_ACRONYM = "acronym"
        const val COL_T9 = "t9"
        const val COL_WORD = "word"
        const val COL_FREQ = "freq"
        const val COL_LANG = "lang"
        const val COL_WORD_LEN = "word_len"
        const val COL_SYLLABLES = "syllables"
    }

    override fun onCreate(db: SQLiteDatabase) { /* 词库来自 assets，首次复制后直接可用 */ }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* 同上 */ }
}
