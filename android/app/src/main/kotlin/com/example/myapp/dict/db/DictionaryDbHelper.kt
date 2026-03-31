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

    // 修复：移除 onOpen 中的 WAL PRAGMA。
    //
    // 原问题：onOpen 在 readableDatabase 首次打开时由 SQLiteOpenHelper 调用，
    // 此时 Android 内部会先尝试以读写模式打开文件。若 DictionaryInstaller
    // 后台线程正在向同一文件写入，PRAGMA journal_mode=WAL 会请求写锁，
    // 触发 SQLite busy timeout（默认 30 秒），造成 T9 模式 30~60 秒卡顿。
    //
    // 正确做法：WAL 模式应由 SQLiteDictionaryEngine.setReady() 在
    // installer 完成后（文件写入已结束）通过专用读写连接设置，
    // 不在 onOpen 里做任何写操作。
    //
    // 注意：如果词库文件从 assets 复制时已经是 WAL 模式（通过打包工具预设），
    // 则完全不需要在运行时设置，直接删除即可。
}
