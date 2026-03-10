package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.cn.CnT9Handler
import java.util.Locale

/**
 * 把 CN-T9 session 状态格式化为顶部预览拼音字符串。
 *
 * 优先级：
 *   1. engine 主动推送的 composingPreviewOverride（如音节栏点选后的锁定文本）
 *   2. pinyinStack（已锁定音节）+ SentencePlanner 对 rawDigits 的最优解码路径
 *   3. 字典未加载时降级为 rawDigits 每位取首字母
 *   4. 仅有 committedPrefix 时只显示 committedPrefix
 */
object CnT9PreeditFormatter {

    /**
     * 生成供顶部预览显示的拼音字符串。
     *
     * @param session          当前 ComposingSession
     * @param dict             字典引擎（用于 SentencePlanner）
     * @param engineOverride   engine 主动推送的预览文本（优先级最高）
     */
    fun format(
        session: ComposingSession,
        dict: Dictionary,
        engineOverride: String? = null
    ): String? {
        // 1. engine override 优先
        val override = engineOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) return override

        val committedPrefix = session.committedPrefix.trim()
        val lockedSegs = session.pinyinStack
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val rawDigits = session.rawT9Digits

        // 2. 无任何输入
        if (committedPrefix.isEmpty() && lockedSegs.isEmpty() && rawDigits.isEmpty()) return null

        // 3. 解码 rawDigits → 最优音节路径
        val plannedSegs: List<String> = when {
            rawDigits.isEmpty() -> emptyList()
            dict.isLoaded -> {
                CnT9Handler.SentencePlanner.planAll(
                    digits = rawDigits,
                    manualCuts = session.t9ManualCuts,
                    dict = dict
                ).firstOrNull()
                    ?.segments
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                    ?: fallbackLetters(rawDigits)
            }
            else -> fallbackLetters(rawDigits)
        }

        val allSegs = lockedSegs + plannedSegs

        return buildString {
            if (committedPrefix.isNotEmpty()) append(committedPrefix)
            if (allSegs.isNotEmpty()) {
                append(allSegs.joinToString("'"))
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** 字典未加载时降级：每个数字取 T9 映射的首个字母 */
    private fun fallbackLetters(digits: String): List<String> {
        return digits.map { d ->
            T9Lookup.charsFromDigit(d)
                .firstOrNull()
                ?.lowercase(Locale.ROOT)
                ?: d.toString()
        }
    }
}
