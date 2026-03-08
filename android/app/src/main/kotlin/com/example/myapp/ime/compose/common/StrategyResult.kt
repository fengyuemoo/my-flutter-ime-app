package com.example.myapp.ime.compose.common

/**
 * Represents the outcome of a strategy processing an input event.
 */
sealed class StrategyResult {
    /** The composing session was mutated. */
    object SessionMutated : StrategyResult()

    /** The strategy performed no action. */
    object Noop : StrategyResult()

    /** The strategy requests a direct commit of text, bypassing the session. */
    data class DirectCommit(val text: String) : StrategyResult()
}
