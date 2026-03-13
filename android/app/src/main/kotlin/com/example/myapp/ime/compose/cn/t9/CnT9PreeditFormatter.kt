package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner
import java.util.Locale

/**
 * CN-T9 Preedit 顶部预览文字生成器。
 *
 * 生成规则（对应规则清单「Preedit 顶部预览」）：
 *  1. engineOverride 优先级最高（由 CnT9CandidateEngine 在特殊状态下注入）
 *  2. committedPrefix（已物化上屏前缀）直接拼在最前
 *  3. lockedSegs（pinyinStack 已确认音节）接在 committedPrefix 之后
 *  4. plannedSegs（planAll 推算的 rawDigits 对应拼音段）接在最后
 *  5. 段与段之间用 ' 分隔，显示拼音字母而非数字
 *  6. dict 未加载时展示 [abc] 形式的键位字母组，而非原始数字或单字母
 *
 * ── 缓存策略（P1 修复）────────────────────────────────────────────────
 *  对 (rawDigits, lockedSegs, committedPrefix, dictLoaded) 四元组做轻量缓存：
 *  - 相同输入下直接返回上次结果，避免每帧重跑 planAll()
 *  - 词库加载完成（dictLoaded 由 false→true）会触发自动重算
 *  - rawDigits / lockedSegs / committedPrefix 任意变化也会触发重算
 *  - 外部可调用 invalidate() 强制下次重算（如 session clear 后）
 *
 * 线程安全：仅在 IME 主线程调用，无需加锁。
 */
class CnT9PreeditFormatter {

    // ── 缓存字段 ──────────────────────────────────────────────────────

    private data class CacheKey(
        val rawDigits: String,
        val lockedSegs: List<String>,
        val committedPrefix: String,
        val dictLoaded: Boolean
    )

    private var lastKey: CacheKey? = null
    private var lastResult: String? = null

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 生成 preedit 文字。
     *
     * @param session        当前 ComposingSession
     * @param dict           字典引擎（用于 planAll 和 isLoaded 检测）
     * @param engineOverride 由 CnT9CandidateEngine 注入的强制覆盖文字（优先级最高）
     * @return               preedit 文字；null 表示无内容（Idle 状态）
     */
    fun format(
        session: ComposingSession,
        dict: Dictionary,
        engineOverride: String? = null
    ): String? {
        // engineOverride 优先，不走缓存（外部已确保只在需要时注入）
        val override = engineOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) return override

        val committedPrefix = session.committedPrefix.trim()
        val lockedSegs = session.pinyinStack
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val rawDigits = session.rawT9Digits

        if (committedPrefix.isEmpty() && lockedSegs.isEmpty() && rawDigits.isEmpty()) {
            // Idle：清缓存并返回 null
            invalidate()
            return null
        }

        // ── 缓存命中检查 ──────────────────────────────────────────────
        val key = CacheKey(
            rawDigits      = rawDigits,
            lockedSegs     = lockedSegs,
            committedPrefix = committedPrefix,
            dictLoaded     = dict.isLoaded
        )
        if (key == lastKey) return lastResult

        // ── 重新计算 ──────────────────────────────────────────────────
        val result = compute(
            committedPrefix = committedPrefix,
            lockedSegs      = lockedSegs,
            rawDigits       = rawDigits,
            dict            = dict
        )

        lastKey    = key
        lastResult = result
        return result
    }

    /**
     * 强制使下次 format() 重算（不使用缓存）。
     * 在 session clear / 用户选词上屏后由外部调用。
     */
    fun invalidate() {
        lastKey    = null
        lastResult = null
    }

    // ── 私有：核心计算逻辑 ────────────────────────────────────────────

    private fun compute(
        committedPrefix: String,
        lockedSegs: List<String>,
        rawDigits: String,
        dict: Dictionary
    ): String? {
        val plannedSegs: List<String> = when {
            rawDigits.isEmpty() -> emptyList()
            dict.isLoaded -> {
                CnT9SentencePlanner.planAll(
                    digits     = rawDigits,
                    manualCuts = emptySet(),   // preedit 仅做展示，不需要 manualCuts 影响
                    dict       = dict
                ).firstOrNull()
                    ?.segments
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                    ?: fallbackKeyLabels(rawDigits)   // planAll 返回空时降级
            }
            else -> fallbackKeyLabels(rawDigits)      // 词库未加载时使用键位标签
        }

        val allSegs = lockedSegs + plannedSegs

        return buildString {
            if (committedPrefix.isNotEmpty()) append(committedPrefix)
            if (allSegs.isNotEmpty()) {
                if (committedPrefix.isNotEmpty()) append("'")
                append(allSegs.joinToString("'"))
            }
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * P2 修复：词库未加载时的键位标签兜底。
     *
     * 旧实现：每个数字只取第一个字母（2→a, 3→d, 4→g…），用户看到无意义字母串。
     * 新实现：每个数字展示该键对应的全部字母，格式为 [abc]，
     *         明确传达"词库加载中，当前按键对应这些字母"的语义。
     *
     * 示例：rawDigits = "46" → listOf("[ghi]", "[mno]")
     *       拼接后 preedit 显示：[ghi]'[mno]
     */
    private fun fallbackKeyLabels(digits: String): List<String> {
        return digits.map { d ->
            val chars = T9Lookup.charsFromDigit(d)
            if (chars.isNotEmpty()) {
                "[${chars.joinToString("") { it.lowercase(Locale.ROOT) }}]"
            } else {
                d.toString()
            }
        }
    }
}
