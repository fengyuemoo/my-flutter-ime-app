package com.example.myapp.ime.mode.cn

import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.ImeModeHandler
import com.example.myapp.ime.ui.ImeUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val isFullWidthPunct: () -> Boolean = { true },
    private val onPreeditInvalidate: (() -> Unit)? = null
) {
    private var isExpanded: Boolean = false
    private var isSingleCharMode: Boolean = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()
    private var composingPreviewOverride: String? = null
    private var enterCommitTextOverride: String? = null

    private val stabilizer = CnT9CandidateStabilizer()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var candidateJob: Job? = null

    private var pendingPenaltyOnBackspace: Boolean = false

    fun getComposingPreviewOverride(): String? = composingPreviewOverride
    fun getEnterCommitTextOverride(): String? = enterCommitTextOverride

    fun destroy() { engineScope.cancel() }

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

    // ── 生命周期 ───────────────────────────────────────────────────

    fun onStartInput() {
        contextWindow?.clear()
        pendingPenaltyOnBackspace = false
        // 新输入框开始时清除 plan/query 缓存，防止跨输入框缓存污染
        CnT9Handler.invalidateCaches()
    }

    // ── UI 状态 ────────────────────────────────────────────────────

    fun syncFilterButton() = ui.setFilterButton(isSingleCharMode)

    fun toggleSingleCharMode() {
        isSingleCharMode = !isSingleCharMode
        syncFilterButton()
        resetUiSelectionToTop()
        // 切换单字模式时 query 结果过滤条件改变，清除 query 缓存
        CnT9CandidateFilter.invalidateQueryCache()
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
        // locked 状态改变，清除 query 缓存（plan 缓存不受影响）
        CnT9CandidateFilter.invalidateQueryCache()
        updateCandidates()
    }

    // ── 退格 ───────────────────────────────────────────────────────

    fun handleBackspace(): Boolean {
        if (pendingPenaltyOnBackspace) {
            pendingPenaltyOnBackspace = false
            userChoiceStore?.penalizeLastChoiceIfRecent()
        }

        val hadRawDigits    = session.rawT9Digits.isNotEmpty()
        val stackSizeBefore = session.pinyinStack.size

        val consumed = session.backspace(useT9Layout = true)

        // 退格后 digits 发生变化，plan 和 query 缓存均失效
        CnT9Handler.invalidateCaches()

        when {
            !session.isComposing() -> sidebarState.clearAll()
            !hadRawDigits && session.pinyinStack.size < stackSizeBefore -> {
                val removedIndex = session.pinyinStack.size
                sidebarState.onSegmentRemoved(removedIndex)
            }
            sidebarState.isDisambiguating && session.rawT9Digits.isEmpty() ->
                sidebarState.retreatFocus()
        }

        updateCandidates()
        return consumed
    }

    // ── 候选更新 ───────────────────────────────────────────────────

    fun updateCandidates() {
        syncFilterButton()

        if (!session.isComposing()) {
            candidateJob?.cancel()
            candidateJob = null

            currentCandidates.clear()
            composingPreviewOverride = null
            enterCommitTextOverride  = null
            resetUiSelectionToTop()
            sidebarState.clearAll()
            stabilizer.reset()
            onPreeditInvalidate?.invoke()
            if (isExpanded) isExpanded = false

            contextWindow?.clear()
            // 会话结束，清除所有缓存
            CnT9Handler.invalidateCaches()

            CnT9PunctuationCandidates.injectIdlePunctuations(
                candidates  = currentCandidates,
                isFullWidth = isFullWidthPunct()
            )

            ui.showIdleState()
            ui.setExpanded(false, isComposing = false)
            keyboardController.updateSidebar(emptyList())
            ui.setCandidates(currentCandidates)
            return
        }

        candidateJob?.cancel()

        val snapSingleCharMode = isSingleCharMode
        val snapRawDigits      = session.rawT9Digits
        val sessionSnapshot    = session.buildSnapshot()

        val snapLockedIndices = sidebarState.lockMap.lockedSnapshot.toList()
        val snapFocusedIndex  = sidebarState.focusedSegmentIndex

        candidateJob = engineScope.launch {
            val out: ImeModeHandler.Output = withContext(Dispatchers.Default) {
                CnT9Handler.buildFromSnapshot(
                    snapshot        = sessionSnapshot,
                    dictEngine      = dictEngine,
                    singleCharMode  = snapSingleCharMode,
                    userChoiceStore = userChoiceStore,
                    contextWindow   = contextWindow,
                    lockedIndices   = snapLockedIndices,
                    focusedIndex    = snapFocusedIndex
                )
            }

            composingPreviewOverride = out.composingPreviewText
            enterCommitTextOverride  = out.enterCommitText
            currentCandidates        = ArrayList(out.candidates)
            resetUiSelectionToTop()

            syncFilterButton()
            ui.showComposingState(isExpanded = isExpanded)
            ui.setExpanded(isExpanded, isComposing = true)

            keyboardController.updateSidebar(
                syllables      = out.pinyinSidebar,
                title          = out.sidebarTitle,
                resegmentPaths = out.resegmentPaths
            )

            if (snapRawDigits.length >= 3 && sessionSnapshot.pinyinStack.isEmpty()) {
                injectMixedInputCandidates(snapRawDigits)
            }

            if (currentCandidates.isEmpty() && session.isComposing()) {
                val fallbackWord = composingPreviewOverride?.takeIf { it.isNotEmpty() }
                    ?: snapRawDigits.takeIf { it.isNotEmpty() }
                if (fallbackWord != null) {
                    currentCandidates.add(
                        Candidate(
                            word          = fallbackWord,
                            input         = snapRawDigits,
                            priority      = 0,
                            matchedLength = snapRawDigits.length,
                            pinyinCount   = 0,
                            pinyin        = null,
                            syllables     = 0,
                            acronym       = null
                        )
                    )
                }
            }

            currentCandidates = stabilizer.stabilize(currentCandidates, snapRawDigits)
            ui.setCandidates(currentCandidates)
        }
    }

    private fun injectMixedInputCandidates(rawDigits: String) {
        val mode = CnT9MixedInputDetector.detectMode(rawDigits)

        val injectWords: List<String> = when (mode) {
            CnT9MixedInputDetector.InputMode.URL ->
                CnT9MixedInputDetector.detectUrlCandidates(rawDigits)
            CnT9MixedInputDetector.InputMode.EMAIL ->
                CnT9MixedInputDetector.detectEmailSuffixCandidates(rawDigits)
            CnT9MixedInputDetector.InputMode.ENGLISH ->
                CnT9MixedInputDetector.detectEnglishCandidates(rawDigits)
            CnT9MixedInputDetector.InputMode.CHINESE -> emptyList()
        }

        if (injectWords.isEmpty()) return

        val injected = injectWords.map { word ->
            Candidate(
                word = word, input = rawDigits, priority = Int.MAX_VALUE,
                matchedLength = rawDigits.length, pinyinCount = 0,
                pinyin = null, syllables = 0, acronym = null
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
        val idx  = preferredIndex() ?: return false
        val cand = preferredCandidate() ?: return false

        if (currentCandidates.size == 1 && cand.priority == 0) return false

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
        candidateJob?.cancel()
        candidateJob = null

        if (index !in currentCandidates.indices) {
            val msg = "Candidate index out of range: CN_T9 index=$index size=${currentCandidates.size}"
            if (isDebuggable()) { Log.wtf("CnT9CandidateEngine", msg); throw AssertionError(msg) }
            return
        }

        ui.setSelectedCandidateIndex(index)
        val cand = currentCandidates[index]

        if (CnT9PunctuationCandidates.isPunctCandidate(cand)) {
            stabilizer.invalidate()
            onPreeditInvalidate?.invoke()
            commitRaw(cand.word)
            return
        }

        recordUserChoice(cand)

        if (isRawCommitMode()) {
            resetUiSelectionToTop()
            sidebarState.clearAll()
            stabilizer.invalidate()
            onPreeditInvalidate?.invoke()
            commitRaw(cand.word)
            contextWindow?.record(cand.word)
            clearComposing()
            pendingPenaltyOnBackspace = true
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
                    stabilizer.invalidate()
                    onPreeditInvalidate?.invoke()
                    commitRaw(r.text)
                    contextWindow?.record(cand.word)
                    clearComposing()
                    pendingPenaltyOnBackspace = true
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    onPreeditInvalidate?.invoke()
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
                    stabilizer.invalidate()
                    onPreeditInvalidate?.invoke()
                    commitRaw(r.text)
                    contextWindow?.record(cand.word)
                    clearComposing()
                    pendingPenaltyOnBackspace = true
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    onPreeditInvalidate?.invoke()
                    updateCandidates()
                }
            }
            return
        }

        resetUiSelectionToTop()
        sidebarState.clearAll()
        stabilizer.invalidate()
        onPreeditInvalidate?.invoke()
        commitRaw(cand.word)
        contextWindow?.record(cand.word)
        clearComposing()
        pendingPenaltyOnBackspace = true
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
