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

object CnT9Handler : ImeModeHandler {

    private val allPinyinsLower: List<String> = PinyinTable.allPinyins.map { it.lowercase(Locale.ROOT) }
    private val pinyinSet: Set<String> = allPinyinsLower.toHashSet()
    private val maxPyLen: Int = allPinyinsLower.maxOfOrNull { it.length } ?: 8

    private const val MAX_DISPLAY_CANDIDATES = 120
    private const val MAX_ALT_FIRST_SEGMENTS = 12
    private const val MAX_QUERY_PER_PLAN = 80

    private data class PathPlan(
        val rank: Int,
        val segments: List<String>
    ) {
        val text: String = segments.joinToString("'")
    }

    private data class CandidateScore(
        val fullExactSegments: Int,
        val prefixMatchedSegments: Int,
        val uncoveredDigits: Int,
        val priority: Int,
        val syllables: Int,
        val wordLength: Int,
        val inputLength: Int
    )

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {
        val rawDigits = session.rawT9Digits
        val stackSegs = session.pinyinStack.map { it.lowercase(Locale.ROOT) }

        val sidebar = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            dictEngine.getPinyinPossibilities(rawDigits)
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } else {
            emptyList()
        }

        val mainAutoSegs = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            SentenceDecoder.decodeAllSegments(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else {
            emptyList()
        }

        val mainSegments = buildList {
            addAll(stackSegs)
            addAll(mainAutoSegs)
        }

        val plans = buildPlans(
            dict = dictEngine,
            stackSegs = stackSegs,
            rawDigits = rawDigits,
            manualCuts = session.t9ManualCuts,
            sidebar = sidebar,
            mainAutoSegs = mainAutoSegs
        )

        val recalled = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            queryCandidatesByPlans(dictEngine, plans)
        } else {
            emptyList()
        }

        val filtered = if (singleCharMode) {
            recalled.filter { it.word.length == 1 }
        } else {
            recalled
        }

        val finalList = ArrayList<Candidate>()
        finalList.addAll(filtered)

        rerankCandidates(
            candidates = finalList,
            plans = plans,
            rawDigits = rawDigits
        )

        if (finalList.size > MAX_DISPLAY_CANDIDATES) {
            finalList.subList(MAX_DISPLAY_CANDIDATES, finalList.size).clear()
        }

        if (finalList.isEmpty() && rawDigits.isNotEmpty()) {
            finalList.add(
                Candidate(
                    word = rawDigits,
                    input = rawDigits,
                    priority = 0,
                    matchedLength = 0,
                    pinyinCount = 0,
                    pinyin = null,
                    syllables = 0,
                    acronym = null
                )
            )
        }

        val previewCore = buildPreviewCore(
            topCandidate = finalList.firstOrNull(),
            fallbackSegments = mainSegments
        )

        val composingPreviewText = buildString {
            append(session.committedPrefix)
            if (previewCore.isNotEmpty()) {
                append(previewCore)
            }
        }.takeIf { it.isNotBlank() }

