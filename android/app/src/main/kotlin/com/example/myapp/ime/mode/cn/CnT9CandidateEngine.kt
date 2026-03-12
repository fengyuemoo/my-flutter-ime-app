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
 *  5. 管理 sidebar 焦点与锁定（CnT9SidebarState）
 *  6. Idle 状态下注入标点快捷候选
 *  7. 混输模式（英文/URL/邮箱）候选插入头部
 *
 * 不包含：
 *  - 拼音切分工具函数    → CnT9PinyinSplitter
 *  - 音节物化 & 消费计算  → CnT9CommitHelper
 *  - 首选置信度模型      → CnT9ConfidenceModel
 *  - sidebar 内容构建    → CnT9SidebarBuilder
 *  - sidebar 焦点状态    → CnT9SidebarState
 *  - 段锁定状态         → CnT9SegmentLockMap
 *  - 标点候选数据        → CnT9PunctuationCandidates
 *  - 混输检测           → CnT9MixedInputDetector
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
    private val sidebarState: CnT9SidebarState = CnT9SidebarState(),
    private val isFullWidthPunct: () -> Boolean = { true }
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

    // ── Sidebar 交互 ───────────────────────────────────────────────

    fun onSidebarItemClick(pinyin: String, t9Code: String) {
        if (pinyin.isEmpty()) return
        session.onPinyinSidebarClick(pinyin = pinyin, t9Code = t9Code)
        sidebarState.advanceFocus(session)
        updateCandidates()
    }

    fun onSegmentFocused(segmentIndex: Int) {
        if (segmentIndex < 0) return
        sidebarState.clearLocksFrom(segmentIndex)
        session.rollbackMaterializedSegmentsFrom(segmentIndex)
        sidebarState.setFocus(segmentIndex)
        updateCandidates()
    }

    // ── 退格（含焦点同步）─────────────────────────────────────────

    fun handleBackspace(): Boolean {
        val hadRawDigits = session.rawT9Digits.isNotEmpty()
        val stackSizeBefore = session.pinyinStack.size

        val consumed = session.backspace(useT9Layout = true)

        when {
            !session.isComposing() -> {
                sidebarState.clearAll()
            }
            !hadRawDigits && session.pinyinStack.size < stackSizeBefore -> {
                val removedIndex = session.pinyinStack.size
                sidebarState.onSegmentRemoved(removedIndex)
            }
            sidebarState.isDisambiguating && session.rawT9Digits.isEmpty() -> {
                sidebarState.retreatFocus()
            }
        }

        updateCandidates()
        return consumed
    }

    // ── 候选更新 ───────────────────────────────────────────────────

    fun updateCandidates() {
        syncFilterButton()
        currentCandidates.clear()

        // ── S0 Idle ───────────────────────────────────────────────
        if (!session.isComposing()) {
            composingPreviewOverride = null
            enterCommitTextOverride  = null
            resetUiSelectionToTop()
            sidebarState.clearAll()
            if (isExpanded) isExpanded = false

            CnT9PunctuationCandidates.injectIdlePunctuations(
                candidates = currentCandidates,
                isFullWidth = isFullWidthPunct()
            )

            ui.showIdleState()
            ui.setExpanded(false, isComposing = false)
            keyboardController.updateSidebar(emptyList())
            ui.setCandidates(currentCandidates)
            return
        }

        // ── S1/S2 Composing ───────────────────────────────────────
        val out: ImeModeHandler.Output = CnT9Handler.build(
            session        = session,
            dictEngine     = dictEngine,
            singleCharMode = isSingleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow,
            sidebarState    = sidebarState
        )

        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride  = out.enterCommitText
        currentCandidates        = ArrayList(out.candidates)
        resetUiSelectionToTop()

        syncFilterButton()
        ui.showComposingState(isExpanded = isExpanded)
        ui.setExpanded(isExpanded, isComposing = true)

        keyboardController.updateSidebar(
            syllables       = out.pinyinSidebar,
            title           = out.sidebarTitle,
            resegmentPaths  = out.resegmentPaths
        )

        val rawDigits = session.rawT9Digits
        if (rawDigits.length >= 3 && session.pinyinStack.isEmpty()) {
            injectMixedInputCandidates(rawDigits)
        }

        ui.setCandidates(currentCandidates)
    }

    /**
     * 根据混输模式检测结果，将英文/URL/邮箱候选插入候选列表头部。
     *
     * 修复：显式标注 injectWords 类型为 List<String>，
     * CHINESE 分支明确返回 emptyList<String>() 避免类型推断歧义。
     */
    private fun injectMixedInputCandidates(rawDigits: String) {
        val mode = CnT9MixedInputDetector.detectMode(rawDigits)

        val injectWords: List<String> = when (mode) {
            CnT9MixedInputDetector.InputMode.URL ->
                CnT9MixedInputDetector.detectUrlCandidates(rawDigits)

            CnT9MixedInputDetector.InputMode.EMAIL ->
                CnT9MixedInputDetector.detectEmailSuffixCandidates(rawDigits)

            CnT9MixedInputDetector.InputMode.ENGLISH ->
                CnT9MixedInputDetector.detectEnglishCandidates(rawDigits)

            CnT9MixedInputDetector.InputMode.CHINESE ->
                emptyList()
        }

        if (injectWords.isEmpty()) return

        val injected = injectWords.map { word ->
            Candidate(
                word           = word,
                input          = rawDigits,
                priority       = Int.MAX_VALUE,
                matchedLength  = rawDigits.length,
                pinyinCount    = 0,
                pinyin         = null,
                syllables      = 0,
                acronym        = null
            )
        }
        currentCandidates.addAll(0, injected)
    }

    // ── 选词提交 ───────────────────────────────────────────────────

    fun handleSpaceKey() {
        val idx = preferredIndex()
        if (idx != null) commitCandidateAt(idx) else commitRaw(" ")
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        val idx  = preferredIndex()        ?: return false
        val cand = preferredCandidate()    ?: return false

        val shouldCommit = CnT9ConfidenceModel.shouldAutoCommit(
            preferredIndex  = idx,
            cand            = cand,
            candidateCount  = currentCandidates.size,
            session         = session,
            dictEngine      = dictEngine,
            isRawCommitMode = isRawCommitMode(),
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow
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

        // ── 标点候选：直接上屏，跳过拼音匹配逻辑 ─────────────────
        if (CnT9PunctuationCandidates.isPunctCandidate(cand)) {
            commitRaw(cand.word)
            return
        }

        recordUserChoice(cand)

        if (isRawCommitMode()) {
            resetUiSelectionToTop()
            sidebarState.clearAll()
            commitRaw(cand.word)
            contextWindow?.record(cand.word)
            clearComposing()
            return
        }

        val consumeSyllables = CnT9CommitHelper.resolveConsumeSyllables(cand).coerceAtLeast(1)
        val stackSizeBefore  = session.pinyinStack.size

        CnT9CommitHelper.materializeSegmentsIfNeeded(session, consumeSyllables, dictEngine)

        val availableStack = session.pinyinStack.size
        if (availableStack > 0) {
            val consume  = consumeSyllables.coerceAtMost(availableStack)
            val pickCand = cand.copy(pinyinCount = consume)

            when (val r = session.pickCandidate(
                cand = pickCand, useT9Layout = true, isChinese = true,
                restorePinyinCountOnUndo = stackSizeBefore.coerceAtMost(consume)
            )) {
                is ComposingSession.PickResult.Commit -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    commitRaw(r.text)
                    contextWindow?.record(cand.word)
                    clearComposing()
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    updateCandidates()
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
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    commitRaw(r.text)
                    contextWindow?.record(cand.word)
                    clearComposing()
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    updateCandidates()
                }
            }
            return
        }

        resetUiSelectionToTop()
        sidebarState.clearAll()
        commitRaw(cand.word)
        contextWindow?.record(cand.word)
        clearComposing()
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
        if (CnT9PunctuationCandidates.isPunctCandidate(cand)) return
        val key = CnT9PinyinSplitter.normalizeCandidate(cand.pinyin, cand.input)
        store.recordChoice(key, cand.word)
    }
}
