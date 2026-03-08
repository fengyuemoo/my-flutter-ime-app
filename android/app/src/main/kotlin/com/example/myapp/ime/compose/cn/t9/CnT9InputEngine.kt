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

    override fun refreshCandidates() {
        super.refreshCandidates()
        stateCoordinator.syncFromSession(session)
    }

    override fun clearComposing() {
        super.clearComposing()
        stateCoordinator.markCleared()
    }

    override fun handleT9Input(digit: String) {
        super.handleT9Input(digit)
        val normalized = digit.filter { it in '0'..'9' }
        stateCoordinator.syncFromSession(
            session = session,
            event = if (normalized.isNotEmpty()) {
                CnT9StateEvent.DigitsAppended(normalized)
            } else {
                null
            }
        )
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        val beforeCount = session.pinyinStack.size
        super.onPinyinSidebarClick(pinyin)
        val afterCount = session.pinyinStack.size

        val focusedIndex = when {
            afterCount > beforeCount -> afterCount - 1
            afterCount > 0 -> afterCount - 1
            else -> null
        }

        stateCoordinator.syncFromSession(
            session = session,
            event = focusedIndex?.let { CnT9StateEvent.SidebarSegmentFocused(it) }
        )
    }

    override fun handleBackspace() {
        super.handleBackspace()
        stateCoordinator.syncFromSession(
            session = session,
            event = CnT9StateEvent.BackspacePressed
        )
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

        stateCoordinator.syncFromSession(
            session = session,
            event = event
        )
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

            stateCoordinator.syncFromSession(
                session = session,
                event = event
            )
        }

        return consumed
    }

    override fun beforeModeSwitch() {
        super.beforeModeSwitch()
        stateCoordinator.syncFromSession(session)
    }

    override fun afterModeSwitch() {
        super.afterModeSwitch()
        stateCoordinator.syncFromSession(session)
    }
}