        val enterCommitText = previewCore
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' }
            .takeIf { it.isNotEmpty() }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = sidebar,
            composingPreviewText = composingPreviewText,
            enterCommitText = enterCommitText
        )
    }

    private fun buildPlans(
        dict: Dictionary,
        stackSegs: List<String>,
        rawDigits: String,
        manualCuts: List<Int>,
        sidebar: List<String>,
        mainAutoSegs: List<String>
    ): List<PathPlan> {
        val plans = ArrayList<PathPlan>()

        val mainPlanSegments = buildList {
            addAll(stackSegs)
            addAll(mainAutoSegs)
        }
        if (mainPlanSegments.isNotEmpty()) {
            plans.add(PathPlan(rank = 0, segments = mainPlanSegments))
        }

        if (!dict.isLoaded || rawDigits.isEmpty() || sidebar.isEmpty()) {
            return plans
        }

        val mainFirst = mainAutoSegs.firstOrNull()?.lowercase(Locale.ROOT)
        var altRank = 1

        for (first in sidebar) {
            if (altRank > MAX_ALT_FIRST_SEGMENTS) break

            val firstSeg = first.lowercase(Locale.ROOT).trim()
            if (firstSeg.isEmpty()) continue
            if (firstSeg == mainFirst) continue

            val firstCode = T9Lookup.encodeLetters(firstSeg)
            if (firstCode.isEmpty()) continue
            if (firstCode.length > rawDigits.length) continue

            val remainDigits = rawDigits.substring(firstCode.length)
            val shiftedCuts = shiftCutsAfterConsume(
                cuts = manualCuts,
                consumedLen = firstCode.length,
                remainLen = remainDigits.length
            )

            val tailSegs = SentenceDecoder.decodeAllSegments(
                digits = remainDigits,
                manualCuts = shiftedCuts,
                dict = dict
            )

            val segs = buildList {
                addAll(stackSegs)
                add(firstSeg)
                addAll(tailSegs)
            }

            if (segs.isNotEmpty()) {
                plans.add(PathPlan(rank = altRank, segments = segs))
                altRank++
            }
        }

        return plans
    }

    private fun shiftCutsAfterConsume(
        cuts: List<Int>,
        consumedLen: Int,
        remainLen: Int
    ): List<Int> {
        if (cuts.isEmpty()) return emptyList()

        val out = ArrayList<Int>()
        for (c in cuts) {
            if (c <= consumedLen) continue
            val shifted = c - consumedLen
            if (shifted in 1 until remainLen) {
                out.add(shifted)
            }
        }
        out.sort()
        return out
    }

    private fun queryCandidatesByPlans(
        dict: Dictionary,
        plans: List<PathPlan>
    ): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()

        for (plan in plans) {
            if (plan.text.isBlank()) continue

            val list = dict.getSuggestions(
                input = plan.text,
                isT9 = false,
                isChineseMode = true
            )

            var taken = 0
            for (cand in list) {
                if (!out.containsKey(cand.word)) {
                    out[cand.word] = cand
                    taken++
                    if (taken >= MAX_QUERY_PER_PLAN) break
                }
            }
        }

        return out.values.toList()
    }

    private fun rerankCandidates(
        candidates: ArrayList<Candidate>,
        plans: List<PathPlan>,
        rawDigits: String
    ) {
        if (candidates.isEmpty() || plans.isEmpty()) return

        val scoreCache = HashMap<Candidate, CandidateScore?>(candidates.size)
        for (cand in candidates) {
            scoreCache[cand] = bestScoreForCandidate(cand, plans, rawDigits)
        }

        candidates.sortWith(
            compareByDescending<Candidate> { scoreCache[it]?.fullExactSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.prefixMatchedSegments ?: 0 }
                .thenBy { scoreCache[it]?.uncoveredDigits ?: Int.MAX_VALUE }
                .thenByDescending { scoreCache[it]?.priority ?: it.priority }
                .thenByDescending { scoreCache[it]?.syllables ?: it.syllables }
                .thenByDescending { scoreCache[it]?.wordLength ?: it.word.length }
                .thenByDescending { scoreCache[it]?.inputLength ?: it.input.length }
                .thenBy { it.word }
        )
    }

    private fun bestScoreForCandidate(
        cand: Candidate,
        plans: List<PathPlan>,
        rawDigits: String
    ): CandidateScore? {
        val syllables = resolveCandidateSyllables(cand)
        if (syllables.isEmpty()) return null

        var best: CandidateScore? = null
        for (plan in plans) {
            val score = scoreAgainstPlan(cand, plan, syllables, rawDigits)
            if (best == null || isBetter(score, best!!)) {
                best = score
            }
        }
        return best
    }

    private fun isBetter(a: CandidateScore, b: CandidateScore): Boolean {
        return when {
            a.fullExactSegments != b.fullExactSegments -> a.fullExactSegments > b.fullExactSegments
            a.prefixMatchedSegments != b.prefixMatchedSegments -> a.prefixMatchedSegments > b.prefixMatchedSegments
            a.uncoveredDigits != b.uncoveredDigits -> a.uncoveredDigits < b.uncoveredDigits
            a.priority != b.priority -> a.priority > b.priority
            a.syllables != b.syllables -> a.syllables > b.syllables
            a.wordLength != b.wordLength -> a.wordLength > b.wordLength
            else -> a.inputLength > b.inputLength
        }
    }

    private fun scoreAgainstPlan(
        cand: Candidate,
        plan: PathPlan,
        candidateSyllables: List<String>,
        rawDigits: String
    ): CandidateScore {
        val planSegs = plan.segments
        val n = min(planSegs.size, candidateSyllables.size)

        var fullExact = 0
        var prefixMatched = 0
        var consumedDigits = 0

        for (i in 0 until n) {
            val seg = planSegs[i].lowercase(Locale.ROOT)
            val syl = candidateSyllables[i].lowercase(Locale.ROOT)

            val segDigits = T9Lookup.encodeLetters(seg).length.coerceAtLeast(1)

            if (seg == syl) {
                fullExact++
                prefixMatched++
                consumedDigits += segDigits
                continue
            }

            if (syl.startsWith(seg)) {
                prefixMatched++
                consumedDigits += segDigits
                continue
            }

            break
        }

        val uncoveredDigits = (rawDigits.length - consumedDigits).coerceAtLeast(0)

        return CandidateScore(
            fullExactSegments = fullExact,
            prefixMatchedSegments = prefixMatched,
            uncoveredDigits = uncoveredDigits,
            priority = cand.priority,
            syllables = if (cand.syllables > 0) cand.syllables else candidateSyllables.size,
            wordLength = cand.word.length,
            inputLength = cand.input.length
        )
    }

    private fun resolveCandidateSyllables(cand: Candidate): List<String> {
        val py = cand.pinyin?.lowercase(Locale.ROOT)?.trim()
        if (!py.isNullOrEmpty()) {
            val split = splitConcatPinyinToSyllables(py)
            if (split.isNotEmpty()) return split
        }

        val input = cand.input.lowercase(Locale.ROOT).trim().replace("'", "")
        if (input.isNotEmpty()) {
            val split = splitConcatPinyinToSyllables(input)
            if (split.isNotEmpty()) return split
        }

        return emptyList()
    }

    private fun buildPreviewCore(
        topCandidate: Candidate?,
        fallbackSegments: List<String>
    ): String {
        val topSyllables = topCandidate?.let { resolveCandidateSyllables(it) }.orEmpty()
        return if (topSyllables.isNotEmpty()) {
            topSyllables.joinToString("'")
        } else {
            fallbackSegments.joinToString("'")
        }
    }

    private fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        if (rawLower.isEmpty()) return emptyList()
        if (!rawLower.all { it in 'a'..'z' || it == 'ü' }) return emptyList()

        val normalized = rawLower.replace("ü", "v")
        val normalizedSet = pinyinSet.map { it.replace("ü", "v") }.toHashSet()
        val out = ArrayList<String>()

        var i = 0
        while (i < normalized.length) {
            val remain = normalized.length - i
            val tryMax = min(maxPyLen, remain)

            var matched: String? = null
            var l = tryMax
            while (l >= 1) {
                val sub = normalized.substring(i, i + l)
                if (normalizedSet.contains(sub)) {
                    matched = sub
                    break
                }
                l--
            }

            if (matched == null) return emptyList()
            out.add(matched.replace("v", "ü"))
            i += matched.length
        }

        return out
    }

    internal object SentenceDecoder {

        fun decodeAllSegments(
            digits: String,
            manualCuts: List<Int>,
            dict: Dictionary
        ): List<String> {
            if (digits.isEmpty()) return emptyList()

            val parts = splitDigitsByCuts(digits, manualCuts)
            if (parts.isEmpty()) return emptyList()

            val out = ArrayList<String>()
            for (part in parts) {
                out.addAll(decodeOnePart(part, dict))
            }
            return out
        }

        fun decodeNextSegment(
            digits: String,
            manualCuts: List<Int>,
            dict: Dictionary
        ): String? {
            return decodeAllSegments(digits, manualCuts, dict).firstOrNull()
        }

        private fun decodeOnePart(part: String, dict: Dictionary): List<String> {
            if (part.isEmpty()) return emptyList()

            val out = ArrayList<String>()
            var remain = part

            while (remain.isNotEmpty()) {
                val next = dict.getPinyinPossibilities(remain)
                    .firstOrNull()
                    ?.lowercase(Locale.ROOT)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: defaultLetterForDigit(remain[0])

                out.add(next)

                val consume = T9Lookup.encodeLetters(next)
                    .length
                    .coerceAtLeast(1)
                    .coerceAtMost(remain.length)

                remain = remain.substring(consume)
            }

            return out
        }

        private fun splitDigitsByCuts(digits: String, manualCuts: List<Int>): List<String> {
            if (digits.isEmpty()) return emptyList()

            val cuts = manualCuts
                .asSequence()
                .filter { it in 1 until digits.length }
                .distinct()
                .sorted()
                .toList()

            if (cuts.isEmpty()) return listOf(digits)

            val out = ArrayList<String>()
            var prev = 0
            for (cut in cuts) {
                if (cut > prev) {
                    out.add(digits.substring(prev, cut))
                }
                prev = cut
            }
            if (prev < digits.length) {
                out.add(digits.substring(prev))
            }
            return out
        }

        private fun defaultLetterForDigit(d: Char): String {
            val list = T9Lookup.charsFromDigit(d)
            val s = list.firstOrNull()?.lowercase(Locale.ROOT)?.trim()
            return if (!s.isNullOrEmpty()) s.substring(0, 1) else "?"
        }
    }
}

