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
    private val isRawCommitMode: () -> Boolean,
    private val userChoiceStore: CnT9UserChoiceStore? = null,
    private val contextWindow: CnT9ContextWindow? = null    // ← 新增
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
            singleCharMode = isSingleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow = contextWindow           // ← 传入
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

        // ── 记录用户学习 ──────────────────────────────────────────
        recordUserChoice(cand)

        if (isRawCommitMode()) {
            resetUiSelectionToTop()
            commitRaw(cand.word)
            // ── 上下文记录 ────────────────────────────────────────
            contextWindow?.record(cand.word)
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
                    contextWindow?.record(cand.word)    // ← 上屏后记录上下文
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
                    contextWindow?.record(cand.word)    // ← 上屏后记录上下文
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
        contextWindow?.record(cand.word)                // ← 上屏后记录上下文
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

    // ── 用户学习 ───────────────────────────────────────────────────

    private fun recordUserChoice(cand: Candidate) {
        val store = userChoiceStore ?: return
        val pinyinKey = (cand.pinyin ?: cand.input)
            .trim()
            .lowercase(Locale.ROOT)
            .replace("'", "'")
        store.recordChoice(pinyinKey, cand.word)
    }

    // ── 首选上屏置信度模型 ─────────────────────────────────────────

    private object ConfidenceThreshold {
        const val AUTO_COMMIT = 60
    }

    private fun computeFirstCandidateConfidence(
        preferredIndex: Int,
        cand: Candidate
    ): Int {
        if (!session.isComposing()) return 0

        var score = 0

        if (preferredIndex > 0) return 100
        if (currentCandidates.size == 1) return 100
        if (isRawCommitMode()) return 100

        if (session.rawT9Digits.isEmpty() && session.pinyinStack.isNotEmpty()) {
            return 90
        }

        val rawLen = session.rawT9Digits.length
        if (rawLen == 1 && session.pinyinStack.isEmpty()) return 20

        val preview = normalizedEnterPreview()
        val candPreview = normalizedCandidatePreview(cand)
        val expectedSyllables = estimateCurrentComposingSyllables()
        val consumeSyllables = resolveConsumeSyllables(cand)

        if (preview != null && candPreview.isNotEmpty()) {
            when {
                candPreview == preview -> score += 40
                preview.startsWith(candPreview) -> score += 25
                else -> score += 5
            }
        }

        val minRequiredSyllables = min(2, expectedSyllables)
        if (consumeSyllables >= minRequiredSyllables && cand.word.length > 1) {
            score += 20
        } else if (cand.word.length == 1 && expectedSyllables == 1) {
            score += 15
        }

        if (session.pinyinStack.isNotEmpty()) {
            score += 10
        }

        if (rawLen >= 4) score += 10

        val userBoost = userChoiceStore?.getBoost(
            pinyinKey = normalizedCandidatePreview(cand),
            word = cand.word
        ) ?: 0
        if (userBoost > 0) score += minOf(userBoost / 10, 10)

        // 上下文加分也影响置信度
        val ctxBoost = contextWindow?.getContextBoost(cand.word) ?: 0
        if (ctxBoost > 0) score += 5

        return score.coerceIn(0, 100)
    }

    private fun shouldCommitPreferredCandidateOnEnter(
        preferredIndex: Int,
        cand: Candidate
    ): Boolean {
        val confidence = computeFirstCandidateConfidence(preferredIndex, cand)
        return confidence >= ConfidenceThreshold.AUTO_COMMIT
    }

    // ── 内部辅助方法 ───────────────────────────────────────────────

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
            CnT9SentencePlanner.planAll(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else emptyList()

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
            val next = CnT9SentencePlanner.decodeNextSegment(
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
