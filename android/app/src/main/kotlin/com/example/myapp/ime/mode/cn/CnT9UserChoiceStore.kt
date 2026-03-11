package com.example.myapp.ime.mode.cn

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户选词学习权重存储。
 *
 * Key   = "拼音路径文本|候选词"，例如 "ni'hao|你好"
 * Value = 选用次数（整型，上限 cap 防溢出）
 *
 * 设计原则：
 *  - 内存中用 ConcurrentHashMap 维持运行时热路径
 *  - 通过 SharedPreferences 持久化（容量上限 MAX_ENTRIES 条，超出后按权重从低到高淘汰）
 *  - 不在主线程 I/O（写操作异步 apply）
 */
class CnT9UserChoiceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "cnt9_user_choice"
        private const val MAX_ENTRIES = 2000
        private const val MAX_COUNT = 999
        private const val BOOST_PER_COUNT = 8   // 每次选用增加的权重分
        private const val MAX_BOOST = 200       // 单词最大加分上限
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 运行时缓存：key → count
    private val cache = ConcurrentHashMap<String, Int>()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val all = prefs.all
        for ((k, v) in all) {
            if (v is Int && v > 0) {
                cache[k] = v
            }
        }
    }

    /**
     * 查询某个候选词在当前拼音路径下的加分。
     * @param pinyinKey  拼音路径串，如 "ni'hao"（由 plan.text 得来）
     * @param word       候选词汉字
     */
    fun getBoost(pinyinKey: String, word: String): Int {
        val key = buildKey(pinyinKey, word)
        val count = cache[key] ?: return 0
        return (count * BOOST_PER_COUNT).coerceAtMost(MAX_BOOST)
    }

    /**
     * 记录一次用户选词（选中后调用）。
     */
    fun recordChoice(pinyinKey: String, word: String) {
        val key = buildKey(pinyinKey, word)
        val newCount = ((cache[key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        cache[key] = newCount

        // 异步持久化
        val editor = prefs.edit()
        editor.putInt(key, newCount)

        // 容量管控：超过 MAX_ENTRIES 时，删除最低频条目
        if (cache.size > MAX_ENTRIES) {
            val toRemove = cache.entries
                .sortedBy { it.value }
                .take(cache.size - MAX_ENTRIES)
            for (entry in toRemove) {
                cache.remove(entry.key)
                editor.remove(entry.key)
            }
        }

        editor.apply()
    }

    /**
     * 清除所有学习数据（供设置界面"重置"按钮调用）。
     */
    fun clearAll() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    private fun buildKey(pinyinKey: String, word: String): String {
        return "$pinyinKey|$word"
    }
}
