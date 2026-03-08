package com.example.myapp.ime.compose.cn.t9

enum class CnT9UiPhase {
    IDLE,
    COMPOSING,
    SELECTING,
    COMMITTED
}

sealed interface CnT9StateEvent {
    data class DigitsAppended(val digits: String) : CnT9StateEvent
    object BackspacePressed : CnT9StateEvent
    data class SidebarSegmentFocused(val index: Int) : CnT9StateEvent
    object CandidateSelectionStarted : CnT9StateEvent
    data class CandidateCommitted(val text: String) : CnT9StateEvent
    object Cleared : CnT9StateEvent
}

data class CnT9StateSnapshot(
    val phase: CnT9UiPhase,
    val isComposing: Boolean,
    val hasRawDigits: Boolean,
    val hasMaterializedSegments: Boolean,
    val focusedSegmentIndex: Int?,
    val selectedCandidateIndex: Int,
    val isCandidatesExpanded: Boolean,
    val revision: Long
)
