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

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val sidebar = if (dictEngine.isLoaded && session.rawT9Digits.isNotEmpty()) {
            dictEngine.getPinyinPossibilities(session.rawT9Digits)
        } else {
            emptyList()
        }

        val candidates = ArrayList<Candidate>()
        if (dictEngine.isLoaded) {
            if (session.pinyinStack.isNotEmpty()) {
                candidates.addAll(
                    dictEngine.getSuggestionsFromPinyinStack(session.pinyinStack, session.rawT9Digits)
                )
            } else {
                val input = session.rawT9Digits
                if (input.isNotEmpty()) {
                    candidates.addAll(dictEngine.getSuggestions(input, isT9 = true, isChineseMode = true))
                }
            }
        }

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

        val t9PreviewText =
            if (session.rawT9Digits.isNotEmpty()) {
                T9PreviewBuilder.buildPreview(session.rawT9Digits, finalList)
            } else {
                null
            }

        promoteCandidateMatchingPreview(finalList, t9PreviewText)

        // CN-T9: UI composing preview line (includes committedPrefix + stack + preview)
        val stackUi = session.pinyinStack.joinToString("'") { it.lowercase() }
        val previewUi = t9PreviewText ?: ""
        val composingPreviewText = buildString {
            append(session.committedPrefix)
            if (stackUi.isNotEmpty()) append(stackUi)
            if (previewUi.isNotEmpty()) {
                if (stackUi.isNotEmpty()) append("'")
                append(previewUi)
            }
        }.takeIf { it.isNotBlank() }

        // CN-T9: Enter commits preview letters only (keep old behavior of t9PreviewCommitText)
        val enterCommitText = t9PreviewText
            ?.lowercase()
            ?.filter { it in 'a'..'z' }
            ?.takeIf { it.isNotEmpty() }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = sidebar,
            composingPreviewText = composingPreviewText,
            enterCommitText = enterCommitText
        )
    }

    private object T9PreviewBuilder {
        private val pinyinSet: Set<String> = PinyinTable.allPinyins.map { it.lowercase() }.toHashSet()
        private val maxPyLen: Int = PinyinTable.allPinyins.maxOfOrNull { it.length } ?: 8

        private fun repLetter(d: Char): Char {
            val list = T9Lookup.charsFromDigit(d)
            if (list.isNotEmpty() && list[0].isNotEmpty()) return list[0][0]
            return '?'
        }

        private fun lettersOnly(raw: String): String {
            val s = raw.lowercase()
            val sb = StringBuilder()
            for (ch in s) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }

        private fun segmentLettersForUi(letters: String): String {
            val s = letters.lowercase()
            if (s.isEmpty()) return ""

            val dp: ArrayList<List<String>?> = ArrayList<List<String>?>(s.length + 1)
            repeat(s.length + 1) { dp.add(null) }
            dp[0] = emptyList()

            for (i in 0..s.length) {
                val base = dp[i] ?: continue
                val remain = s.length - i
                val tryMax = minOf(maxPyLen, remain)
                for (l in 1..tryMax) {
                    val sub = s.substring(i, i + l)
                    if (!pinyinSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    if (old == null || cand.size > old.size) {
                        dp[i + l] = cand
                    }
                }
            }

            var bestCut = 0
            for (k in s.length downTo 0) {
                if (dp[k] != null) {
                    bestCut = k
                    break
                }
            }

            val parts = ArrayList<String>()
            val left = dp[bestCut] ?: emptyList()
            parts.addAll(left)

            if (bestCut < s.length) {
                parts.add(s.substring(bestCut))
            }

            return parts.joinToString("'")
        }

        fun buildPreview(digits: String, candidates: List<Candidate>): String? {
            if (digits.isEmpty()) return null

            if (digits.length == 1) {
                return repLetter(digits[0]).toString()
            }

            val bestPinyin = candidates.asSequence()
                .mapNotNull { it.pinyin }
                .firstOrNull { it.isNotBlank() }
                ?.lowercase()
                ?.trim()

            val prefixLetters = if (!bestPinyin.isNullOrEmpty()) {
                val letters = lettersOnly(bestPinyin)
                val sb = StringBuilder()
                sb.append(letters.take(digits.length))
                var i = sb.length
                while (i < digits.length) {
                    sb.append(repLetter(digits[i]))
                    i++
                }
                sb.toString()
            } else {
                buildString {
                    for (ch in digits) append(repLetter(ch))
                }
            }

            return segmentLettersForUi(prefixLetters)
        }

        fun normalizePreviewLetters(previewText: String): String {
            val sb = StringBuilder()
            for (ch in previewText.lowercase()) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }

        fun normalizePinyinLetters(pinyin: String): String {
            val sb = StringBuilder()
            for (ch in pinyin.lowercase()) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }
    }

    private fun promoteCandidateMatchingPreview(
        candidates: ArrayList<Candidate>,
        previewText: String?
    ) {
        if (previewText.isNullOrEmpty()) return
        if (candidates.isEmpty()) return

        val key = T9PreviewBuilder.normalizePreviewLetters(previewText)
        if (key.isEmpty()) return

        var bestIdx = -1
        var bestScore = Int.MIN_VALUE

        for (i in candidates.indices) {
            val c = candidates[i]
            val py = c.pinyin ?: continue
            val pyLetters = T9PreviewBuilder.normalizePinyinLetters(py)
            if (!pyLetters.startsWith(key)) continue

            val lenScore = -kotlin.math.abs(pyLetters.length - key.length)
            val freqScore = c.priority
            val score = lenScore * 1_000_000 + freqScore

            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        if (bestIdx <= 0) return

        val matched = candidates.removeAt(bestIdx)
        candidates.add(0, matched)
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
