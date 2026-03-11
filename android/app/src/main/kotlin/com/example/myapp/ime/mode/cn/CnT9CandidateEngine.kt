package com.example.myapp.ime.mode.cn

import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.ImeModeHandler
import com.example.myapp.ime.ui.ImeUi
import java.util.Locale
import kotlin.math.min

class CnT9CandidateEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val dictEngine: Dictionary,
    private val session: ComposingSession,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val isRawCommitMode: () -> Boolean
) {
    private var isExpanded: Boolean = false
    private var isSingleCharMode: Boolean = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()
    private var composingPreviewOverride: String? = null
    private var enterCommitTextOverride: String? = null

    fun getComposingPreviewOverride(): String? = composingPreviewOverride
    fun getEnterCommitTextOverride(): String? = enterCommitTextOverride

    private fun isDebuggableApp(): Boolean {
        return (ui.rootView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun resetUiSelectionToTop() {
        ui.resetSelectedCandidateIndex()
    }

    private fun preferredCandidateIndexOrNull(): Int? {
        if (currentCandidates.isEmpty()) return null
        val selected = ui.getSelectedCandidateIndex()
        return if (selected in currentCandidates.indices) selected else 0
    }

    private fun preferredCandidateOrNull(): Candidate? {
        val idx = preferredCandidateIndexOrNull() ?: return null
        return currentCandidates.getOrNull(idx)
    }

    fun syncFilterButton() {
        ui.setFilterButton(isSingleCharMode)
    }

    fun toggleSingleCharMode() {
        isSingleCharMode = !isSingleCharMode
        syncFilterButton()
        resetUiSelectionToTop()
        updateCandidates()
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
        ui.setExpanded(isExpanded, session.isComposing())
    }

    private fun renderIdleUi() {
        ui.showIdleState()
        ui.setExpanded(false, isComposing = false)
        keyboardController.updateSidebar(emptyList())
    }

    private fun renderComposingUi(out: ImeModeHandler.Output) {
        syncFilterButton()
        ui.showComposingState(isExpanded = isExpanded)
        ui.setExpanded(isExpanded, isComposing = true)
        keyboardController.updateSidebar(out.pinyinSidebar)
        ui.setCandidates(currentCandidates)
    }

    private fun clearOutputOverrides() {
        composingPreviewOverride = null
        enterCommitTextOverride = null
    }

    private fun applyBuildOutput(out: ImeModeHandler.Output) {
        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride = out.enterCommitText
        currentCandidates = ArrayList(out.candidates)
        resetUiSelectionToTop()
        renderComposingUi(out)
    }

    fun updateCandidates() {
        syncFilterButton()
        currentCandidates.clear()

        if (!session.isComposing()) {
            clearOutputOverrides()
            resetUiSelectionToTop()
            if (isExpanded) isExpanded = false
            renderIdleUi()
            return
        }

        val out = CnT9Handler.build(
            session = session,
            dictEngine = dictEngine,
            singleCharMode = isSingleCharMode
        )

        applyBuildOutput(out)
    }

    fun handleSpaceKey() {
        val preferred = preferredCandidateIndexOrNull()
        if (preferred != null) {
            commitCandidateAt(preferred)
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        val preferredIndex = preferredCandidateIndexOrNull() ?: return false
        val preferredCandidate = preferredCandidateOrNull() ?: return false

        if (!shouldCommitPreferredCandidateOnEnter(preferredIndex, preferredCandidate)) {
            return false
        }

        commitCandidateAt(preferredIndex)
        return true
    }

    fun commitCandidateAt(index: Int) {
        if (index !in 0 until currentCandidates.size) {
            val msg = "Candidate index out of range: CN_T9 index=$index size=${currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("CnT9CandidateEngine", msg)
                throw AssertionError(msg)
            }
            return
        }

        ui.setSelectedCandidateIndex(index)
        val cand = currentCandidates[index]

        if (isRawCommitMode()) {
            resetUiSelectionToTop()
            commitRaw(cand.word)
            clearComposing()
            return
        }

        val consumeSyllables = resolveConsumeSyllables(cand).coerceAtLeast(1)
        val stackSizeBeforeMaterialize = session.pinyinStack.size

        materializeSegmentsIfNeeded(targetSyllables = consumeSyllables)

        val availableStack = session.pinyinStack.size
        if (availableStack > 0) {
            val consume = consumeSyllables.coerceAtMost(availableStack)
            val pickCand = cand.copy(pinyinCount = consume)

            when (val result = session.pickCandidate(
                cand = pickCand,
                useT9Layout = true,
                isChinese = true,
                restorePinyinCountOnUndo = stackSizeBeforeMaterialize.coerceAtMost(consume)
            )) {
                is ComposingSession.PickResult.Commit -> {
                    resetUiSelectionToTop()
                    commitRaw(result.text)
                    clearComposing()
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    updateCandidates()
                }
            }
            return
        }

        if (session.rawT9Digits.isNotEmpty()) {
            val consumeDigits = resolveDigitsToConsume(cand)
                .coerceAtLeast(1)
                .coerceAtMost(session.rawT9Digits.length)

            val pickCand = cand.copy(pinyinCount = 0)

            when (val result = session.pickCandidate(
                cand = pickCand,
                useT9Layout = true,
                isChinese = true,
                t9ConsumedDigitsCount = consumeDigits
            )) {
                is ComposingSession.PickResult.Commit -> {
                    resetUiSelectionToTop()
                    commitRaw(result.text)
                    clearComposing()
                }
                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    updateCandidates()
                }
            }
            return
        }

        resetUiSelectionToTop()
        commitRaw(cand.word)
        clearComposing()
    }

    fun commitCandidate(cand: Candidate) {
        val idx = currentCandidates.indexOf(cand)
        if (idx < 0) {
            val msg = "Candidate not in current CN_T9 list: cand=$cand size=${currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("CnT9CandidateEngine", msg)
                throw AssertionError(msg)
            }
            return
        }
        commitCandidateAt(idx)
    }

    private fun shouldCommitPreferredCandidateOnEnter(
        preferredIndex: Int,
        cand: Candidate
    ): Boolean {
        if (!session.isComposing()) return false
        if (preferredIndex > 0) return true
        if (currentCandidates.size == 1) return true
        if (isRawCommitMode()) return true

        if (session.rawT9Digits.isEmpty()) {
            return session.pinyinStack.isNotEmpty()
        }

        val preview = normalizedEnterPreview() ?: return false
        val candPreview = normalizedCandidatePreview(cand)
        val expectedSyllables = estimateCurrentComposingSyllables()
        val consumeSyllables = resolveConsumeSyllables(cand)

        if (candPreview.isNotEmpty() && candPreview == preview) return true

        if (candPreview.isNotEmpty() &&
            preview.startsWith(candPreview) &&
            cand.word.length > 1 &&
            consumeSyllables >= min(2, expectedSyllables)
        ) return true

        if (session.pinyinStack.isNotEmpty() &&
            session.rawT9Digits.length <= 2 &&
            cand.word.length > 1 &&
            consumeSyllables >= min(2, expectedSyllables)
        ) return true

        return false
    }

    private fun normalizedEnterPreview(): String? {
        fun normalizeSegment(seg: String): String {
            return seg.trim()
                .lowercase(Locale.ROOT)
                .replace("'", "")
                .replace("ü", "v")
                .filter { it in 'a'..'z' || it == 'v' }
        }

        val stackSegs = session.pinyinStack
            .map { normalizeSegment(it) }
            .filter { it.isNotEmpty() }

        val rawDigits = session.rawT9Digits

        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(          // ← 改为 CnT9SentencePlanner
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else {
            emptyList()
        }

        val bestSegments = when {
            stackSegs.isEmpty() && autoPlans.isEmpty() -> emptyList()
            autoPlans.isEmpty() -> stackSegs
            else -> stackSegs + autoPlans.first().segments
                .map { normalizeSegment(it) }
                .filter { it.isNotEmpty() }
        }

        val normalized = bestSegments.joinToString("")
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun normalizedCandidatePreview(cand: Candidate): String {
        return (cand.pinyin ?: cand.input)
            .trim()
            .lowercase(Locale.ROOT)
            .replace("ü", "v")
            .replace("'", "")
    }

    private fun estimateCurrentComposingSyllables(): Int {
        return session.pinyinStack.size + estimateRawDigitSyllables(session.rawT9Digits)
    }

    private fun estimateRawDigitSyllables(digits: String): Int {
        if (digits.isEmpty()) return 0
        return when {
            digits.length <= 2 -> 1
            digits.length <= 5 -> 2
            digits.length <= 8 -> 3
            digits.length <= 11 -> 4
            digits.length <= 14 -> 5
            else -> (digits.length + 2) / 3
        }
    }

    private fun materializeSegmentsIfNeeded(targetSyllables: Int) {
        if (!dictEngine.isLoaded) return

        while (session.pinyinStack.size < targetSyllables && session.rawT9Digits.isNotEmpty()) {
            val next = CnT9SentencePlanner.decodeNextSegment(  // ← 改为 CnT9SentencePlanner
                digits = session.rawT9Digits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            ) ?: break

            val code = T9Lookup.encodeLetters(next)
            if (code.isEmpty()) break

            session.onPinyinSidebarClick(next, code)
        }
    }

    private fun resolveConsumeSyllables(cand: Candidate): Int {
        if (cand.pinyinCount > 0) return cand.pinyinCount
        if (cand.syllables > 0) return cand.syllables

        cand.pinyin?.let { py ->
            val split = splitCandidatePinyin(py.lowercase(Locale.ROOT).replace("'", ""))
            if (split > 0) return split
        }

        val split = splitCandidatePinyin(cand.input.lowercase(Locale.ROOT).replace("'", ""))
        if (split > 0) return split

        return 1
    }

    private fun resolveDigitsToConsume(cand: Candidate): Int {
        val source = (cand.pinyin ?: cand.input)
            .lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("ü", "v")

        val digits = T9Lookup.encodeLetters(source)
        if (digits.isNotEmpty()) return digits.length

        return T9Lookup.encodeLetters(cand.input.replace("'", "")).length
    }

    private fun splitCandidatePinyin(raw: String): Int {
        if (raw.isBlank()) return 0

        val all = PinyinTable.allPinyins
            .map { it.lowercase(Locale.ROOT).replace("ü", "v") }
            .toHashSet()

        val normalized = raw.replace("ü", "v")
        val maxLen = all.maxOfOrNull { it.length } ?: 8

        var i = 0
        var count = 0

        while (i < normalized.length) {
            val remain = normalized.length - i
            val tryMax = min(maxLen, remain)

            var found = false
            for (len in tryMax downTo 1) {
                val sub = normalized.substring(i, i + len)
                if (all.contains(sub)) {
                    i += len
                    count++
                    found = true
                    break
                }
            }

            if (!found) return 0
        }

        return count
    }
}
