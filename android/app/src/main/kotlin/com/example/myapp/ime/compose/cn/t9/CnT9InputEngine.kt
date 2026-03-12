package com.example.myapp.ime.compose.cn.t9

import android.content.Context
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.CnBaseInputEngine
import com.example.myapp.ime.ui.ImeUi

class CnT9InputEngine(
    context: Context,
    ui: ImeUi,
    keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    inputConnectionProvider: () -> InputConnection?
) : CnBaseInputEngine(
    context = context,
    ui = ui,
    keyboardController = keyboardController,
    candidateController = candidateController,
    session = session,
    inputConnectionProvider = inputConnectionProvider,
    useT9Layout = true,
    logTag = "CnT9InputEngine",
    strategy = CnT9ComposeStrategy(
        sessionProvider = { session },
        enterCommitProvider = { candidateController.getEnterCommitTextOverride() }
    )
) {
    private val stateMachine = CnT9StateMachine()

    private var currentState: CnT9SessionState = CnT9SessionState()
    private var lastEvent: CnT9StateEvent? = null

    private var pinnedFocusedIndex: Int? = null

    init {
        currentState = buildStateFromSession(focusOverride = null)
    }

    // ── 公开读取接口 ────────────────────────────────────────────────

    fun getStateSnapshot(): CnT9StateSnapshot = stateMachine.snapshot(currentState, lastEvent)

    /** 供键盘 UI 读取当前焦点音节下标（-1 表示无焦点）*/
    fun getFocusedSegmentIndex(): Int = pinnedFocusedIndex ?: -1

    // ── 核心状态同步 ────────────────────────────────────────────────

    private fun buildStateFromSession(
        focusOverride: Int? = null,
        clearFocus: Boolean = false
    ): CnT9SessionState {
        val segments = session.t9MaterializedSegments.mapIndexed { _, snap ->
            CnT9MaterializedSegment(
                syllable = snap.syllable,
                digitChunk = snap.digitChunk,
                locked = snap.locked,
                localCuts = snap.localCuts.toSet()
            )
        }

        val resolvedFocus = when {
            clearFocus -> null
            focusOverride != null -> focusOverride.takeIf { it in segments.indices }
            else -> pinnedFocusedIndex?.takeIf { it in segments.indices }
        }

        pinnedFocusedIndex = resolvedFocus

        return CnT9SessionState(
            rawDigits = session.rawT9Digits,
            committedPrefix = session.committedPrefix,
            materializedSegments = segments,
            manualCuts = session.t9ManualCuts.toSet(),
            focusedSegmentIndex = resolvedFocus,
            selectedCandidateIndex = currentState.selectedCandidateIndex,
            isCandidatesExpanded = currentState.isCandidatesExpanded,
            revision = currentState.revision + 1
        )
    }

    private fun applyEvent(
        event: CnT9StateEvent? = null,
        focusOverride: Int? = null,
        clearFocus: Boolean = false
    ) {
        lastEvent = event
        currentState = buildStateFromSession(
            focusOverride = focusOverride,
            clearFocus = clearFocus
        )
    }

    private fun lastMaterializedIndexOrNull(): Int? =
        session.pinyinStack.lastIndex.takeIf { it >= 0 }

    // ── 音节栏：焦点 + 锁定 ─────────────────────────────────────────

    fun focusMaterializedSegment(index: Int) {
        if (index !in session.pinyinStack.indices) return
        applyEvent(
            event = CnT9StateEvent.SidebarSegmentFocused(index),
            focusOverride = index
        )
        super.refreshCandidates()
    }

    fun replaceFocusedSegmentWith(pinyin: String) {
        val focusedIdx = pinnedFocusedIndex ?: return
        val changed = session.replaceMaterializedSegmentAt(focusedIdx, pinyin)
        if (!changed) return

        applyEvent(
            event = CnT9StateEvent.SidebarSegmentFocused(focusedIdx),
            focusOverride = focusedIdx
        )
        super.refreshCandidates()
    }

    /**
     * 在焦点音节的右边界（即该音节 digitChunk 结束后的位置）循环插入/移除切分点。
     *
     * - 有焦点音节：在该音节对应 digitChunk 的累积右边界处切换切分点
     * - 无焦点（或焦点为末段）：在 rawDigits 首位切换（原行为，作为降级）
     *
     * 切分点位置语义：position N 表示"第 N 位数字之后断开"（1-based）。
     */
    fun cycleManualCut() {
        if (session.rawT9Digits.isEmpty()) return

        val cutPos = resolveCutPosition()

        val cuts = session.t9ManualCuts
        if (cuts.contains(cutPos)) {
            session.removeT9ManualCut(cutPos)
        } else {
            session.insertT9ManualCut(cutPos)
        }

        applyEvent(event = CnT9StateEvent.DigitsAppended(""), clearFocus = false)
        super.refreshCandidates()
    }

    /**
     * 计算切分点位置：
     * - 有焦点音节 → 该音节 digitChunk 的长度之和（从头累积到焦点段，不含焦点段后的部分）
     * - 无焦点 → 1（rawDigits 首位，原行为）
     */
    private fun resolveCutPosition(): Int {
        val focusedIdx = pinnedFocusedIndex
            ?.takeIf { it in session.t9MaterializedSegments.indices }
            ?: return 1

        // 累积焦点段之前所有段的 digit 长度，即焦点段左边界
        // 切分点在焦点段右边界 = 左边界 + 焦点段自身 digit 长度
        var accumulated = 0
        val segs = session.t9MaterializedSegments
        for (i in 0..focusedIdx) {
            val chunk = segs[i].digitChunk.filter { it in '0'..'9' }
            accumulated += chunk.length.coerceAtLeast(1)
        }
        // 右边界位置（切分点在此处表示焦点段之后断开）
        return accumulated.coerceAtLeast(1)
    }

    // ── 覆写父类行为 ────────────────────────────────────────────────

    override fun refreshCandidates() {
        super.refreshCandidates()
        applyEvent()
    }

    override fun clearComposing() {
        super.clearComposing()
        applyEvent(event = CnT9StateEvent.Cleared, clearFocus = true)
    }

    override fun handleT9Input(digit: String) {
        super.handleT9Input(digit)
        val normalized = digit.filter { it in '0'..'9' }
        applyEvent(
            event = if (normalized.isNotEmpty()) CnT9StateEvent.DigitsAppended(normalized) else null,
            clearFocus = true
        )
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        super.onPinyinSidebarClick(pinyin)
        applyEvent(
            event = CnT9StateEvent.SidebarSegmentFocused(lastMaterializedIndexOrNull() ?: 0),
            focusOverride = lastMaterializedIndexOrNull()
        )
    }

    override fun handleBackspace() {
        val focusedIndex = pinnedFocusedIndex
            ?.takeIf { it in session.pinyinStack.indices }

        if (focusedIndex != null) {
            val consumed = session.backspaceMaterializedSegmentTailDigit(focusedIndex)
            if (consumed) {
                super.refreshCandidates()
                val newFocus = when {
                    focusedIndex in session.pinyinStack.indices -> focusedIndex
                    session.pinyinStack.isNotEmpty() -> session.pinyinStack.lastIndex
                    else -> null
                }
                applyEvent(
                    event = CnT9StateEvent.BackspacePressed,
                    focusOverride = newFocus,
                    clearFocus = newFocus == null
                )
                return
            }
        }

        super.handleBackspace()
        applyEvent(
            event = CnT9StateEvent.BackspacePressed,
            clearFocus = session.pinyinStack.isEmpty()
        )
    }

    override fun handleSpaceKey() {
        val wasComposing = session.isComposing()
        super.handleSpaceKey()
        applyEvent(
            event = if (wasComposing && !session.isComposing())
                CnT9StateEvent.CandidateCommitted("")
            else
                CnT9StateEvent.CandidateSelectionStarted,
            clearFocus = true
        )
    }

    override fun handleEnter(ic: InputConnection?): Boolean {
        val wasComposing = session.isComposing()
        val consumed = super.handleEnter(ic)
        if (consumed) {
            applyEvent(
                event = if (wasComposing && !session.isComposing())
                    CnT9StateEvent.CandidateCommitted("")
                else
                    CnT9StateEvent.CandidateSelectionStarted,
                clearFocus = true
            )
        }
        return consumed
    }

    override fun beforeModeSwitch() {
        super.beforeModeSwitch()
        applyEvent(clearFocus = true)
    }

    override fun afterModeSwitch() {
        super.afterModeSwitch()
        applyEvent(clearFocus = true)
    }
}
