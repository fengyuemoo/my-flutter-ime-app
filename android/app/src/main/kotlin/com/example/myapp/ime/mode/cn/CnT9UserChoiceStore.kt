package com.example.myapp.ime.mode.cn

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户选词学习权重存储（含时间衰减 + streak + R-L04 退词惩罚）。
 *
 * 存储 Key  = "拼音路径|候选词"，如 "ni'hao|你好"
 * 存储 Value = "$count:$lastTimestamp:$streak"（':'分隔的紧凑格式）
 *
 * 衰减公式：
 *   decay = when (daysSince) {
 *     < 30  → 1.0
 *     < 90  → 0.5
 *     < 180 → 0.25
 *     else  → 0.0
 *   }
 *   boost = min(count × BOOST_PER_COUNT × decay × streakMultiplier, MAX_BOOST)
 *
 * streak 规则：
 *   - 连续 2 次在同一 pinyinKey 下选同一 word → streak = 2
 *   - 中途选了别的词 → streak 重置为 1
 *   - streakMultiplier = 1.0 + min(streak - 1, 4) × 0.1（最多 1.4×）
 *
 * ── R-L04：退词惩罚（误触保护）──────────────────────────────────────
 *   当用户选词后立即退格（在 UNDO_WINDOW_MS 内调用 penalizeChoice()），
 *   说明这次选词可能是误触，对该词施加惩罚：
 *     count -= PENALTY_PER_UNDO（最低降为 0，不删除条目保留时间戳）
 *     streak 重置为 1
 *   惩罚窗口：选词后 2 秒内退格视为撤销。
 */
class CnT9UserChoiceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "cnt9_user_choice_v2"
        private const val MAX_ENTRIES = 2000
        private const val MAX_COUNT = 999
        private const val BOOST_PER_COUNT = 8
        private const val MAX_BOOST = 200

        // 衰减阈值（毫秒）
        private val HALF_DECAY_MS    = 30L * 24 * 3600 * 1000
        private val QUARTER_DECAY_MS = 90L * 24 * 3600 * 1000
        private val ZERO_DECAY_MS    = 180L * 24 * 3600 * 1000

        // R-L04：退词惩罚参数
        /** 选词后多少毫秒内退格，视为撤销 */
        private const val UNDO_WINDOW_MS = 2000L
        /** 每次撤销降低的 count 值 */
        private const val PENALTY_PER_UNDO = 2
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
                        count         = parts[0].toInt(),
                        lastTimestamp = parts[1].toLong(),
                        streak        = parts[2].toInt()
                    )
                } catch (_: NumberFormatException) { null }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile private var lastPinyinKey: String = ""
    @Volatile private var lastWord: String = ""

    // R-L04：记录最后一次选词的时间戳和 key，供 penalizeChoice() 判断时间窗口
    @Volatile private var lastChoiceTimestamp: Long = 0L
    @Volatile private var lastChoiceStoreKey: String = ""

    init { loadFromPrefs() }

    private fun loadFromPrefs() {
        val now = System.currentTimeMillis()
        for ((k, v) in prefs.all) {
            if (v !is String) continue
            val entry = Entry.deserialize(v) ?: continue
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

        val newStreak = if (lastPinyinKey == pinyinKey && lastWord == word) {
            (cache[key]?.streak ?: 1) + 1
        } else {
            1
        }
        lastPinyinKey = pinyinKey
        lastWord = word

        // R-L04：记录本次选词信息，供 penalizeChoice() 判断
        lastChoiceTimestamp = now
        lastChoiceStoreKey  = key

        val oldCount = cache[key]?.count ?: 0
        val newCount = (oldCount + 1).coerceAtMost(MAX_COUNT)

        val newEntry = Entry(count = newCount, lastTimestamp = now, streak = newStreak)
        cache[key] = newEntry

        val editor = prefs.edit()
        editor.putString(key, newEntry.serialize())

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
     * R-L04：退词惩罚。
     *
     * 在用户选词后立即退格时调用（由 CnT9CandidateEngine.handleBackspace 在
     * 上屏后第一次退格时触发）。若距上次 recordChoice() 在 UNDO_WINDOW_MS 内，
     * 则对最后一次选词施加 count 惩罚，streak 重置为 1。
     */
    fun penalizeLastChoiceIfRecent() {
        val now = System.currentTimeMillis()
        if (now - lastChoiceTimestamp > UNDO_WINDOW_MS) return
        val key = lastChoiceStoreKey.takeIf { it.isNotEmpty() } ?: return

        val entry = cache[key] ?: return
        val penalizedCount = (entry.count - PENALTY_PER_UNDO).coerceAtLeast(0)
        val penalized = entry.copy(count = penalizedCount, streak = 1)
        cache[key] = penalized
