package com.example.myapp.ime.compose.cn.t9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CnT9StateMachineTest {

    private val machine = CnT9StateMachine()

    @Test
    fun resolvePhase_returnsCommitted_after_candidate_commit_when_session_not_composing() {
        val state = CnT9SessionState(
            rawDigits = "",
            committedPrefix = "",
            materializedSegments = emptyList(),
            selectedCandidateIndex = 0,
            isCandidatesExpanded = false
        )

        val phase = machine.resolvePhase(
            state = state,
            lastEvent = CnT9StateEvent.CandidateCommitted("")
        )

        assertEquals(CnT9UiPhase.COMMITTED, phase)
    }

    @Test
    fun resolvePhase_returnsIdle_for_plain_empty_state_without_commit_event() {
        val state = CnT9SessionState(
            rawDigits = "",
            committedPrefix = "",
            materializedSegments = emptyList()
        )

        val phase = machine.resolvePhase(state = state)

        assertEquals(CnT9UiPhase.IDLE, phase)
        assertTrue(machine.isIdle(state))
        assertFalse(machine.isSelecting(state))
        assertFalse(machine.isComposing(state))
    }

    @Test
    fun resolvePhase_returnsSelecting_when_candidate_selection_started_during_composing() {
        val state = CnT9SessionState(
            rawDigits = "94664",
            committedPrefix = "",
            materializedSegments = emptyList(),
            selectedCandidateIndex = 0,
            isCandidatesExpanded = false
        )

        val phase = machine.resolvePhase(
            state = state,
            lastEvent = CnT9StateEvent.CandidateSelectionStarted
        )

        assertEquals(CnT9UiPhase.SELECTING, phase)
        assertTrue(machine.isSelecting(state, CnT9StateEvent.CandidateSelectionStarted))
    }

    @Test
    fun resolvePhase_returnsSelecting_when_candidates_are_expanded() {
        val state = CnT9SessionState(
            rawDigits = "94664",
            committedPrefix = "",
            materializedSegments = emptyList(),
            selectedCandidateIndex = 0,
            isCandidatesExpanded = true
        )

        val phase = machine.resolvePhase(state = state)

        assertEquals(CnT9UiPhase.SELECTING, phase)
        assertTrue(machine.isSelecting(state))
    }

    @Test
    fun resolvePhase_returnsSelecting_when_selected_candidate_index_is_non_zero() {
        val state = CnT9SessionState(
            rawDigits = "94664",
            committedPrefix = "",
            materializedSegments = emptyList(),
            selectedCandidateIndex = 2,
            isCandidatesExpanded = false
        )

        val phase = machine.resolvePhase(state = state)

        assertEquals(CnT9UiPhase.SELECTING, phase)
        assertTrue(machine.isSelecting(state))
    }

    @Test
    fun resolvePhase_returnsComposing_when_session_active_but_not_selecting() {
        val state = CnT9SessionState(
            rawDigits = "94664",
            committedPrefix = "",
            materializedSegments = emptyList(),
            selectedCandidateIndex = 0,
            isCandidatesExpanded = false
        )

        val phase = machine.resolvePhase(state = state)

        assertEquals(CnT9UiPhase.COMPOSING, phase)
        assertTrue(machine.isComposing(state))
        assertFalse(machine.isSelecting(state))
        assertFalse(machine.isIdle(state))
    }

    @Test
    fun snapshot_reflects_phase_and_safe_focus_index() {
        val state = CnT9SessionState(
            rawDigits = "",
            committedPrefix = "",
            materializedSegments = listOf(
                CnT9MaterializedSegment(
                    syllable = "zhong",
                    digitChunk = "94664",
                    locked = true
                )
            ),
            focusedSegmentIndex = 0,
            selectedCandidateIndex = 0,
            isCandidatesExpanded = false,
            revision = 12L
        )

        val snapshot = machine.snapshot(state = state)

        assertEquals(CnT9UiPhase.COMPOSING, snapshot.phase)
        assertTrue(snapshot.isComposing)
        assertFalse(snapshot.hasRawDigits)
        assertTrue(snapshot.hasMaterializedSegments)
        assertEquals(0, snapshot.focusedSegmentIndex)
        assertEquals(0, snapshot.selectedCandidateIndex)
        assertFalse(snapshot.isCandidatesExpanded)
        assertEquals(12L, snapshot.revision)
    }
}
