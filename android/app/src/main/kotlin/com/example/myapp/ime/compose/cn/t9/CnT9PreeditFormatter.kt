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
 *  2. committedPrefix（已物化上屏前缀汉字）直接拼在最前，样式 COMMITTED_PREFIX
 *  3. lockedSegs（pinyinStack 中已锁定段）接在 committedPrefix 之后，样式 LOCKED
 *  4. focusedSegs（当前焦点段）在 lockedSegs 内或之后，样式 FOCUSED（覆盖 LOCKED）
 *  5. plannedSegs（planAll 推算的 rawDigits 对应拼音段）接在最后，样式 NORMAL
 *  6. 拼音段与段之间用 ' 分隔，不显示数字
 *  7. dict 未加载时展示 [abc] 格式键位字母组（P2 修复），样式 FALLBACK
 *
 * ── 缓存策略（P1 修复）────────────────────────────────────────────────
 *  对 (rawDigits, lockedSegs, committedPrefix, focusedIndex, dictLoaded) 五元组做轻量缓存：
 *  - 相同输入状态下直接复用结果，避免每帧重跑 planAll()
 *  - 任意字段变化（含 dictLoaded: false→true）触发自动重算
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
        val focusedIndex: Int,          // R-P04 新增：焦点段下标（-1 表示无焦点）
        val dictLoaded: Boolean
    )

    private var lastKey: CacheKey? = null
    private var lastResult: PreeditDisplay? = null

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 生成带段样式的 PreeditDisplay。
     *
     * @param session        当前 ComposingSession
     * @param dict           字典引擎（用于 planAll 和 isLoaded 检测）
     * @param engineOverride 由 CnT9CandidateEngine 注入的强制覆盖纯文本（优先级最高）
     * @param focusedSegmentIndex  当前焦点段下标（来自 CnT9SidebarState，-1 表示无焦点）
     * @return               PreeditDisplay；plainText 为空时表示 Idle 状态
     */
    fun format(
        session: ComposingSession,
        dict: Dictionary,
        engineOverride: String? = null,
        focusedSegmentIndex: Int = -1
    ): PreeditDisplay {
        // engineOverride：优先级最高，包装为单段 NORMAL 展示
        val override = engineOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            return PreeditDisplay(
                plainText = override,
                segments = listOf(PreeditSegment(override, PreeditSegment.Style.NORMAL))
            )
        }

        val committedPrefix = session.committedPrefix.trim()
        val pinyinStack = session.pinyinStack
        val lockedSegs = pinyinStack
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val rawDigits = session.rawT9Digits

        if (committedPrefix.isEmpty() && lockedSegs.isEmpty() && rawDigits.isEmpty()) {
            invalidate()
            return PreeditDisplay.EMPTY
        }

        val key = CacheKey(
            rawDigits       = rawDigits,
            lockedSegs      = lockedSegs,
            committedPrefix = committedPrefix,
            focusedIndex    = focusedSegmentIndex,
            dictLoaded      = dict.isLoaded
        )
        if (key == lastKey && lastResult != null) return lastResult!!

        val result = compute(
            committedPrefix      = committedPrefix,
            lockedSegs           = lockedSegs,
            rawDigits            = rawDigits,
            dict                 = dict,
            session              = session,
            focusedSegmentIndex  = focusedSegmentIndex
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
    ): String? {
        val d = format(session, dict, engineOverride, focusedSegmentIndex = -1)
        return d.plainText.takeIf { it.isNotEmpty() }
    }

    /**
     * 强制使下次 format() 重算（不使用缓存）。
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
        focusedSegmentIndex: Int
    ): PreeditDisplay {
        // ── 1. 规划 rawDigits → plannedSegs ──────────────────────────
        val isFallback: Boolean
        val plannedSegs: List<String>

        when {
            rawDigits.isEmpty() -> {
                isFallback = false
                plannedSegs = emptyList()
            }
            dict.isLoaded -> {
                isFallback = false
                plannedSegs = CnT9SentencePlanner.planAll(
                    digits     = rawDigits,
                    manualCuts = session.t9ManualCuts,
                    dict       = dict
                ).firstOrNull()
                    ?.segments
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                    ?: fallbackKeyLabels(rawDigits).also { /* isFallback handled below */ }
                    .also { /* no-op, already assigned */ }
                    .let { it }   // compiler needs explicit branch
            }
            else -> {
                isFallback = true
                plannedSegs = fallbackKeyLabels(rawDigits)
            }
        }

        // ── 2. 构建各段列表（含样式）──────────────────────────────────
        val segments = mutableListOf<PreeditSegment>()

        // committedPrefix（已上屏汉字前缀）
        if (committedPrefix.isNotEmpty()) {
            segments.add(PreeditSegment(committedPrefix, PreeditSegment.Style.COMMITTED_PREFIX))
        }

        // lockedSegs（pinyinStack 中锁定/物化的音节段）
        lockedSegs.forEachIndexed { idx, seg ->
            val style = when {
                idx == focusedSegmentIndex -> PreeditSegment.Style.FOCUSED   // 焦点覆盖锁定
                else                       -> PreeditSegment.Style.LOCKED
            }
            segments.add(PreeditSegment(seg, style))
        }

        // plannedSegs（rawDigits 解码出的待输入音节）
        if (isFallback) {
            plannedSegs.forEach { seg ->
                segments.add(PreeditSegment(seg, PreeditSegment.Style.FALLBACK))
            }
        } else {
            plannedSegs.forEachIndexed { idx, seg ->
                val globalIdx = lockedSegs.size + idx
                val style = if (globalIdx == focusedSegmentIndex)
                    PreeditSegment.Style.FOCUSED
                else
                    PreeditSegment.Style.NORMAL
                segments.add(PreeditSegment(seg, style))
            }
        }

        if (segments.isEmpty()) return PreeditDisplay.EMPTY

        // ── 3. 拼接 plainText（committedPrefix 直接连接，拼音段间用 '）──
        val pinyinParts = segments
            .filter { it.style != PreeditSegment.Style.COMMITTED_PREFIX }
            .map { it.text }
        val pinyinStr = pinyinParts.joinToString("'")
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
