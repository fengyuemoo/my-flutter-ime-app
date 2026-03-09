package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.ime.compose.common.ComposingSession

class CnT9StateCoordinator(
    private val mutator: CnT9SessionMutator = CnT9SessionMutator(),
    private val stateMachine: CnT9StateMachine = CnT9StateMachine()
) {
    private var lastEvent: CnT9StateEvent? = null

    fun currentState(): CnT9SessionState = mutator.state

    fun snapshot(): CnT9StateSnapshot {
        return stateMachine.snapshot(
            state = mutator.state,
            lastEvent = lastEvent
        )
    }

    fun markCleared(): CnT9StateSnapshot {
        mutator.clearAll()
        lastEvent = CnT9StateEvent.Cleared
        return snapshot()
    }

    fun markCandidateSelectionStarted(): CnT9StateSnapshot {
        lastEvent = CnT9StateEvent.CandidateSelectionStarted
        return snapshot()
    }

    fun markCandidateCommitted(text: String): CnT9StateSnapshot {
        lastEvent = CnT9StateEvent.CandidateCommitted(text)
        return snapshot()
    }

    fun syncFromSession(
        session: ComposingSession,
        event: CnT9StateEvent? = null
    ): CnT9StateSnapshot {
        val old = mutator.state

        val eventFocusedIndex = when (event) {
            is CnT9StateEvent.SidebarSegmentFocused -> event.index
            else -> null
        }

        val materializedSegments = session.t9MaterializedSegments.map { seg ->
            CnT9MaterializedSegment(
                syllable = seg.syllable,
                digitChunk = seg.digitChunk,
                locked = seg.locked,
                localCuts = seg.localCuts.toSet()
            )
        }

        val resolvedFocusedIndex = eventFocusedIndex
            ?.takeIf { it in materializedSegments.indices }
            ?: old.safeFocusedSegmentIndex()
                ?.takeIf { it in materializedSegments.indices }

        val rebuilt = CnT9SessionState(
            rawDigits = session.rawT9Digits,
            committedPrefix = session.committedPrefix,
            materializedSegments = materializedSegments,
            manualCuts = session.t9ManualCuts.toSet(),
            focusedSegmentIndex = resolvedFocusedIndex,
            selectedCandidateIndex = old.selectedCandidateIndex,
            isCandidatesExpanded = old.isCandidatesExpanded,
            revision = old.revision
        ).sanitized()

        mutator.replaceState(rebuilt)
        lastEvent = event
        return snapshot()
    }

    fun setSelectedCandidateIndex(index: Int): CnT9StateSnapshot {
        mutator.setSelectedCandidateIndex(index)
        return snapshot()
    }

    fun setCandidatesExpanded(expanded: Boolean): CnT9StateSnapshot {
        mutator.setCandidatesExpanded(expanded)
        return snapshot()
    }

    fun setFocusedSegment(index: Int?): CnT9StateSnapshot {
        mutator.setFocusedSegment(index)
        lastEvent = index?.let { CnT9StateEvent.SidebarSegmentFocused(it) }
        return snapshot()
    }
}
