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
 * 职责：
 *  1. 驱动 CnT9Handler 生成候选列表（updateCandidates）
 *  2. 处理用户选词提交（commitCandidateAt / commitCandidate）
 *  3. 处理空格键与 Enter 首选上屏
 *  4. 管理 UI 展开/收起、单字过滤模式
 *  5. 管理 sidebar 焦点与锁定（CnT9SidebarState）
 *  6. Idle 状态下注入标点快捷候选
 *  7. 混输模式（英文/URL/邮箱）候选插入头部
 *  8. 候选排序稳定化（CnT9CandidateStabilizer）
 *
 * ── R-Perf01 修复：后台异步候选生成 ──────────────────────────────────
 *  updateCandidates() 将 CnT9Handler.build()（包含 planAll + 字典查询 + 多维排序）
 *  分发到 Dispatchers.Default 后台线程执行，结果通过 withContext(Dispatchers.Main)
 *  回到主线程更新 UI，避免阻塞 IME 主线程（防 ANR / 掉帧）。
 *
 *  - 每次新调用 updateCandidates() 时取消上一个未完成的 Job（debounce cancel），
 *    保证最终展示的永远是最新输入状态的结果。
 *  - Idle 路径（无 session.isComposing()）不需要后台，直接同步执行。
 *  - 调用方需在 IME 生命周期结束时调用 destroy() 取消协程 scope。
 *
 * [onPreeditInvalidate] 为可选回调，在选词上屏或 Idle 时通知外部
 * （CandidateController）使 CnT9PreeditFormatter 缓存失效（P1 修复）。
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
    private val onPreeditInvalidate: (() -> Unit)? = null   // P1 修复：preedit 缓存失效回调
) {
    private var isExpanded: Boolean = false
    private var isSingleCharMode: Boolean = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()
    private var composingPreviewOverride: String? = null
    private var enterCommitTextOverride: String? = null

    private val stabilizer = CnT9CandidateStabilizer()

    // R-Perf01：候选生成协程 scope，与 IME 生命周期绑定
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 当前候选生成 Job，新请求到来时 cancel 旧 Job（debounce cancel）
    private var candidateJob: Job? = null

    fun getComposingPreviewOverride(): String? = composingPreviewOverride
    fun getEnterCommitTextOverride(): String? = enterCommitTextOverride

    /**
     * 释放协程 scope。在 IME Service onDestroy() 时调用。
     */
    fun destroy() {
        engineScope.cancel()
    }

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

    // ── 候选更新（R-Perf01：异步化）──────────────────────────────

    fun updateCandidates() {
        syncFilterButton()

        // ── S0 Idle（同步，无需后台）──────────────────────────────
        if (!session.isComposing()) {
            // 取消任何挂起的后台 Job
            candidateJob?.cancel()
            candidateJob = null

            currentCandidates.clear()
            composingPreviewOverride = null
            enterCommitTextOverride = null
            resetUiSelectionToTop()
            sidebarState.clearAll()
            stabilizer.reset()
            onPreeditInvalidate?.invoke()
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

        // ── S1/S2 Composing（后台异步）──────────────────────────
        // 取消上一次未完成的候选 Job（debounce cancel）
        candidateJob?.cancel()

        // 快照当前输入状态（避免后台线程访问 session 可变状态）
        val snapSingleCharMode  = isSingleCharMode
        val snapRawDigits       = session.rawT9Digits
        // CnT9Handler 内部会访问 session，session 是主线程单例，
        // 这里将整个 build 调用放到后台但不传 session 引用——
        // 改为传 session 的不可变快照（见下方 buildSnapshot）。
        val sessionSnapshot     = session.buildSnapshot()

        candidateJob = engineScope.launch {
            // ── 后台：CPU 密集型计算 ──────────────────────────────
            val out: ImeModeHandler.Output = withContext(Dispatchers.Default) {
                CnT9Handler.buildFromSnapshot(
                    snapshot        = sessionSnapshot,
                    dictEngine      = dictEngine,
                    singleCharMode  = snapSingleCharMode,
                    userChoiceStore = userChoiceStore,
                    contextWindow   = contextWindow,
                    sidebarState    = sidebarState
                )
            }

            // ── 主线程：更新 UI ──────────────────────────────────
            // Job 未被取消时才更新（保证最新请求生效）
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
        val idx = preferredIndex() ?: return false
        val cand = preferredCandidate() ?: return false

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
        // 选词时立即取消后台 Job，避免旧结果覆盖上屏后状态
        candidateJob?.cancel()
        candidateJob = null

        if (index !in currentCandidates.indices) {
            val msg = "Candidate index out of range: CN_T9 index=$index size=${currentCandidates.size}"
            if (isDebuggable()) { Log.wtf("CnT9CandidateEngine", msg); throw AssertionError(msg) }
            return
        }

        ui.setSelectedCandidateIndex(index)
        val cand = currentCandidates[index]

        // ── 标点候选：直接上屏 ────────────────────────────────────
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
            return
        }

        val consumeSyllables = CnT9CommitHelper.resolveConsumeSyllables(cand).coerceAtLeast(1)
        val stackSizeBefore = session.pinyinStack.size

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
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    stabilizer.invalidate()
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
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    sidebarState.clearAll()
                    stabilizer.invalidate()
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
