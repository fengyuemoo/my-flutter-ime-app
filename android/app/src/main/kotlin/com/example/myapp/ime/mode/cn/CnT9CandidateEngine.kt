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

/**
 * CN-T9 候选引擎（协调器）。
 *
 * ── R-Perf01：后台异步候选生成 ──────────────────────────────────────
 *  updateCandidates() 分发到 Dispatchers.Default 后台线程执行，
 *  结果通过 withContext(Dispatchers.Main) 回到主线程更新 UI。
 *
 * ── R-L04（问题7修复）：退词惩罚触发 ────────────────────────────────
 *  handleBackspace() 在上屏后第一次被调用时，触发
 *  userChoiceStore.penalizeLastChoiceIfRecent()。
 *  标志位 pendingPenaltyOnBackspace 在每次 commitCandidateAt() 上屏后置 true，
 *  退格时消费一次后立即重置为 false，防止多次退格叠加惩罚。
 *
 * ── 缺陷 B 修复：sidebarState 跨线程竞态消除 ────────────────────────
 *  updateCandidates() 在进入协程前于主线程完成 sidebarState 快照：
 *    snapLockedIndices = sidebarState.lockMap.lockedSnapshot.toList()
 *    snapFocusedIndex  = sidebarState.focusedSegmentIndex
 *  快照后的不可变值传入 CnT9Handler.buildFromSnapshot()，
 *  不再将可变的 sidebarState 对象本身跨线程传递。
 *
 * ── R-E02 修复（问题4）：无候选兜底 ────────────────────────────────
 *  当词库查询结果为空时，必须至少展示一个候选。
 *  兜底优先级：composingPreviewOverride（拼音预览）> rawDigits 直出。
 *  兜底候选在 stabilizer.stabilize() 之前注入，保证稳定化流程也能感知到候选。
 *  兜底候选的标识：priority == 0 且为列表中唯一候选。
 *
 * ── 新问题 B 修复：R-E02 兜底候选不自动上屏 ──────────────────────
 *  commitFirstCandidateOnEnter() 新增前置检查：
 *  若当前列表仅含 1 个候选且 priority == 0（R-E02 兜底候选），
 *  则直接返回 false，不触发 shouldAutoCommit，
 *  避免将拼音预览文本或原始数字串盲目提交给用户。
 *
 * ── 缺陷4修复：连打重排时机 ─────────────────────────────────────────
 *  commitCandidateAt() 的 PickResult.Updated 分支（选短词、剩余段继续
 *  composing）不再调用 stabilizer.invalidate()。
 *  只有 PickResult.Commit（完整上屏、会话结束）才调用 invalidate()，
 *  防止连打场景下后续段候选顺序不必要地强制重排，损害肌肉记忆。
 *
 * ── ContextWindow 跨会话污染修复 ────────────────────────────────
 *  新增 onStartInput() 方法，在输入法焦点切换到新输入框时调用，
 *  执行 contextWindow?.clear()，防止上一个输入框的上下文词
 *  错误影响新输入框的候选排序。
 *  同时在 S0 Idle 分支（session 结束 composing）也调用 clear()，
 *  确保每次 composing 结束后上下文干净（适用于同一输入框内换行等场景）。
 *
 *  调用方（ImeService 或等价宿主）须在 onStartInputView() / onStartInput()
 *  生命周期回调中调用 engine.onStartInput()。
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

    /**
     * R-L04：标记"上次上屏后尚未收到第一次退格"。
     * commitCandidateAt() 成功上屏后置 true；handleBackspace() 消费一次后置 false。
     */
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

    /**
     * 输入焦点切换到新输入框时调用（由 ImeService.onStartInputView 或
     * onStartInput 触发）。
     *
     * ContextWindow 跨会话污染修复：
     *  清除上一个输入框遗留的上下文窗口，防止跨输入框 bigram 偏置污染。
     */
    fun onStartInput() {
        contextWindow?.clear()
        pendingPenaltyOnBackspace = false
    }

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

    // ── 退格（含焦点同步 + R-L04 退词惩罚触发）────────────────────

    fun handleBackspace(): Boolean {
        // R-L04：上屏后第一次退格，触发退词惩罚（时间窗口内才生效）
        if (pendingPenaltyOnBackspace) {
            pendingPenaltyOnBackspace = false
            userChoiceStore?.penalizeLastChoiceIfRecent()
        }

        val hadRawDigits    = session.rawT9Digits.isNotEmpty()
        val stackSizeBefore = session.pinyinStack.size

        val consumed = session.backspace(useT9Layout = true)

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

    // ── 候选更新（缺陷 B 修复 + R-E02 修复版）──────────────────────

    fun updateCandidates() {
        syncFilterButton()

        // ── S0 Idle ───────────────────────────────────────────────
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

            // ContextWindow 跨会话污染修复：
            // composing 结束（换行、清空、焦点切换后的 Idle）时清除上下文，
            // 防止同一输入框内句间上下文串扰（如换行后首字被上一句末词偏置）。
            contextWindow?.clear()

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

        // ── S1/S2 Composing（后台异步）──────────────────────────
        candidateJob?.cancel()

        val snapSingleCharMode = isSingleCharMode
        val snapRawDigits      = session.rawT9Digits
        val sessionSnapshot    = session.buildSnapshot()

        // 缺陷 B 修复：在主线程完成 sidebarState 快照，避免可变对象跨线程传递
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

            // R-E02 修复（问题4）：词库查无结果时必须至少展示一个候选（拼音直出兜底）
            // 在 stabilizer.stabilize() 之前注入，保证稳定化也能感知到该兜底候选
            // 兜底候选标识：priority == 0，供 commitFirstCandidateOnEnter() 识别
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

        // 新问题 B 修复：R-E02 兜底候选不触发自动上屏。
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

        // 标点候选：直接上屏，不触发退词惩罚机制
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
                    // 缺陷4修复：Updated（连打继续）不调用 stabilizer.invalidate()
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
                    // 缺陷4修复：Updated 不 invalidate stabilizer
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
