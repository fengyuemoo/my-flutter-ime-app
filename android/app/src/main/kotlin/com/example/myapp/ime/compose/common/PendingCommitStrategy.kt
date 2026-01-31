package com.example.myapp.ime.compose.common

import android.view.inputmethod.InputConnection

/**
 * A strategy that may have a pending state that needs to be committed before a mode switch.
 * Example: T9 multi-tap composing.
 */
interface PendingCommitStrategy {

    /**
     * Flushes any pending composing state (e.g., from multi-tap) and returns
     * the result of the action (e.g., a text to commit).
     */
    fun flushPendingCommit(): StrategyResult

    /**
     * Handles backspace within its own internal buffer if needed.
     * Returns true if the backspace was consumed, false otherwise.
     */
    fun handleBackspaceInOwnBuffer(ic: InputConnection?): Boolean

}
