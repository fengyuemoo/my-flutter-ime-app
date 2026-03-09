package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.ime.compose.common.ComposingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CnT9StateCoordinatorTest {

    @Test
    fun markCleared_resets_snapshot_to_idle() {
        val coordinator = CnT9StateCoordinator()

        coordinator.setSelectedCandidateIndex(3)
        coordinator.setCandidatesExpanded(true)
        coordinator.setFocusedSegment(0)

        val snapshot = coordinator.markCleared()

        assertEquals(CnT9UiPhase.IDLE, snapshot.phase)
        assertFalse(snapshot.isComposing)
        assertFalse(snapshot.hasRawDigits)
        assertFalse(snapshot.hasMaterializedSegments)
        assertNull(snapshot.focusedSegmentIndex)
        assertEquals(0, snapshot.selectedCandidateIndex)
        assertFalse(snapshot.isCandidatesExpanded)
    }

    @Test
    fun markCandidateCommitted_returns_committed_when_state_is_not_composing() {
        val coordinator = CnT9StateCoordinator()

        val snapshot = coordinator.markCandidateCommitted("中")

        assertEquals(CnT9UiPhase.COMMITTED, snapshot.phase)
        assertFalse(snapshot.isComposing)
        assertFalse(snapshot.hasRawDigits)
        assertFalse(snapshot.hasMaterializedSegments)
    }

    @Test
    fun markCandidateSelectionStarted_returns_selecting_when_state_is_active() {
        val coordinator = CnT9StateCoordinator()
        val session = ComposingSession().apply {
            appendT9Digit("9")
            appendT9Digit("4")
            appendT9Digit("6")
            appendT9Digit("6")
            appendT9Digit("4")
        }

        coordinator.syncFromSession(session)
        val snapshot = coordinator.markCandidateSelectionStarted()

        assertEquals(CnT9UiPhase.SELECTING, snapshot.phase)
        assertTrue(snapshot.isComposing)
        assertTrue(snapshot.hasRawDigits)
    }

    @Test
    fun syncFromSession_clears_stale_focus_and_selection_when_session_is_not_composing() {
        val coordinator = CnT9StateCoordinator()
        val activeSession = ComposingSession().apply {
            appendT9Digit("9")
            appendT9Digit("4")
            appendT9Digit("6")
            appendT9Digit("6")
            appendT9Digit("4")
            onPinyinSidebarClick("zhong", "94664")
        }

        coordinator.syncFromSession(
            session = activeSession,
            event = CnT9StateEvent.SidebarSegmentFocused(0)
        )
        coordinator.setSelectedCandidateIndex(4)
        coordinator.setCandidatesExpanded(true)

        val clearedSession = ComposingSession()
        val snapshot = coordinator.syncFromSession(
            session = clearedSession,
            event = CnT9StateEvent.CandidateCommitted("中")
        )

        assertEquals(CnT9UiPhase.COMMITTED, snapshot.phase)
        assertFalse(snapshot.isComposing)
        assertFalse(snapshot.hasRawDigits)
        assertFalse(snapshot.hasMaterializedSegments)
        assertNull(snapshot.focusedSegmentIndex)
        assertEquals(0, snapshot.selectedCandidateIndex)
        assertFalse(snapshot.isCandidatesExpanded)
    }

    @Test
    fun syncFromSession_preserves_valid_focused_segment_while_session_remains_active() {
        val coordinator = CnT9StateCoordinator()
        val session = ComposingSession().apply {
            appendT9Digit("9")
            appendT9Digit("4")
            appendT9Digit("6")
            appendT9Digit("6")
            appendT9Digit("4")
            onPinyinSidebarClick("zhong", "94664")
        }

        val snapshot = coordinator.syncFromSession(
            session = session,
            event = CnT9StateEvent.SidebarSegmentFocused(0)
        )

        assertEquals(CnT9UiPhase.COMPOSING, snapshot.phase)
        assertTrue(snapshot.isComposing)
        assertFalse(snapshot.hasRawDigits)
        assertTrue(snapshot.hasMaterializedSegments)
        assertEquals(0, snapshot.focusedSegmentIndex)
    }

    @Test
    fun syncFromSession_reuses_previous_focus_when_event_does_not_override_it() {
        val coordinator = CnT9StateCoordinator()
        val session = ComposingSession().apply {
            appendT9Digit("9")
            appendT9Digit("4")
            appendT9Digit("6")
            appendT9Digit("6")
            appendT9Digit("4")
            onPinyinSidebarClick("zhong", "94664")
        }

        coordinator.syncFromSession(
            session = session,
            event = CnT9StateEvent.SidebarSegmentFocused(0)
        )

        val snapshot = coordinator.syncFromSession(session)

        assertEquals(CnT9UiPhase.COMPOSING, snapshot.phase)
        assertEquals(0, snapshot.focusedSegmentIndex)
        assertTrue(snapshot.hasMaterializedSegments)
    }
}
