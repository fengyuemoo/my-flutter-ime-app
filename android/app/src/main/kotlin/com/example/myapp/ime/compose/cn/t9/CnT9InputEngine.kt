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
    private val stateCoordinator = CnT9StateCoordinator()

    init {
        stateCoordinator.syncFromSession(session)
    }

    fun getStateSnapshot(): CnT9StateSnapshot {
        return stateCoordinator.snapshot()
    }

    private fun lastMaterializedIndexOrNull(): Int? {
        return session.pinyinStack.lastIndex.takeIf { it >= 0 }
    }

    private fun syncState(
        focusedIndex: Int? = null,
        fallbackEvent: CnT9StateEvent? = null
    ) {
        val event = focusedIndex?.let { CnT9StateEvent.SidebarSegmentFocused(it) } ?: fallbackEvent
        stateCoordinator.syncFromSession(
            session = session,
            event = event
        )
    }

    fun focusMaterializedSegment(index: Int) {
        val changed = session.rollbackMaterializedSegmentsFrom(index)
        val focusedIndexAfterRollback = lastMaterializedIndexOrNull()

        if (changed) {
            super.refreshCandidates()
        }

        syncState(focusedIndex = focusedIndexAfterRollback)
    }

    override fun refreshCandidates() {
        super.refreshCandidates()
        syncState()
    }

    override fun clearComposing() {
        super.clearComposing()
        stateCoordinator.markCleared()
    }

    override fun handleT9Input(digit: String) {
        super.handleT9Input(digit)
        val normalized = digit.filter { it in '0'..'9' }
        syncState(
            fallbackEvent = if (normalized.isNotEmpty()) {
                CnT9StateEvent.DigitsAppended(normalized)
            } else {
                null
            }
        )
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        super.onPinyinSidebarClick(pinyin)
        syncState(focusedIndex = lastMaterializedIndexOrNull())
    }

    override fun handleBackspace() {
        super.handleBackspace()
        syncState(fallbackEvent = CnT9StateEvent.BackspacePressed)
    }

    override fun handleSpaceKey() {
        val wasComposing = session.isComposing()
        stateCoordinator.markCandidateSelectionStarted()
        super.handleSpaceKey()

        val event = if (wasComposing && !session.isComposing()) {
            CnT9StateEvent.CandidateCommitted("")
        } else {
            CnT9StateEvent.CandidateSelectionStarted
        }

        syncState(fallbackEvent = event)
    }

    override fun handleEnter(ic: InputConnection?): Boolean {
        val wasComposing = session.isComposing()
        val consumed = super.handleEnter(ic)

        if (consumed) {
            val event = if (wasComposing && !session.isComposing()) {
                CnT9StateEvent.CandidateCommitted("")
            } else {
                CnT9StateEvent.CandidateSelectionStarted
            }

            syncState(fallbackEvent = event)
        }

        return consumed
    }

    override fun beforeModeSwitch() {
        super.beforeModeSwitch()
        syncState()
    }

    override fun afterModeSwitch() {
        super.afterModeSwitch()
        syncState()
    }
}