/**
 * Rewritten baseline candidate engine for CN-T9 sentence mode.
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

        val consumeSyllables = resolveConsumeSyllables(cand).coerceAtLeast(1)

        materializeSegmentsIfNeeded(consumeSyllables)

        val availableStack = session.pinyinStack.size
        if (availableStack > 0) {
            val consume = consumeSyllables.coerceAtMost(availableStack)
            val pickCand = cand.copy(pinyinCount = consume)

            when (val result = session.pickCandidate(
                cand = pickCand,
                useT9Layout = true,
                isChinese = true
            )) {
                is ComposingSession.PickResult.Commit -> {
                    commitRaw(result.text)
                    clearComposing()
                }

                is ComposingSession.PickResult.Updated -> {
                    updateCandidates()
                    updateComposingView()
                }
            }
            return
        }

        if (session.rawT9Digits.isNotEmpty()) {
            val inputLen = cand.input.length.coerceAtLeast(1).coerceAtMost(session.rawT9Digits.length)
            val pickCand = cand.copy(input = cand.input.take(inputLen), pinyinCount = 0)

            when (val result = session.pickCandidate(
                cand = pickCand,
                useT9Layout = true,
                isChinese = true
            )) {
                is ComposingSession.PickResult.Commit -> {
                    commitRaw(result.text)
                    clearComposing()
                }

                is ComposingSession.PickResult.Updated -> {
                    updateCandidates()
                    updateComposingView()
                }
            }
            return
        }

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

    private fun materializeSegmentsIfNeeded(targetSyllables: Int) {
        if (!dictEngine.isLoaded) return

        while (session.pinyinStack.size < targetSyllables && session.rawT9Digits.isNotEmpty()) {
            val next = CnT9Handler.SentenceDecoder.decodeNextSegment(
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
        if (cand.syllables > 0) return cand.syllables

        cand.pinyin?.let { py ->
            val normalized = py.lowercase(Locale.ROOT).replace("'", "")
            val split = splitCandidatePinyin(normalized)
            if (split > 0) return split
        }

        val input = cand.input.lowercase(Locale.ROOT).replace("'", "")
        val split = splitCandidatePinyin(input)
        if (split > 0) return split

        return 1
    }

    private fun splitCandidatePinyin(raw: String): Int {
        if (raw.isBlank()) return 0
        val all = PinyinTable.allPinyins.map { it.lowercase(Locale.ROOT).replace("ü", "v") }.toHashSet()
        val normalized = raw.replace("ü", "v")

        var i = 0
        var count = 0
        val maxLen = all.maxOfOrNull { it.length } ?: 8

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
