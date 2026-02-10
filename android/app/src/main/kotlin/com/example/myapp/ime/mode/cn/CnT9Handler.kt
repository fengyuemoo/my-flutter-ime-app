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

object CnT9Handler : ImeModeHandler {

    private val allPinyinsLower: List<String> = PinyinTable.allPinyins.map { it.lowercase() }
    private val pinyinSet: Set<String> = allPinyinsLower.toHashSet()
    private val maxPyLen: Int = allPinyinsLower.maxOfOrNull { it.length } ?: 8

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        // Sidebar: “下一节”候选（在 SQLiteDictionaryEngine.getPinyinPossibilities 里改成严格前缀语义）
        val sidebar = if (dictEngine.isLoaded && session.rawT9Digits.isNotEmpty()) {
            dictEngine.getPinyinPossibilities(session.rawT9Digits)
        } else {
            emptyList()
        }

        // --- Build preview segments (stack + auto) ---
        val stackSegs = session.pinyinStack.map { it.lowercase() }
        val autoSegs = if (dictEngine.isLoaded && session.rawT9Digits.isNotEmpty()) {
            T9PathBuilder.buildAutoSegments(
                digits = session.rawT9Digits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else {
            emptyList()
        }

        val fullSegs = ArrayList<String>(stackSegs.size + autoSegs.size).apply {
            addAll(stackSegs)
            addAll(autoSegs)
        }

        val previewPathText = fullSegs.joinToString("'").takeIf { it.isNotBlank() }

        // --- Candidates: use apostrophe path (strict match + fallback handled by engine),
        // then re-rank by how many “segments” truly match candidate's syllables.
        val candidates = ArrayList<Candidate>()
        if (dictEngine.isLoaded && !previewPathText.isNullOrBlank()) {
            candidates.addAll(
                dictEngine.getSuggestions(
                    input = previewPathText,
                    isT9 = false,
                    isChineseMode = true
                )
            )
        }

        // Apply single-char filter if enabled.
        val filtered = if (singleCharMode) {
            candidates.filter { it.word.length == 1 }
        } else {
            candidates
        }

        val finalList =
            if (filtered.isEmpty() && session.rawT9Digits.isNotEmpty()) {
                arrayListOf(Candidate(session.rawT9Digits, session.rawT9Digits, 0, 0, 0))
            } else {
                ArrayList(filtered)
            }

        // Re-rank (strictly by segment match count first, then freq)
        promoteCandidatesMatchingSegments(finalList, fullSegs)

        // UI composing preview line: committedPrefix + full preview segments
        val composingPreviewCore = previewPathText ?: ""
        val composingPreviewText = buildString {
            append(session.committedPrefix)
            if (composingPreviewCore.isNotEmpty()) append(composingPreviewCore)
        }.takeIf { it.isNotBlank() }

        // Enter commits preview letters only (keep old behavior: letters only)
        val enterCommitText = composingPreviewCore
            .lowercase()
            .filter { it in 'a'..'z' }
            .takeIf { it.isNotEmpty() }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = sidebar,
            composingPreviewText = composingPreviewText,
            enterCommitText = enterCommitText
        )
    }

    private object T9PathBuilder {
        private fun defaultLetterForDigit(d: Char): String {
            val list = T9Lookup.charsFromDigit(d)
            val s = list.firstOrNull()?.lowercase()?.trim()
            return if (!s.isNullOrEmpty()) s.substring(0, 1) else "?"
        }

        private fun splitDigitsByCuts(digits: String, manualCuts: List<Int>): List<String> {
            if (digits.isEmpty()) return emptyList()

            val cuts = manualCuts
                .asSequence()
                .filter { it in 1 until digits.length } // interior cuts only
                .distinct()
                .sorted()
                .toList()

            if (cuts.isEmpty()) return listOf(digits)

            val out = ArrayList<String>()
            var prev = 0
            for (c in cuts) {
                if (c > prev) out.add(digits.substring(prev, c))
                prev = c
            }
            if (prev < digits.length) out.add(digits.substring(prev))
            return out
        }

