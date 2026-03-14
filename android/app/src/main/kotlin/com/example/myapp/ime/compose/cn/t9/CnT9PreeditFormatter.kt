package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner
import java.util.Locale

/**
 * CN-T9 Preedit 顶部预览文字生成器（带段样式）。
 *
 * 生成规则（对应规则清单「Preedit 顶部预览」）：
 *  1. engineOverride 优先级最高（由 CnT9CandidateEngine 在特殊状态下注入）
 *     ── 修复（问题1）：engineOverride 仅在 pinyinStack 与 committedPrefix 均为空时
 *        才整段替换；否则作为末尾 NORMAL 段追加，不破坏锁定/焦点样式。
 *  2. committedPrefix（已物化上屏前缀汉字）直接拼在最前，样式 COMMITTED_PREFIX
 *  3. lockedSegs（pinyinStack 中锁定/物化段）接在 committedPrefix 之后，样式 LOCKED
 *  4. 当前焦点段（focusedSegmentIndex）样式覆盖为 FOCUSED（含下划线高亮）
 *  5. plannedSegs（planAll 推算的 rawDigits 对应拼音段）接在最后，样式 NORMAL
 *  6. 拼音段与段之间用 ' 分隔，不显示数字
 *  7. dict 未加载时展示 [abc] 格式键位字母组（P2 修复），样式 FALLBACK
 *
 * ── 缓存策略（P1 修复）────────────────────────────────────────────────
 *  对 (rawDigits, lockedSegs, committedPrefix, focusedIndex, dictLoaded,
 *      engineOverride) 六元组做轻量缓存：
 *  - 相同输入状态下直接复用结果，避免每帧重跑 planAll()
 *  - 任意字段变化（含 dictLoaded: false→true、焦点变化、override 变化）触发自动重算
 *  - 外部调用 invalidate() 强制下次重算（Idle/选词上屏后）
 *
 * 线程安全：仅在 IME 主线程调用，无需加锁。
 */
class CnT9PreeditFormatter {

    // ── 缓存 ──────────────────────────────────────────────────────────

    private data class CacheKey(
        val rawDigits: String,
        val lockedSegs: List<String>,
        val committedPrefix: String,
        val focusedIndex: Int,
        val dictLoaded: Boolean,
        val engineOverride: String?     // 问题1修复：override 纳入缓存 key
    )

    private var lastKey: CacheKey? = null
    private var lastResult: PreeditDisplay? = null

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 生成带段样式的 PreeditDisplay。
     *
     * @param session               当前 ComposingSession
     * @param dict                  字典引擎（用于 planAll 和 isLoaded 检测）
     * @param engineOverride        由 CnT9CandidateEngine 注入的强制覆盖纯文本（优先级最高）
     * @param focusedSegmentIndex   当前焦点段下标（-1 = 无焦点），来自 CnT9InputEngine
     * @return                      PreeditDisplay；plainText 为空时表示 Idle 状态
     */
    fun format(
        session: ComposingSession,
        dict: Dictionary,
        engineOverride: String? = null,
        focusedSegmentIndex: Int = -1
    ): PreeditDisplay {
        val committedPrefix = session.committedPrefix.trim()
        val lockedSegs = session.pinyinStack
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val rawDigits = session.rawT9Digits
        val override = engineOverride?.trim()?.takeIf { it.isNotEmpty() }

        // 问题1修复：仅当 pinyinStack 与 committedPrefix 均为空时，
        // engineOverride 才作为完整替换（单段 NORMAL），否则走完整分段流程。
        if (override != null && committedPrefix.isEmpty() && lockedSegs.isEmpty() && rawDigits.isEmpty()) {
            return PreeditDisplay(
                plainText = override,
                segments  = listOf(PreeditSegment(override, PreeditSegment.Style.NORMAL))
            )
        }

        if (committedPrefix.isEmpty() && lockedSegs.isEmpty() && rawDigits.isEmpty() && override == null) {
            invalidate()
            return PreeditDisplay.EMPTY
        }

        val key = CacheKey(
            rawDigits       = rawDigits,
            lockedSegs      = lockedSegs,
            committedPrefix = committedPrefix,
            focusedIndex    = focusedSegmentIndex,
            dictLoaded      = dict.isLoaded,
            engineOverride  = override
        )
        if (key == lastKey && lastResult != null) return lastResult!!

        val result = compute(
            committedPrefix     = committedPrefix,
            lockedSegs          = lockedSegs,
            rawDigits           = rawDigits,
            dict                = dict,
            session             = session,
            focusedSegmentIndex = focusedSegmentIndex,
            engineOverride      = override
        )
        lastKey    = key
        lastResult = result
        return result
    }

