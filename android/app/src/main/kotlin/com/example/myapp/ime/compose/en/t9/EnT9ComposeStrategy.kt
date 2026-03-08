package com.example.myapp.ime.compose.en.t9

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.EnglishComposeStrategy
import com.example.myapp.ime.compose.common.PendingCommitStrategy
import com.example.myapp.ime.compose.common.StrategyResult

class EnT9ComposeStrategy(
    private val sessionProvider: () -> ComposingSession,
    private val inputConnectionProvider: () -> InputConnection?,
    private val onPreviewStateChanged: () -> Unit
) : EnglishComposeStrategy, PendingCommitStrategy {

    private fun session(): ComposingSession = sessionProvider()
    private fun ic(): InputConnection? = inputConnectionProvider()

    private var englishPredictEnabled: Boolean = true

    private var multiTapKey: Char = ' '
    private var multiTapIndex = 0
    private val multiTapHandler = Handler(Looper.getMainLooper())
    private val multiTapConfirmRunnable = Runnable {
        confirmMultiTapInternalCommitIfAny()
    }

    private val multiTapMap = mapOf(
        '2' to "abc2",
        '3' to "def3",
        '4' to "ghi4",
        '5' to "jkl5",
        '6' to "mno6",
        '7' to "pqrs7",
        '8' to "tuv8",
        '9' to "wxyz9"
    )

    override fun getEnglishPredictEnabled(): Boolean = englishPredictEnabled

    override fun setEnglishPredictEnabled(enabled: Boolean) {
        if (englishPredictEnabled == enabled) return
        englishPredictEnabled = enabled

        session().clear()
        resetMultiTapState()
        ic()?.setComposingText("", 0)
        onPreviewStateChanged()
    }

    override fun onComposingInput(text: String): StrategyResult {
        return StrategyResult.Noop
    }

    override fun onT9Input(digit: String): StrategyResult {
        if (digit.isEmpty()) return StrategyResult.Noop

        return if (englishPredictEnabled) {
            resetMultiTapState()
            session().appendT9Digit(digit)
            onPreviewStateChanged()
            StrategyResult.SessionMutated
        } else {
            handleMultiTap(digit[0])
        }
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        // Not handled in English
    }

    override fun onEnter(ic: InputConnection?): StrategyResult = StrategyResult.Noop

    override fun flushPendingCommit(): StrategyResult = confirmMultiTap()

    override fun handleBackspaceInOwnBuffer(ic: InputConnection?): Boolean {
        if (!englishPredictEnabled) {
            if (resetMultiTapState()) {
                ic()?.setComposingText("", 0)
                onPreviewStateChanged()
                return true
            }
        }
        return false
    }

    fun getPendingMultiTapPreviewText(): String? {
        if (englishPredictEnabled) return null
        return getMultiTapComposingText()
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun handleMultiTap(digit: Char): StrategyResult {
        if (digit == '0' || digit == '1') {
            resetMultiTapState()
            onPreviewStateChanged()
            return StrategyResult.DirectCommit(digit.toString())
        }

        if (!multiTapMap.containsKey(digit)) {
            resetMultiTapState()
            onPreviewStateChanged()
            return StrategyResult.DirectCommit(digit.toString())
        }

        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)

        if (multiTapKey == digit) {
            val len = multiTapMap[digit]?.length ?: 1
            multiTapIndex = (multiTapIndex + 1) % len
        } else {
            confirmMultiTapInternalCommitIfAny()
            multiTapKey = digit
            multiTapIndex = 0
        }

        multiTapHandler.postDelayed(multiTapConfirmRunnable, 800)
        onPreviewStateChanged()
        return StrategyResult.Noop
    }

    private fun getMultiTapComposingText(): String {
        if (multiTapKey == ' ') return ""
        val chars = multiTapMap[multiTapKey] ?: return ""
        if (chars.isEmpty()) return ""
        return chars[multiTapIndex].toString()
    }

    private fun confirmMultiTapInternalCommitIfAny() {
        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)
        if (multiTapKey == ' ') {
            onPreviewStateChanged()
            return
        }

        val textToCommit = getMultiTapComposingText()
        resetMultiTapState()

        if (textToCommit.isNotEmpty()) {
            val ic = ic()
            ic?.setComposingText("", 0)
            ic?.commitText(textToCommit, 1)
        }

        onPreviewStateChanged()
    }

    private fun confirmMultiTap(): StrategyResult {
        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)
        if (multiTapKey == ' ') {
            onPreviewStateChanged()
            return StrategyResult.Noop
        }

        val textToCommit = getMultiTapComposingText()
        resetMultiTapState()
        onPreviewStateChanged()

        return if (textToCommit.isNotEmpty()) {
            StrategyResult.DirectCommit(textToCommit)
        } else {
            StrategyResult.Noop
        }
    }

    private fun resetMultiTapState(): Boolean {
        multiTapHandler.removeCallbacks(multiTapConfirmRunnable)
        if (multiTapKey == ' ') return false
        multiTapKey = ' '
        multiTapIndex = 0
        return true
    }
}
