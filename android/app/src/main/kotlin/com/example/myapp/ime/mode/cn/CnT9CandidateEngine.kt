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

    /**
     * 用户在 sidebar 点选了一个拼音音节（如 "zhong"）。
     */
    fun onSidebarItemClick(pinyin: String, t9Code: String) {
        if (pinyin.isEmpty()) return
        session.onPinyinSidebarClick(pinyin = pinyin, t9Code = t9Code)
        sidebarState.advanceFocus(session)
        updateCandidates()
    }

    /**
     * 用户点击了已物化的拼音段（触发重切分 / 消歧）。
     */
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

        // ── S0 Idle：不在 composing 状态 ──────────────────────────
        if (!session.isComposing()) {
            composingPreviewOverride = null
            enterCommitTextOverride = null
            resetUiSelectionToTop()
            sidebarState.clearAll()
            if (isExpanded) isExpanded = false

            // Idle 状态下注入标点快捷候选（规则清单「标点候选快捷行」）
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

        // ── S1/S2 Composing：正常候选生成 ─────────────────────────
        val out: ImeModeHandler.Output = CnT9Handler.build(
            session = session,
            dictEngine = dictEngine,
            singleCharMode = isSingleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow = contextWindow,
            sidebarState = sidebarState
        )

        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride = out.enterCommitText
        currentCandidates = ArrayList(out.candidates)
        resetUiSelectionToTop()

        syncFilterButton()
        ui.showComposingState(isExpanded = isExpanded)
        ui.setExpanded(isExpanded, isComposing = true)

        keyboardController.updateSidebar(
            syllables = out.pinyinSidebar,
            title = out.sidebarTitle,
            resegmentPaths = out.resegmentPaths
        )

        // ── 混输模式候选注入（规则清单「中英文混输」）────────────────
        val rawDigits = session.rawT9Digits
        if (rawDigits.length >= 3 && session.pinyinStack.isEmpty()) {
            injectMixedInputCandidates(rawDigits)
        }

        ui.setCandidates(currentCandidates)
    }

    /**
     * 根据混输模式检测结果，将英文/URL/邮箱候选插入候选列表头部。
     *
     * 对应规则清单：
     *  - 英文直出候选插入**头部**（之前是末尾），让用户无需翻页即可选取
     *  - URL/邮箱模式同理
     *
     * @param rawDigits 当前未物化的纯数字串
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