    /**
     * 兼容旧调用方：仅返回纯文字。
     * 新代码应调用 format(…) 获取 PreeditDisplay 以取得段样式。
     */
    fun formatPlain(
        session: ComposingSession,
        dict: Dictionary,
        engineOverride: String? = null
    ): String? = format(session, dict, engineOverride, -1)
        .plainText.takeIf { it.isNotEmpty() }

    /**
     * 强制使下次 format() 重算（不使用缓存）。
     * 在 session clear / 用户选词上屏 / Idle 时由外部或 format() 自身调用。
     */
    fun invalidate() {
        lastKey    = null
        lastResult = null
    }

    // ── 私有：核心计算 ────────────────────────────────────────────────

    private fun compute(
        committedPrefix: String,
        lockedSegs: List<String>,
        rawDigits: String,
        dict: Dictionary,
        session: ComposingSession,
        focusedSegmentIndex: Int,
        engineOverride: String?         // 问题1修复：传入 override 供末尾追加
    ): PreeditDisplay {

        // ── 1. 规划 rawDigits → plannedSegs ──────────────────────────
        val isFallback: Boolean
        val plannedSegs: List<String>

        when {
            rawDigits.isEmpty() -> {
                isFallback  = false
                plannedSegs = emptyList()
            }
            dict.isLoaded -> {
                isFallback  = false
                val decoded = CnT9SentencePlanner.planAll(
                    digits     = rawDigits,
                    manualCuts = session.t9ManualCuts,
                    dict       = dict
                ).firstOrNull()
                    ?.segments
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                plannedSegs = decoded ?: fallbackKeyLabels(rawDigits)
            }
            else -> {
                isFallback  = true
                plannedSegs = fallbackKeyLabels(rawDigits)
            }
        }

        // ── 2. 构建各段列表（含样式）──────────────────────────────────
        val segments = mutableListOf<PreeditSegment>()

        // committedPrefix（已上屏汉字前缀）
        if (committedPrefix.isNotEmpty()) {
            segments.add(PreeditSegment(committedPrefix, PreeditSegment.Style.COMMITTED_PREFIX))
        }

        // lockedSegs（pinyinStack 中已物化的音节段）
        lockedSegs.forEachIndexed { idx, seg ->
            val style = if (idx == focusedSegmentIndex)
                PreeditSegment.Style.FOCUSED
            else
                PreeditSegment.Style.LOCKED
            segments.add(PreeditSegment(seg, style))
        }

        // plannedSegs（rawDigits 解码出的待输入音节）
        plannedSegs.forEachIndexed { idx, seg ->
            val globalIdx = lockedSegs.size + idx
            val style = when {
                isFallback                       -> PreeditSegment.Style.FALLBACK
                globalIdx == focusedSegmentIndex -> PreeditSegment.Style.FOCUSED
                else                             -> PreeditSegment.Style.NORMAL
            }
            segments.add(PreeditSegment(seg, style))
        }

        // 问题1修复：engineOverride 不为空时，作为末尾 NORMAL 段追加
        // （仅在有 pinyinStack 或 committedPrefix 时走此分支，否则已在 format() 入口提前返回）
        if (engineOverride != null) {
            segments.add(PreeditSegment(engineOverride, PreeditSegment.Style.NORMAL))
        }

        if (segments.isEmpty()) return PreeditDisplay.EMPTY

        // ── 3. 拼接 plainText ─────────────────────────────────────────
        //    committedPrefix 直接连接（无分隔符），拼音段间用 '
        val pinyinStr = segments
            .filter { it.style != PreeditSegment.Style.COMMITTED_PREFIX }
            .joinToString("'") { it.text }
        val plainText = committedPrefix + pinyinStr

        return PreeditDisplay(
            plainText  = plainText,
            segments   = segments,
            isFallback = isFallback
        )
    }

    /**
     * P2 修复：词库未加载时的键位标签兜底。
     * 每个数字展示该键对应全部字母，格式 [abc]。
     * 示例：rawDigits = "46" → ["[ghi]", "[mno]"]
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
