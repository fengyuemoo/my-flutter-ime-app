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
    // 状态机 + 当前状态（替代已删除的 CnT9StateCoordinator）
    private val stateMachine = CnT9StateMachine()
    private var currentState: CnT9SessionState = CnT9SessionState()
    private var lastEvent: CnT9StateEvent? = null

    init {
        currentState = syncStateFromSession()
    }

    // ── 对外读取快照 ────────────────────────────────────────────────
    fun getStateSnapshot(): CnT9StateSnapshot = stateMachine.snapshot(currentState, lastEvent)

    // ── 内部状态同步 ────────────────────────────────────────────────

    private fun syncStateFromSession(): CnT9SessionState {
        return CnT9SessionState(
            rawDigits = session.rawT9Digits,
            committedPrefix = session.committedPrefix,
            materializedSegments = session.pinyinStack.mapIndexed { i, syllable ->
                CnT9MaterializedSegment(
                    syllable = syllable,
                    digitChunk = "",   // ComposingSession 不保存每段 digits，留空
                    locked = true
                )
            },
            focusedSegmentIndex = session.pinyinStack.lastIndex.takeIf { it >= 0 }
        )
    }

    private fun applyEvent(event: CnT9StateEvent? = null) {
        lastEvent = event
        currentState = syncStateFromSession()
    }

    private fun lastMaterializedIndexOrNull(): Int? =
        session.pinyinStack.lastIndex.takeIf { it >= 0 }

    // ── 对外操作 ────────────────────────────────────────────────────

    fun focusMaterializedSegment(index: Int) {
        val changed = session.rollbackMaterializedSegmentsFrom(index)
        if (changed) super.refreshCandidates()
        applyEvent(CnT9StateEvent.SidebarSegmentFocused(lastMaterializedIndexOrNull() ?: index))
    }

    override fun refreshCandidates() {
        super.refreshCandidates()
        applyEvent()
    }

    override fun clearComposing() {
        super.clearComposing()
        applyEvent(CnT9StateEvent.Cleared)
    }

    override fun handleT9Input(digit: String) {
        super.handleT9Input(digit)
        val normalized = digit.filter { it in '0'..'9' }
        applyEvent(
            if (normalized.isNotEmpty()) CnT9StateEvent.DigitsAppended(normalized) else null
        )
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        super.onPinyinSidebarClick(pinyin)
        applyEvent(CnT9StateEvent.SidebarSegmentFocused(lastMaterializedIndexOrNull() ?: 0))
    }

    override fun handleBackspace() {
        val focusedIndex = currentState.safeFocusedSegmentIndex()
            ?.takeIf { it in session.pinyinStack.indices }

        if (focusedIndex != null) {
            val consumed = session.backspaceMaterializedSegmentTailDigit(focusedIndex)
            if (consumed) {
                super.refreshCandidates()
                applyEvent(CnT9StateEvent.BackspacePressed)
                return
            }
        }

        super.handleBackspace()
        applyEvent(CnT9StateEvent.BackspacePressed)
    }

    override fun handleSpaceKey() {
        val wasComposing = session.isComposing()
        super.handleSpaceKey()
        applyEvent(
            if (wasComposing && !session.isComposing())
                CnT9StateEvent.CandidateCommitted("")
            else
                CnT9StateEvent.CandidateSelectionStarted
        )
    }

    override fun handleEnter(ic: InputConnection?): Boolean {
        val wasComposing = session.isComposing()
        val consumed = super.handleEnter(ic)
        if (consumed) {
            applyEvent(
                if (wasComposing && !session.isComposing())
                    CnT9StateEvent.CandidateCommitted("")
                else
                    CnT9StateEvent.CandidateSelectionStarted
            )
        }
        return consumed
    }

    override fun beforeModeSwitch() {
        super.beforeModeSwitch()
        applyEvent()
    }

    override fun afterModeSwitch() {
        super.afterModeSwitch()
        applyEvent()
    }
}