        fun buildAutoSegments(digits: String, manualCuts: List<Int>, dict: Dictionary): List<String> {
            if (digits.isEmpty()) return emptyList()

            val parts = splitDigitsByCuts(digits, manualCuts)
            val out = ArrayList<String>()

            for (part in parts) {
                var remain = part
                while (remain.isNotEmpty()) {
                    val opts = dict.getPinyinPossibilities(remain)
                    val chosen = opts.firstOrNull()?.lowercase()?.trim()
                        ?: defaultLetterForDigit(remain[0])

                    out.add(chosen)

                    val consume = T9Lookup.encodeLetters(chosen)
                        .length
                        .coerceAtLeast(1)
                        .coerceAtMost(remain.length)

                    remain = remain.substring(consume)
                }
            }

            return out
        }
    }

    private fun promoteCandidatesMatchingSegments(
        candidates: ArrayList<Candidate>,
        segments: List<String>
    ) {
        if (candidates.isEmpty()) return
        if (segments.isEmpty()) return

        val matchCount = HashMap<Candidate, Int>(candidates.size)

        for (c in candidates) {
            val py = c.pinyin?.lowercase()?.trim()
            val syllables = if (!py.isNullOrEmpty()) splitConcatPinyinToSyllables(py) else emptyList()
            val cnt = if (syllables.isEmpty()) 0 else countMatchedSegments(segments, syllables)
            matchCount[c] = cnt
        }

        candidates.sortWith(
            compareByDescending<Candidate> { matchCount[it] ?: 0 }
                .thenByDescending { it.priority }
                .thenBy { it.word }
        )
    }

    private fun countMatchedSegments(segments: List<String>, syllables: List<String>): Int {
        val n = minOf(segments.size, syllables.size)
        var matched = 0

        for (i in 0 until n) {
            val seg = segments[i].lowercase()
            val syl = syllables[i].lowercase()

            val ok = when {
                // 完整音节：必须完全相等
                pinyinSet.contains(seg) -> syl == seg

                // zh/ch/sh：作为“完成一个节”，但只约束该音节的声母前缀
                seg == "zh" || seg == "ch" || seg == "sh" -> syl.startsWith(seg)

                // 单字母节：约束该音节首字母
                seg.length == 1 && seg[0] in 'a'..'z' -> syl.startsWith(seg)

                else -> false
            }

            if (!ok) break
            matched++
        }

        return matched
    }

    private fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        if (rawLower.isEmpty()) return emptyList()
        if (!rawLower.all { it in 'a'..'z' }) return emptyList()
        if (maxPyLen <= 0) return emptyList()

        val out = ArrayList<String>()
        var i = 0
        while (i < rawLower.length) {
            val remain = rawLower.length - i
            val tryMax = minOf(maxPyLen, remain)

            var matched: String? = null
            var l = tryMax
            while (l >= 1) {
                val sub = rawLower.substring(i, i + l)
                if (pinyinSet.contains(sub)) {
                    matched = sub
                    break
                }
                l--
            }

            if (matched == null) return emptyList()
            out.add(matched)
            i += matched.length
        }
        return out
    }
}

/**
 * Strong-isolated candidate engine for CN-T9.
 */
class CnT9CandidateEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val dictEngine: Dictionary,
    private val session: ComposingSession,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val updateComposingView: () -> Unit,
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

    fun syncFilterButton() {
        ui.setFilterButton(isSingleCharMode)
    }

    fun toggleSingleCharMode() {
        isSingleCharMode = !isSingleCharMode
        syncFilterButton()
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

    fun updateCandidates() {
        syncFilterButton()
        currentCandidates.clear()

        if (!session.isComposing()) {
            composingPreviewOverride = null
            enterCommitTextOverride = null

            if (isExpanded) isExpanded = false
            renderIdleUi()
            return
        }

        val out = CnT9Handler.build(
            session = session,
            dictEngine = dictEngine,
            singleCharMode = isSingleCharMode
        )

        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride = out.enterCommitText

        currentCandidates = ArrayList(out.candidates)
        renderComposingUi(out)
    }

    fun handleSpaceKey() {
        if (currentCandidates.isNotEmpty()) {
            commitCandidateAt(0)
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        if (currentCandidates.isEmpty()) return false
        commitCandidateAt(0)
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

        val cand = currentCandidates[index]

        if (isRawCommitMode()) {
            commitRaw(cand.word)
            clearComposing()
            return
        }

        when (val r = session.pickCandidate(
            cand = cand,
            useT9Layout = true,
            isChinese = true
        )) {
            is ComposingSession.PickResult.Commit -> {
                commitRaw(r.text)
                clearComposing()
            }

            is ComposingSession.PickResult.Updated -> {
                updateCandidates()
                updateComposingView()
            }
        }
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
}
