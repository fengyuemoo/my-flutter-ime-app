package com.example.myapp.ime.mode.cn

import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.ImeModeHandler
import com.example.myapp.ime.ui.ImeUi

/**
 * CN-T9 候选引擎（协调器）。
 *
 * 职责：
 *  1. 驱动 CnT9Handler 生成候选列表（updateCandidates）
 *  2. 处理用户选词提交（commitCandidateAt / commitCandidate）
 *  3. 处理空格键与 Enter 首选上屏
 *  4. 管理 UI 展开/收起、单字过滤模式
 *
 * 不包含：
 *  - 拼音切分工具函数    → CnT9PinyinSplitter
 *  - 音节物化 & 消费计算  → CnT9CommitHelper
 *  - 首选置信度模型      → CnT9ConfidenceModel
 */
class CnT9CandidateEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val dictEngine: Dictionary,
    private val session: ComposingSession,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val isRawCommitMode: () -> Boolean,
    private val userChoiceStore: CnT9UserChoiceStore? = null,
    private val contextWindow: CnT9ContextWindow? = null,
    private val focusedSegmentIndexProvider: () -> Int = { -1 }
) {
    private var isExpanded: Boolean = false
    private var isSingleCharMode: Boolean = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()
    private var composingPreviewOverride: String? = null
    private var enterCommitTextOverride: String? = null

    fun getComposingPreviewOverride(): String? = composingPreviewOverride
    fun getEnterCommitTextOverride(): String? = enterCommitTextOverride

    private fun isDebuggable(): Boolean =
        (ui.rootView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun resetUiSelectionToTop() = ui.resetSelectedCandidateIndex()

    private fun preferredIndex(): Int? {
        if (currentCandidates.isEmpty()) return null
        val sel = ui.getSelectedCandidateIndex()
        return if (sel in currentCandidates.indices) sel else 0
    }

    private fun preferredCandidate(): Candidate? =
        preferredIndex()?.let { currentCandidates.getOrNull(it) }

    // ── UI 状态 ────────────────────────────────────────────────────

    fun syncFilterButton() = ui.setFilterButton(isSingleCharMode)

    fun toggleSingleCharMode() {
        isSingleCharMode = !isSingleCharMode
        syncFilterButton()
        resetUiSelectionToTop()
        updateCandidates()
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
        ui.setExpanded(isExpanded, session.isComposing())
    }

    // ── 候选更新 ───────────────────────────────────────────────────

    fun updateCandidates() {
        syncFilterButton()
        currentCandidates.clear()

        if (!session.isComposing()) {
            composingPreviewOverride = null
            enterCommitTextOverride = null
            resetUiSelectionToTop()
            if (isExpanded) isExpanded = false
            ui.showIdleState()
            ui.setExpanded(false, isComposing = false)
            keyboardController.updateSidebar(emptyList())
            return
        }

        val out: ImeModeHandler.Output = CnT9Handler.build(
            session = session,
            dictEngine = dictEngine,
            singleCharMode = isSingleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow = contextWindow,
            focusedSegmentIndex = focusedSegmentIndexProvider()
        )

        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride = out.enterCommitText
        currentCandidates = ArrayList(out.candidates)
        resetUiSelectionToTop()

        syncFilterButton()
        ui.showComposingState(isExpanded = isExpanded)
        ui.setExpanded(isExpanded, isComposing = true)
        keyboardController.updateSidebar(out.pinyinSidebar)
        ui.setCandidates(currentCandidates)

        // 中英混输：仅在纯数字输入（未锁定任何音节）时追加英文直出候选
        val rawDigits = session.rawT9Digits
        if (rawDigits.length >= 3 && session.pinyinStack.isEmpty()) {
            val enCands = CnT9MixedInputDetector.detectEnglishCandidates(rawDigits)
            if (enCands.isNotEmpty()) {
                enCands.forEach { word ->
                    currentCandidates.add(
                        Candidate(
                            word = word, input = rawDigits, priority = 0,
                            matchedLength = rawDigits.length, pinyinCount = 0,
                            pinyin = null, syllables = 0, acronym = null
                        )
                    )
                }
                ui.setCandidates(currentCandidates)
            }
        }
    }

    // ── 选词提交 ───────────────────────────────────────────────────

    fun handleSpaceKey() {
        val idx = preferredIndex()
        if (idx != null) commitCandidateAt(idx) else commitRaw(" ")
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        val idx = preferredIndex() ?: return false
        val cand = preferredCandidate() ?: return false

        val shouldCommit = CnT9ConfidenceModel.shouldAutoCommit(
            preferredIndex = idx,
            cand = cand,
            candidateCount = currentCandidates.size,
            session = session,
            dictEngine = dictEngine,
            isRawCommitMode = isRawCommitMode(),
            userChoiceStore = userChoiceStore,
            contextWindow = contextWindow
        )
        if (!shouldCommit) return false

        commitCandidateAt(idx)
        return true
    }

    fun commitCandidateAt(index: Int) {
        if (index !in currentCandidates.indices) {
            val msg = "Candidate index out of range: CN_T9 index=$index size=${currentCandidates.size}"
            if (isDebuggable()) { Log.wtf("CnT9CandidateEngine", msg); throw AssertionError(msg) }
            return
        }

        ui.setSelectedCandidateIndex(index)
        val cand = currentCandidates[index]
        recordUserChoice(cand)

        if (isRawCommitMode()) {
            resetUiSelectionToTop(); commitRaw(cand.word); contextWindow?.record(cand.word); clearComposing()
            return
        }

        val consumeSyllables = CnT9CommitHelper.resolveConsumeSyllables(cand).coerceAtLeast(1)
        val stackSizeBefore = session.pinyinStack.size

        CnT9CommitHelper.materializeSegmentsIfNeeded(session, consumeSyllables, dictEngine)

        val availableStack = session.pinyinStack.size
        if (availableStack > 0) {
            val consume = consumeSyllables.coerceAtMost(availableStack)
            val pickCand = cand.copy(pinyinCount = consume)

            when (val r = session.pickCandidate(
                cand = pickCand, useT9Layout = true, isChinese = true,
                restorePinyinCountOnUndo = stackSizeBefore.coerceAtMost(consume)
            )) {
                is ComposingSession.PickResult.Commit -> {
                    resetUiSelectionToTop(); commitRaw(r.text); contextWindow?.record(cand.word); clearComposing()
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop(); updateCandidates()
                }
            }
            return
        }

        if (session.rawT9Digits.isNotEmpty()) {
            val consumeDigits = CnT9CommitHelper.resolveDigitsToConsume(cand)
                .coerceAtLeast(1).coerceAtMost(session.rawT9Digits.length)
            val pickCand = cand.copy(pinyinCount = 0)

            when (val r = session.pickCandidate(
                cand = pickCand, useT9Layout = true, isChinese = true,
                t9ConsumedDigitsCount = consumeDigits
            )) {
                is ComposingSession.PickResult.Commit -> {
                    resetUiSelectionToTop(); commitRaw(r.text); contextWindow?.record(cand.word); clearComposing()
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop(); updateCandidates()
                }
            }
            return
        }

        resetUiSelectionToTop(); commitRaw(cand.word); contextWindow?.record(cand.word); clearComposing()
    }

    fun commitCandidate(cand: Candidate) {
        val idx = currentCandidates.indexOf(cand)
        if (idx < 0) {
            val msg = "Candidate not in current CN_T9 list: cand=$cand"
            if (isDebuggable()) { Log.wtf("CnT9CandidateEngine", msg); throw AssertionError(msg) }
            return
        }
        commitCandidateAt(idx)
    }

    // ── 用户学习记录 ───────────────────────────────────────────────

    private fun recordUserChoice(cand: Candidate) {
        val store = userChoiceStore ?: return
        // 统一使用 CnT9PinyinSplitter.normalizeCandidate，与 getBoost 侧 key 格式保持一致
        val key = CnT9PinyinSplitter.normalizeCandidate(cand.pinyin, cand.input)
        store.recordChoice(key, cand.word)
    }
}
