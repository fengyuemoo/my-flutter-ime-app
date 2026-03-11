package com.example.myapp.ime.mode.cn

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户选词学习权重存储（含时间衰减 + streak）。
 *
 * 存储 Key  = "拼音路径|候选词"，如 "ni'hao|你好"
 * 存储 Value = "$count:$lastTimestamp:$streak"（':'分隔的紧凑格式）
 *
 * 衰减公式：
 *   decay = when (daysSince) {
 *     < 30  → 1.0
 *     < 90  → 0.5
 *     < 180 → 0.25
 *     else  → 0.0  （写回 0，下次自动从存储删除）
 *   }
 *   boost = min(count × BOOST_PER_COUNT × decay × streakMultiplier, MAX_BOOST)
 *
 * streak 规则：
 *   - 连续 2 次在同一 pinyinKey 下选同一 word → streak = 2
 *   - 中途选了别的词 → streak 重置为 1
 *   - streakMultiplier = 1.0 + min(streak - 1, 4) × 0.1  （最多 1.4×）
 */
class CnT9UserChoiceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "cnt9_user_choice_v2"
        private const val MAX_ENTRIES = 2000
        private const val MAX_COUNT = 999
        private const val BOOST_PER_COUNT = 8
        private const val MAX_BOOST = 200

        // 衰减阈值（毫秒）
        private val HALF_DECAY_MS = 30L * 24 * 3600 * 1000
        private val QUARTER_DECAY_MS = 90L * 24 * 3600 * 1000
        private val ZERO_DECAY_MS = 180L * 24 * 3600 * 1000
    }

    private data class Entry(
        val count: Int,
        val lastTimestamp: Long,
        val streak: Int
    ) {
        fun serialize(): String = "$count:$lastTimestamp:$streak"

        companion object {
            fun deserialize(s: String): Entry? {
                val parts = s.split(":")
                if (parts.size < 3) return null
                return try {
                    Entry(
                        count = parts[0].toInt(),
                        lastTimestamp = parts[1].toLong(),
                        streak = parts[2].toInt()
                    )
                } catch (_: NumberFormatException) { null }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = ConcurrentHashMap<String, Entry>()

    // 记录"上一次在同一 pinyinKey 下选的 word"，用于 streak 判断
    @Volatile private var lastPinyinKey: String = ""
    @Volatile private var lastWord: String = ""

    init { loadFromPrefs() }

    private fun loadFromPrefs() {
        val now = System.currentTimeMillis()
        for ((k, v) in prefs.all) {
            if (v !is String) continue
            val entry = Entry.deserialize(v) ?: continue
            // 过期条目直接丢弃，不加载进内存
            if (decayFactor(now - entry.lastTimestamp) > 0f) {
                cache[k] = entry
            }
        }
    }

    private fun decayFactor(ageMs: Long): Float = when {
        ageMs < HALF_DECAY_MS    -> 1.0f
        ageMs < QUARTER_DECAY_MS -> 0.5f
        ageMs < ZERO_DECAY_MS    -> 0.25f
        else                      -> 0f
    }

    private fun streakMultiplier(streak: Int): Float =
        1.0f + minOf(streak - 1, 4) * 0.1f

    /**
     * 查询某候选词在当前拼音路径下的加分（含衰减 + streak 加乘）。
     */
    fun getBoost(pinyinKey: String, word: String): Int {
        val key = buildKey(pinyinKey, word)
        val entry = cache[key] ?: return 0
        val now = System.currentTimeMillis()
        val decay = decayFactor(now - entry.lastTimestamp)
        if (decay == 0f) return 0
        val streak = streakMultiplier(entry.streak)
        return (entry.count * BOOST_PER_COUNT * decay * streak)
            .toInt()
            .coerceAtMost(MAX_BOOST)
    }

    /**
     * 记录一次用户选词。
     */
    fun recordChoice(pinyinKey: String, word: String) {
        val key = buildKey(pinyinKey, word)
        val now = System.currentTimeMillis()

        // Streak 判断
        val newStreak = if (lastPinyinKey == pinyinKey && lastWord == word) {
            (cache[key]?.streak ?: 1) + 1
        } else {
            1
        }
        lastPinyinKey = pinyinKey
        lastWord = word

        val oldCount = cache[key]?.count ?: 0
        val newCount = (oldCount + 1).coerceAtMost(MAX_COUNT)

        val newEntry = Entry(count = newCount, lastTimestamp = now, streak = newStreak)
        cache[key] = newEntry

        val editor = prefs.edit()
        editor.putString(key, newEntry.serialize())

        // 容量管控：淘汰最老 + 最低频
        if (cache.size > MAX_ENTRIES) {
            val toRemove = cache.entries
                .sortedWith(compareBy({ it.value.lastTimestamp }, { it.value.count }))
                .take(cache.size - MAX_ENTRIES)
            for (e in toRemove) {
                cache.remove(e.key)
                editor.remove(e.key)
            }
        }

        editor.apply()
    }

    /**
     * 清除所有学习数据（供设置界面"重置"按钮调用）。
     */
    fun clearAll() {
        cache.clear()
        lastPinyinKey = ""
        lastWord = ""
        prefs.edit().clear().apply()
    }

    private fun buildKey(pinyinKey: String, word: String): String = "$pinyinKey|$word"
}
