package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import kotlin.math.min

/**
 * 首选上屏置信度模型。
 *
 * 职责：
 *  根据当前 session 状态、首选候选属性、用户学习权重与上下文偏置，
 *  计算一个 0–100 的置信度分数；分数 ≥ AUTO_COMMIT 阈值时自动上屏。
 *
 * 设计原则：
 *  - 纯计算，无副作用
 *  - 依赖注入所有外部状态（session / dictEngine / stores）
 *  - 阈值与权重集中在 companion object，便于调参
 */
object CnT9ConfidenceModel {

    const val AUTO_COMMIT_THRESHOLD = 60

    /**
     * 计算首选候选的上屏置信度（0–100）。
     *
     * @param preferredIndex  候选在当前列表中的下标（0 = 真正首选）
     * @param cand            首选候选
     * @param candidateCount  当前候选总数
     * @param session         当前 composing session
     * @param dictEngine      字典引擎（用于构造 autoPlans）
     * @param isRawCommitMode 是否处于 raw commit 模式
     * @param userChoiceStore 用户学习存储（可空）
     * @param contextWindow   上下文窗口（可空）
     */
    fun compute(
        preferredIndex: Int,
        cand: Candidate,
        candidateCount: Int,
        session: ComposingSession,
        dictEngine: Dictionary,
        isRawCommitMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?
    ): Int {
        if (!session.isComposing()) return 0
        if (preferredIndex > 0) return 100
        if (candidateCount == 1) return 100
        if (isRawCommitMode) return 100
        if (session.rawT9Digits.isEmpty() && session.pinyinStack.isNotEmpty()) return 90

        val rawLen = session.rawT9Digits.length
        if (rawLen == 1 && session.pinyinStack.isEmpty()) return 20

        var score = 0

        val preview = buildNormalizedPreview(session, dictEngine)
        val candPreview = CnT9PinyinSplitter.normalizeCandidate(cand.pinyin, cand.input)
        val expectedSyllables = session.pinyinStack.size +
            CnT9PinyinSplitter.estimateDigitSyllables(session.rawT9Digits)
        val consumeSyllables = CnT9CommitHelper.resolveConsumeSyllables(cand)

        if (preview != null && candPreview.isNotEmpty()) {
            score += when {
                candPreview == preview          -> 40
                preview.startsWith(candPreview) -> 25
                else                            -> 5
            }
        }

        val minRequired = min(2, expectedSyllables)
        score += when {
            consumeSyllables >= minRequired && cand.word.length > 1 -> 20
            cand.word.length == 1 && expectedSyllables == 1         -> 15
            else                                                     -> 0
        }

        if (session.pinyinStack.isNotEmpty()) score += 10
        if (rawLen >= 4) score += 10

        val userBoost = userChoiceStore?.getBoost(candPreview, cand.word) ?: 0
        if (userBoost > 0) score += minOf(userBoost / 10, 10)

        if ((contextWindow?.getContextBoost(cand.word) ?: 0) > 0) score += 5

        return score.coerceIn(0, 100)
    }

    fun shouldAutoCommit(
        preferredIndex: Int,
        cand: Candidate,
        candidateCount: Int,
        session: ComposingSession,
        dictEngine: Dictionary,
        isRawCommitMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?
    ): Boolean = compute(
        preferredIndex, cand, candidateCount, session, dictEngine,
        isRawCommitMode, userChoiceStore, contextWindow
    ) >= AUTO_COMMIT_THRESHOLD

    // ── 内部辅助 ──────────────────────────────────────────────────

    private fun normalizeSegment(seg: String): String =
        seg.trim().lowercase().replace("'", "").replace("ü", "v")
            .filter { it in 'a'..'z' || it == 'v' }

    private fun buildNormalizedPreview(
        session: ComposingSession,
        dictEngine: Dictionary
    ): String? {
        val stackSegs = session.pinyinStack
            .map { normalizeSegment(it) }
            .filter { it.isNotEmpty() }

        val rawDigits = session.rawT9Digits
        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else emptyList()

        val bestSegments = when {
            stackSegs.isEmpty() && autoPlans.isEmpty() -> return null
            autoPlans.isEmpty() -> stackSegs
            else -> stackSegs + autoPlans.first().segments
                .map { normalizeSegment(it) }
                .filter { it.isNotEmpty() }
        }

        return bestSegments.joinToString("").takeIf { it.isNotEmpty() }
    }
}
