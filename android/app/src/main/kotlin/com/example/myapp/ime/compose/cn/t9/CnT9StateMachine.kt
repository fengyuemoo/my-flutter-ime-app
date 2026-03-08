package com.example.myapp.ime.compose.cn.t9

class CnT9StateMachine {

    fun snapshot(
        state: CnT9SessionState,
        lastEvent: CnT9StateEvent? = null
    ): CnT9StateSnapshot {
        val phase = resolvePhase(state, lastEvent)

        return CnT9StateSnapshot(
            phase = phase,
            isComposing = state.isComposing(),
            hasRawDigits = state.hasRawDigits(),
            hasMaterializedSegments = state.hasMaterializedSegments(),
            focusedSegmentIndex = state.safeFocusedSegmentIndex(),
            selectedCandidateIndex = state.selectedCandidateIndex,
            isCandidatesExpanded = state.isCandidatesExpanded,
            revision = state.revision
        )
    }

    fun resolvePhase(
        state: CnT9SessionState,
        lastEvent: CnT9StateEvent? = null
    ): CnT9UiPhase {
        if (lastEvent is CnT9StateEvent.CandidateCommitted && !state.isComposing()) {
            return CnT9UiPhase.COMMITTED
        }

        if (!state.isComposing()) {
            return CnT9UiPhase.IDLE
        }

        if (isSelecting(state, lastEvent)) {
            return CnT9UiPhase.SELECTING
        }

        return CnT9UiPhase.COMPOSING
    }

    fun isIdle(state: CnT9SessionState): Boolean {
        return resolvePhase(state) == CnT9UiPhase.IDLE
    }

    fun isComposing(state: CnT9SessionState): Boolean {
        return resolvePhase(state) == CnT9UiPhase.COMPOSING
    }

    fun isSelecting(
        state: CnT9SessionState,
        lastEvent: CnT9StateEvent? = null
    ): Boolean {
        return when {
            !state.isComposing() -> false
            lastEvent is CnT9StateEvent.CandidateSelectionStarted -> true
            state.isCandidatesExpanded -> true
            state.selectedCandidateIndex > 0 -> true
            else -> false
        }
    }
}
