package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.model.Candidate

/**
 * CN-T9 标点符号快捷候选生成器。
 *
 * 对应规则清单：
 *  - 「标点与全半角」：中文模式下标点常用全角（可设开关）
 *  - 「候选栏可提供 。，！？；：快捷候选」
 *
 * 设计原则：
 *  - 与候选列表解耦：调用方决定何时注入标点候选（如 composing 为空时，或特定触发键）
 *  - 全/半角由外部 isFullWidth 开关控制，默认全角
 *  - 标点候选使用特殊 input = PUNCT_INPUT_MARKER 以便调用方识别
 *
 * 使用场景：
 *  1. 用户在中文模式下输入状态为空（S0/Idle）时，候选栏提供常用标点快捷入口
 *  2. 用户长按某数字键弹出标点面板（调用方处理长按逻辑，本类只负责数据）
 */
object CnT9PunctuationCandidates {

    /** 标点候选的特殊 input 标记，供调用方识别并跳过拼音匹配逻辑。*/
    const val PUNCT_INPUT_MARKER = "__punct__"

    // ── 全角标点表 ─────────────────────────────────────────────────

    /** 首行常用标点（句末 + 常用符号），优先展示。*/
    private val PRIMARY_FULL_WIDTH = listOf(
        "。", "，", "！", "？", "；", "：",
        "、", "…", "—", "～"
    )

    /** 引号与括号。*/
    private val QUOTE_FULL_WIDTH = listOf(
        "\u201c", "\u201d",   // "  "  左右双引号
        "\u2018", "\u2019",   // '  '  左右单引号
        "《", "》", "〈", "〉",
        "【", "】", "（", "）"
    )

    /** 半角对应表（全角 → 半角）。*/
    private val FULL_TO_HALF = mapOf(
        "。" to ".", "，" to ",", "！" to "!", "？" to "?",
        "；" to ";", "：" to ":", "、" to ",", "…" to "...",
        "—" to "-", "～" to "~",
        "\u201c" to "\"", "\u201d" to "\"",
        "\u2018" to "'", "\u2019" to "'",
        "《" to "<", "》" to ">", "〈" to "<", "〉" to ">",
        "【" to "[", "】" to "]", "（" to "(", "）" to ")"
    )

    // ── 公开 API ──────────────────────────────────────────────────

    /**
     * 获取首行常用标点候选列表。
     *
     * @param isFullWidth  true = 全角（默认）；false = 半角
     * @return             标点候选列表，每项 input = PUNCT_INPUT_MARKER
     */
    fun getPrimaryPunctuations(isFullWidth: Boolean = true): List<Candidate> {
        return PRIMARY_FULL_WIDTH.map { full ->
            val word = if (isFullWidth) full else FULL_TO_HALF[full] ?: full
            buildPunctCandidate(word)
        }
    }

    /**
     * 获取引号/括号标点候选列表。
     *
     * @param isFullWidth  true = 全角（默认）；false = 半角
     */
    fun getQuotePunctuations(isFullWidth: Boolean = true): List<Candidate> {
        return QUOTE_FULL_WIDTH.map { full ->
            val word = if (isFullWidth) full else FULL_TO_HALF[full] ?: full
            buildPunctCandidate(word)
        }
    }

    /**
     * 获取全部标点候选（首行 + 引号括号）。
     *
     * @param isFullWidth  true = 全角（默认）；false = 半角
     */
    fun getAllPunctuations(isFullWidth: Boolean = true): List<Candidate> {
        return getPrimaryPunctuations(isFullWidth) + getQuotePunctuations(isFullWidth)
    }

    /**
     * 判断一个候选是否是标点候选（供调用方跳过拼音匹配）。
     */
    fun isPunctCandidate(cand: Candidate): Boolean =
        cand.input == PUNCT_INPUT_MARKER

    /**
     * 将标点候选注入到候选列表末尾（Idle 状态快捷标点入口）。
     * 只注入首行常用标点，不占用太多候选位。
     *
     * @param candidates   当前候选列表（会在末尾追加）
     * @param isFullWidth  全/半角开关
     */
    fun injectIdlePunctuations(
        candidates: ArrayList<Candidate>,
        isFullWidth: Boolean = true
    ) {
        val puncts = getPrimaryPunctuations(isFullWidth)
        candidates.addAll(puncts)
    }

    // ── 私有辅助 ──────────────────────────────────────────────────

    private fun buildPunctCandidate(word: String): Candidate = Candidate(
        word = word,
        input = PUNCT_INPUT_MARKER,
        priority = 0,
        matchedLength = 0,
        pinyinCount = 0,
        pinyin = null,
        syllables = 0,
        acronym = null
    )
}
