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

    fun getStateSnapshot(): CnT9StateSnapshot = stateMachine.snapshot(currentState, lastEvent)

    fun getFocusedSegmentIndex(): Int = pinnedFocusedIndex ?: -1

    private fun buildStateFromSession(
        focusOverride: Int? = null,
        clearFocus: Boolean = false
    ): CnT9SessionState {
        val segments = session.t9MaterializedSegments.mapIndexed { _, snap ->
            CnT9MaterializedSegment(
                syllable   = snap.syllable,
                digitChunk = snap.digitChunk,
                locked     = snap.locked,
                localCuts  = snap.localCuts.toSet()
            )
        }

        val resolvedFocus = when {
            clearFocus        -> null
            focusOverride != null -> focusOverride.takeIf { it in segments.indices }
            else -> pinnedFocusedIndex?.takeIf { it in segments.indices }
        }

        pinnedFocusedIndex = resolvedFocus

        return CnT9SessionState(
            rawDigits              = session.rawT9Digits,
            committedPrefix        = session.committedPrefix,
            materializedSegments   = segments,
            manualCuts             = session.t9ManualCuts.toSet(),
            focusedSegmentIndex    = resolvedFocus,
            selectedCandidateIndex = currentState.selectedCandidateIndex,
            isCandidatesExpanded   = currentState.isCandidatesExpanded,
            revision               = currentState.revision + 1
        )
    }

    private fun applyEvent(
        event: CnT9StateEvent? = null,
        focusOverride: Int? = null,
        clearFocus: Boolean = false
    ) {
        lastEvent     = event
        currentState  = buildStateFromSession(
            focusOverride = focusOverride,
            clearFocus    = clearFocus
        )
    }

    private fun lastMaterializedIndexOrNull(): Int? =
        session.pinyinStack.lastIndex.takeIf { it >= 0 }

    fun focusMaterializedSegment(index: Int) {
        if (index !in session.pinyinStack.indices) return
        applyEvent(
            event        = CnT9StateEvent.SidebarSegmentFocused(index),
            focusOverride = index
        )
        super.refreshCandidates()
    }

    fun replaceFocusedSegmentWith(pinyin: String) {
        val focusedIdx = pinnedFocusedIndex ?: return
        val changed = session.replaceMaterializedSegmentAt(focusedIdx, pinyin)
        if (!changed) return

        applyEvent(
            event        = CnT9StateEvent.SidebarSegmentFocused(focusedIdx),
            focusOverride = focusedIdx
        )
        super.refreshCandidates()
    }

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

    private fun resolveCutPosition(): Int {
        val focusedIdx = pinnedFocusedIndex
            ?.takeIf { it in session.t9MaterializedSegments.indices }
            ?: return 1

        var accumulated = 0
        val segs = session.t9MaterializedSegments
        for (i in 0..focusedIdx) {
            val chunk = segs[i].digitChunk.filter { it in '0'..'9' }
            accumulated += chunk.length.coerceAtLeast(1)
        }
        return accumulated.coerceAtLeast(1)
    }

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
            event      = if (normalized.isNotEmpty()) CnT9StateEvent.DigitsAppended(normalized) else null,
            clearFocus = true
        )
    }

    // 修复：签名加 t9Code，传给 super
    override fun onPinyinSidebarClick(pinyin: String, t9Code: String) {
        super.onPinyinSidebarClick(pinyin, t9Code)
        applyEvent(
            event        = CnT9StateEvent.SidebarSegmentFocused(lastMaterializedIndexOrNull() ?: 0),
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
                    session.pinyinStack.isNotEmpty()            -> session.pinyinStack.lastIndex
                    else                                        -> null
                }
                applyEvent(
                    event        = CnT9StateEvent.BackspacePressed,
                    focusOverride = newFocus,
                    clearFocus   = newFocus == null
                )
                return
            }
        }

        super.handleBackspace()
        applyEvent(
            event      = CnT9StateEvent.BackspacePressed,
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
