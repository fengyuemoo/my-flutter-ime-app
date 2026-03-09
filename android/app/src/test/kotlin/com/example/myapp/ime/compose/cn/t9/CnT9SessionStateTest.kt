package com.example.myapp.ime.compose.cn.t9

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CnT9SessionStateTest {

    @Test
    fun sanitized_clears_focus_selection_and_expansion_when_not_composing() {
        val state = CnT9SessionState(
            rawDigits = "",
            committedPrefix = "",
            materializedSegments = emptyList(),
            manualCuts = setOf(1, 2),
            focusedSegmentIndex = 3,
            selectedCandidateIndex = 5,
            isCandidatesExpanded = true,
            revision = 7L
        )

        val sanitized = state.sanitized()

        assertFalse(sanitized.isComposing())
        assertNull(sanitized.focusedSegmentIndex)
        assertEquals(0, sanitized.selectedCandidateIndex)
        assertFalse(sanitized.isCandidatesExpanded)
        assertTrue(sanitized.manualCuts.isEmpty())
        assertEquals(7L, sanitized.revision)
    }

    @Test
    fun sanitized_preserves_selecting_state_when_raw_digits_still_exist() {
        val state = CnT9SessionState(
            rawDigits = "94a664",
            committedPrefix = "",
            materializedSegments = emptyList(),
            manualCuts = setOf(1, 3, 9),
            focusedSegmentIndex = null,
            selectedCandidateIndex = 2,
            isCandidatesExpanded = true
        )

        val sanitized = state.sanitized()

        assertTrue(sanitized.isComposing())
        assertEquals("94664", sanitized.rawDigits)
        assertEquals(setOf(1, 3), sanitized.manualCuts)
        assertEquals(2, sanitized.selectedCandidateIndex)
        assertTrue(sanitized.isCandidatesExpanded)
        assertNull(sanitized.focusedSegmentIndex)
    }

    @Test
    fun sanitized_preserves_valid_focus_while_composing_with_materialized_segments() {
        val state = CnT9SessionState(
            rawDigits = "",
            committedPrefix = "",
            materializedSegments = listOf(
                CnT9MaterializedSegment(
                    syllable = " ZHONG ",
                    digitChunk = "94a664",
                    locked = true,
                    localCuts = setOf(0, 2, 8)
                ),
                CnT9MaterializedSegment(
                    syllable = " GUO ",
                    digitChunk = "486",
                    locked = true,
                    localCuts = setOf(1, 3, 5)
                )
            ),
            manualCuts = emptySet(),
            focusedSegmentIndex = 1,
            selectedCandidateIndex = -4,
            isCandidatesExpanded = true
        )

        val sanitized = state.sanitized()

        assertTrue(sanitized.isComposing())
        assertEquals(listOf("zhong", "guo"), sanitized.materializedSegments.map { it.syllable })
        assertEquals(listOf("94664", "486"), sanitized.materializedSegments.map { it.digitChunk })
        assertEquals(setOf(2), sanitized.materializedSegments[0].localCuts)
        assertEquals(setOf(1, 3), sanitized.materializedSegments[1].localCuts)
        assertEquals(1, sanitized.focusedSegmentIndex)
        assertEquals(0, sanitized.selectedCandidateIndex)
        assertTrue(sanitized.isCandidatesExpanded)
    }

    @Test
    fun sanitized_does_not_force_idle_cleanup_when_committed_prefix_keeps_session_active() {
        val state = CnT9SessionState(
            rawDigits = "",
            committedPrefix = "中",
            materializedSegments = emptyList(),
            manualCuts = emptySet(),
            focusedSegmentIndex = null,
            selectedCandidateIndex = 4,
            isCandidatesExpanded = true
        )

        val sanitized = state.sanitized()

        assertTrue(sanitized.isComposing())
        assertEquals("中", sanitized.committedPrefix)
        assertEquals(4, sanitized.selectedCandidateIndex)
        assertTrue(sanitized.isCandidatesExpanded)
    }
}
