package com.example.myapp.ime.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object SymbolPrefs {

    private const val PREFS_NAME = "SymbolPrefs"
    private const val KEY_MRU_COMMON = "mru_common_symbols"

    // 建议：24 或 32 都行；先用 24，页内展示更像“常用置顶一行多页”的感觉
    private const val MAX_MRU = 24

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadMruCommon(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_MRU_COMMON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun recordMruCommon(context: Context, symbol: String) {
        val s = symbol.trim()
        if (s.isEmpty()) return

        val current = loadMruCommon(context).toMutableList()

        // 去重：把已有的移除，再插到最前面
        current.removeAll { it == s }
        current.add(0, s)

        // 截断长度
        while (current.size > MAX_MRU) current.removeAt(current.lastIndex)

        saveMruCommon(context, current)
    }

    private fun saveMruCommon(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_MRU_COMMON, arr.toString()).apply()
    }

    fun clearMruCommon(context: Context) {
        prefs(context).edit().remove(KEY_MRU_COMMON).apply()
    }
}
