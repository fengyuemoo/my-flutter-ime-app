package com.example.myapp.ime.compose.common

/**
 * Represents the outcome of a strategy processing an input event.
 * This allows the dispatcher to know what kind of UI update is required.
 */
sealed class StrategyResult {
    /** The composing session was mutated. A full UI refresh (composing + candidates) is needed. */
    object SessionMutated : StrategyResult()

    /** The strategy performed no action. */
    object Noop : StrategyResult()

    /** The strategy requests a direct commit of text, bypassing the session. */
    data class DirectCommit(val text: String) : StrategyResult()

    /**
     * The strategy requests updating the composing text directly, bypassing the session.
     * Used for modes like multi-tap where there's composing UI but no prediction session.
     */
    data class ComposingUpdate(val composingText: String) : StrategyResult()
}
