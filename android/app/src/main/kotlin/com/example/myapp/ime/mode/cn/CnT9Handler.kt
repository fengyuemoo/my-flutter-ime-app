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
import kotlin.math.abs
import kotlin.math.min

object CnT9Handler : ImeModeHandler {

    private val allPinyinsLower: List<String> =
        PinyinTable.allPinyins.map { it.lowercase(Locale.ROOT) }

    private val normalizedPinyinSet: Set<String> =
        allPinyinsLower.map { it.replace("ü", "v") }.toHashSet()

    private val maxPyLen: Int = normalizedPinyinSet.maxOfOrNull { it.length } ?: 8

    private const val MAX_DISPLAY_CANDIDATES = 120
    private const val MAX_PLAN_COUNT = 12
    private const val PART_BEAM_WIDTH = 8
    private const val PART_STEP_OPTIONS = 8
    private const val MAX_QUERY_PER_PLAN = 80
    private const val MAX_SIDEBAR_ITEMS = 24

    internal data class PathPlan(
        val rank: Int,
        val segments: List<String>,
        val consumedDigits: Int
    ) {
        val text: String = segments.joinToString("'")
    }

    private data class CandidateScore(
        val exactSegments: Int,
        val exactChars: Int,
        val prefixSegments: Int,
        val prefixChars: Int,
        val consumedDigits: Int,
        val uncoveredDigits: Int,
        val syllableDistance: Int,
        val exactInput: Boolean,
        val planRank: Int,
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

        val sidebar = buildSidebar(
            dictEngine = dictEngine,
            rawDigits = rawDigits
        )

        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            SentencePlanner.planAll(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else {
            emptyList()
        }

        val plans = buildPlans(
            stackSegs = stackSegs,
            autoPlans = autoPlans
        )

        val queried = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            queryCandidatesByPlans(
                dict = dictEngine,
                plans = plans
            )
        } else {
            emptyList()
        }

        val filtered = if (singleCharMode) {
            queried.filter { it.word.length == 1 }
        } else {
            queried
        }

        val finalList = ArrayList<Candidate>()
        finalList.addAll(filtered)

        val scoreCache = buildScoreCache(
            candidates = finalList,
            plans = plans,
            rawDigits = rawDigits
        )

        sortCandidates(
            candidates = finalList,
            scoreCache = scoreCache
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

        val bestPlan = plans.firstOrNull()
        val topCandidate = finalList.firstOrNull()
        val topScore = topCandidate?.let { scoreCache[it] }

        val previewCore = buildPreviewCore(
            bestPlan = bestPlan,
            topCandidate = topCandidate,
            topScore = topScore
        )

        val composingPreviewText = buildString {
            append(session.committedPrefix)
            if (previewCore.isNotEmpty()) {
                append(previewCore)
            }
        }.takeIf { it.isNotBlank() }

        val enterCommitText = previewCore
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it == '\'' }
            .takeIf { it.isNotEmpty() }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = sidebar,
            composingPreviewText = composingPreviewText,
            enterCommitText = enterCommitText
        )
    }

    private fun buildSidebar(
        dictEngine: Dictionary,
        rawDigits: String
    ): List<String> {
        if (!dictEngine.isLoaded || rawDigits.isEmpty()) return emptyList()

        val fromDict = dictEngine.getPinyinPossibilities(rawDigits)
            .map { it.lowercase(Locale.ROOT).trim() }
            .filter { it.isNotEmpty() }

        return (fromDict + T9Lookup.charsFromDigit(rawDigits.first()).map { it.lowercase(Locale.ROOT) })
            .distinct()
            .take(MAX_SIDEBAR_ITEMS)
    }

    private fun buildPlans(
        stackSegs: List<String>,
        autoPlans: List<PathPlan>
    ): List<PathPlan> {
        if (stackSegs.isEmpty() && autoPlans.isEmpty()) return emptyList()

        if (autoPlans.isEmpty()) {
            return listOf(
                PathPlan(
                    rank = 0,
                    segments = stackSegs,
                    consumedDigits = 0
                )
            )
        }

        return autoPlans.map { auto ->
            PathPlan(
                rank = auto.rank,
                segments = stackSegs + auto.segments,
                consumedDigits = auto.consumedDigits
            )
        }
    }

    private fun queryCandidatesByPlans(
        dict: Dictionary,
        plans: List<PathPlan>
    ): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()

        for (plan in plans) {
            if (plan.segments.isEmpty()) continue

            val exactByStack = dict.getSuggestionsFromPinyinStack(
                pinyinStack = plan.segments,
                rawDigits = ""
            )

            var taken = 0
            for (cand in exactByStack) {
                val normalized = normalizeCandidateAgainstPlan(cand, plan)
                if (!out.containsKey(normalized.word)) {
                    out[normalized.word] = normalized
                    taken++
                    if (taken >= MAX_QUERY_PER_PLAN) break
                }
            }

            if (taken < MAX_QUERY_PER_PLAN) {
                val exactByJoined = dict.getSuggestions(
                    input = plan.text,
                    isT9 = false,
                    isChineseMode = true
                )

                for (cand in exactByJoined) {
                    val normalized = normalizeCandidateAgainstPlan(cand, plan)
                    if (!out.containsKey(normalized.word)) {
                        out[normalized.word] = normalized
                        taken++
                        if (taken >= MAX_QUERY_PER_PLAN) break
                    }
                }
            }
        }

        return out.values.toList()
    }

    private fun normalizeCandidateAgainstPlan(
        cand: Candidate,
        plan: PathPlan
    ): Candidate {
        val count = resolveCandidateSyllables(cand)
            .size
            .coerceAtLeast(if (cand.syllables > 0) cand.syllables else 0)
            .coerceAtLeast(1)

        return cand.copy(
            input = plan.text,
            matchedLength = plan.consumedDigits,
            pinyinCount = count
        )
    }

    private fun buildScoreCache(
        candidates: List<Candidate>,
        plans: List<PathPlan>,
        rawDigits: String
    ): Map<Candidate, CandidateScore?> {
        if (candidates.isEmpty() || plans.isEmpty()) return emptyMap()

        val out = HashMap<Candidate, CandidateScore?>(candidates.size)
        for (cand in candidates) {
            out[cand] = bestScoreForCandidate(cand, plans, rawDigits)
        }
        return out
    }

    private fun sortCandidates(
        candidates: ArrayList<Candidate>,
        scoreCache: Map<Candidate, CandidateScore?>
    ) {
        if (candidates.isEmpty()) return

        candidates.sortWith(
            compareByDescending<Candidate> { scoreCache[it]?.exactSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.exactChars ?: 0 }
                .thenByDescending { scoreCache[it]?.prefixSegments ?: 0 }
                .thenByDescending { scoreCache[it]?.prefixChars ?: 0 }
                .thenByDescending { scoreCache[it]?.consumedDigits ?: 0 }
                .thenBy { scoreCache[it]?.uncoveredDigits ?: Int.MAX_VALUE }
                .thenBy { scoreCache[it]?.syllableDistance ?: Int.MAX_VALUE }
                .thenByDescending { if (scoreCache[it]?.exactInput == true) 1 else 0 }
                .thenBy { scoreCache[it]?.planRank ?: Int.MAX_VALUE }
                .thenByDescending { scoreCache[it]?.priority ?: it.priority }
                .thenByDescending { scoreCache[it]?.wordLength ?: it.word.length }
                .thenByDescending { scoreCache[it]?.syllables ?: it.syllables }
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
            val score = scoreAgainstPlan(
                cand = cand,
                plan = plan,
                candidateSyllables = syllables,
                rawDigits = rawDigits
            )
            if (best == null || isBetter(score, best)) {
                best = score
            }
        }
        return best
    }

    private fun scoreAgainstPlan(
        cand: Candidate,
        plan: PathPlan,
        candidateSyllables: List<String>,
        rawDigits: String
    ): CandidateScore {
        val planSegs = plan.segments
        val n = min(planSegs.size, candidateSyllables.size)

        var exactSegments = 0
        var exactChars = 0
        var prefixSegments = 0
        var prefixChars = 0
        var consumedDigits = 0

        for (i in 0 until n) {
            val expected = planSegs[i].lowercase(Locale.ROOT)
            val actual = candidateSyllables[i].lowercase(Locale.ROOT)
            val segDigits = T9Lookup.encodeLetters(expected)
                .length
                .coerceAtLeast(1)

            if (expected == actual) {
                exactSegments++
                exactChars += expected.length
                prefixSegments++
                prefixChars += expected.length
                consumedDigits += segDigits
                continue
            }

            if (actual.startsWith(expected)) {
                prefixSegments++
                prefixChars += expected.length
                consumedDigits += segDigits
                continue
            }

            break
        }

        val planConcat = normalizePinyinConcat(plan.segments.joinToString(""))
        val candConcat = normalizedCandidateConcatPinyin(cand)

        return CandidateScore(
            exactSegments = exactSegments,
            exactChars = exactChars,
            prefixSegments = prefixSegments,
            prefixChars = prefixChars,
            consumedDigits = consumedDigits,
            uncoveredDigits = (rawDigits.length - consumedDigits).coerceAtLeast(0),
            syllableDistance = abs(candidateSyllables.size - planSegs.size),
            exactInput = candConcat.isNotEmpty() && candConcat == planConcat,
            planRank = plan.rank,
            priority = cand.priority,
            syllables = if (cand.syllables > 0) cand.syllables else candidateSyllables.size,
            wordLength = cand.word.length,
            inputLength = cand.input.length
        )
    }

    private fun isBetter(a: CandidateScore, b: CandidateScore): Boolean {
        return when {
            a.exactSegments != b.exactSegments -> a.exactSegments > b.exactSegments
            a.exactChars != b.exactChars -> a.exactChars > b.exactChars
            a.prefixSegments != b.prefixSegments -> a.prefixSegments > b.prefixSegments
            a.prefixChars != b.prefixChars -> a.prefixChars > b.prefixChars
            a.consumedDigits != b.consumedDigits -> a.consumedDigits > b.consumedDigits
            a.uncoveredDigits != b.uncoveredDigits -> a.uncoveredDigits < b.uncoveredDigits
            a.syllableDistance != b.syllableDistance -> a.syllableDistance < b.syllableDistance
            a.exactInput != b.exactInput -> a.exactInput
            a.planRank != b.planRank -> a.planRank < b.planRank
            a.priority != b.priority -> a.priority > b.priority
            a.wordLength != b.wordLength -> a.wordLength > b.wordLength
            a.syllables != b.syllables -> a.syllables > b.syllables
            else -> a.inputLength > b.inputLength
        }
    }

    private fun buildPreviewCore(
        bestPlan: PathPlan?,
        topCandidate: Candidate?,
        topScore: CandidateScore?
    ): String {
        val planSegments = bestPlan?.segments.orEmpty()
        if (planSegments.isEmpty()) {
            return topCandidate
                ?.let { resolveCandidateSyllables(it) }
                .orEmpty()
                .joinToString("'")
        }

        val topSyllables = topCandidate
            ?.let { resolveCandidateSyllables(it) }
            .orEmpty()

        if (topSyllables.isEmpty() || topScore == null || bestPlan == null) {
            return planSegments.joinToString("'")
        }

        if (!shouldTrustTopCandidate(bestPlan, topScore)) {
            return planSegments.joinToString("'")
        }

        val confirmedCount = topScore.prefixSegments
            .coerceAtLeast(0)
            .coerceAtMost(topSyllables.size)
            .coerceAtMost(planSegments.size)

        val merged = buildList {
            addAll(topSyllables.take(confirmedCount))
            addAll(planSegments.drop(confirmedCount))
        }

        return merged.joinToString("'")
    }

    private fun shouldTrustTopCandidate(
        bestPlan: PathPlan,
        topScore: CandidateScore
    ): Boolean {
        if (bestPlan.segments.isEmpty()) return false

        if (topScore.exactInput && topScore.uncoveredDigits == 0) {
            return true
        }

        if (bestPlan.segments.size == 1) {
            return topScore.prefixSegments >= 1 &&
                topScore.syllableDistance <= 1
        }

        val needExact = min(2, bestPlan.segments.size)
        return topScore.exactSegments >= needExact &&
            topScore.prefixSegments >= needExact &&
            topScore.syllableDistance <= 1
    }

    private fun normalizedCandidateConcatPinyin(cand: Candidate): String {
        val fromPinyin = cand.pinyin
            ?.let { normalizePinyinConcat(it) }
            .orEmpty()

        if (fromPinyin.isNotEmpty()) return fromPinyin
        return normalizePinyinConcat(cand.input)
    }

    private fun normalizePinyinConcat(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("ü", "v")
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

    private fun splitConcatPinyinToSyllables(rawLower: String): List<String> {
        if (rawLower.isEmpty()) return emptyList()

        val normalized = rawLower.replace("ü", "v")
        if (!normalized.all { it in 'a'..'z' || it == 'v' }) return emptyList()

        val out = ArrayList<String>()
        var i = 0

        while (i < normalized.length) {
            val remain = normalized.length - i
            val tryMax = min(maxPyLen, remain)

            var matched: String? = null
            for (len in tryMax downTo 1) {
                val sub = normalized.substring(i, i + len)
                if (normalizedPinyinSet.contains(sub)) {
                    matched = sub
                    break
                }
            }

            if (matched == null) return emptyList()
            out.add(matched.replace("v", "ü"))
            i += matched.length
        }

        return out
    }

    internal object SentencePlanner {

        private data class Choice(
            val text: String,
            val codeLen: Int,
            val score: Int
        )

        private data class PartState(
            val pos: Int,
            val segments: List<String>,
            val score: Int
        )

        fun planAll(
            digits: String,
            manualCuts: List<Int>,
            dict: Dictionary
        ): List<PathPlan> {
            if (digits.isEmpty()) return emptyList()

            val parts = splitDigitsByCuts(digits, manualCuts)
            if (parts.isEmpty()) return emptyList()

            var combined = listOf(
                PartState(
                    pos = 0,
                    segments = emptyList(),
                    score = 0
                )
            )

            for (part in parts) {
                val decodedPart = decodePart(part, dict)
                if (decodedPart.isEmpty()) {
                    combined = combined.map { state ->
                        state.copy(
                            segments = state.segments + defaultLetterForDigit(part.first()),
                            score = state.score + 10
                        )
                    }
                    continue
                }

                val next = ArrayList<PartState>()
                for (prefix in combined) {
                    for (suffix in decodedPart) {
                        next.add(
                            PartState(
                                pos = 0,
                                segments = prefix.segments + suffix.segments,
                                score = prefix.score + suffix.score
                            )
                        )
                    }
                }

                combined = next
                    .sortedWith(
                        compareByDescending<PartState> { it.score }
                            .thenByDescending { joinedCodeLength(it.segments) }
                            .thenBy { it.segments.joinToString("'") }
                    )
                    .distinctBy { it.segments.joinToString("'") }
                    .take(MAX_PLAN_COUNT)
            }

            return combined.mapIndexed { index, state ->
                PathPlan(
                    rank = index,
                    segments = state.segments,
                    consumedDigits = joinedCodeLength(state.segments)
                        .coerceAtMost(digits.length)
                )
            }
        }

        fun decodeNextSegment(
            digits: String,
            manualCuts: List<Int>,
            dict: Dictionary
        ): String? {
            if (digits.isEmpty()) return null
            val plans = planAll(digits, manualCuts, dict)
            return plans.firstOrNull()?.segments?.firstOrNull()
        }

        private fun decodePart(
            part: String,
            dict: Dictionary
        ): List<PartState> {
            if (part.isEmpty()) return emptyList()

            var beam = listOf(
                PartState(
                    pos = 0,
                    segments = emptyList(),
                    score = 0
                )
            )

            while (beam.any { it.pos < part.length }) {
                val next = ArrayList<PartState>()

                for (state in beam) {
                    if (state.pos >= part.length) {
                        next.add(state)
                        continue
                    }

                    val remain = part.substring(state.pos)
                    val choices = buildChoices(remain, dict)

                    for (choice in choices.take(PART_STEP_OPTIONS)) {
                        val nextPos = (state.pos + choice.codeLen).coerceAtMost(part.length)
                        next.add(
                            PartState(
                                pos = nextPos,
                                segments = state.segments + choice.text,
                                score = state.score + choice.score
                            )
                        )
                    }
                }

                beam = next
                    .sortedWith(
                        compareByDescending<PartState> { it.score }
                            .thenByDescending { it.pos }
                            .thenBy { it.segments.joinToString("'") }
                    )
                    .distinctBy { "${it.pos}|${it.segments.joinToString("'")}" }
                    .take(PART_BEAM_WIDTH)

                if (beam.isEmpty()) break
                if (beam.all { it.pos >= part.length }) break
            }

            return beam
                .filter { it.pos >= part.length }
                .sortedWith(
                    compareByDescending<PartState> { it.score }
                        .thenBy { it.segments.joinToString("'") }
                )
                .distinctBy { it.segments.joinToString("'") }
                .take(MAX_PLAN_COUNT)
        }

        private fun buildChoices(
            digits: String,
            dict: Dictionary
        ): List<Choice> {
            val out = ArrayList<Choice>()
            val seen = HashSet<String>()

            val items = dict.getPinyinPossibilities(digits)
                .map { it.lowercase(Locale.ROOT).trim() }
                .filter { it.isNotEmpty() }

            for (item in items) {
                if (!seen.add(item)) continue
                val codeLen = T9Lookup.encodeLetters(item)
                    .length
                    .coerceAtLeast(1)
                    .coerceAtMost(digits.length)

                val normalized = item.replace("ü", "v")
                val score = when {
                    normalizedPinyinSet.contains(normalized) -> 300 + codeLen * 30
                    item == "zh" || item == "ch" || item == "sh" -> 240 + codeLen * 25
                    item.length == 1 -> 80 + codeLen * 10
                    else -> 120 + codeLen * 10
                }

                out.add(
                    Choice(
                        text = item,
                        codeLen = codeLen,
                        score = score
                    )
                )
            }

            val fallback = defaultLetterForDigit(digits.first())
            if (fallback.isNotEmpty() && seen.add(fallback)) {
                out.add(
                    Choice(
                        text = fallback,
                        codeLen = 1,
                        score = 20
                    )
                )
            }

            return out.sortedWith(
                compareByDescending<Choice> { it.score }
                    .thenByDescending { it.codeLen }
                    .thenByDescending { it.text.length }
                    .thenBy { it.text }
            )
        }

        private fun splitDigitsByCuts(
            digits: String,
            manualCuts: List<Int>
        ): List<String> {
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
            return T9Lookup.charsFromDigit(d)
                .firstOrNull()
                ?.lowercase(Locale.ROOT)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: ""
        }

        private fun joinedCodeLength(segments: List<String>): Int {
            var total = 0
            for (seg in segments) {
                total += T9Lookup.encodeLetters(seg).length
            }
            return total
        }
    }
}

/**
 * CN-T9 candidate engine:
 * - UI/提交门面继续保留
 * - 句级分段/候选生成交给 CnT9Handler
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

    private fun resetUiSelectionToTop() {
        ui.resetSelectedCandidateIndex()
    }

    private fun preferredCandidateIndexOrNull(): Int? {
        if (currentCandidates.isEmpty()) return null
        val selected = ui.getSelectedCandidateIndex()
        return if (selected in currentCandidates.indices) selected else 0
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

    fun updateCandidates() {
        syncFilterButton()
        currentCandidates.clear()

        if (!session.isComposing()) {
            composingPreviewOverride = null
            enterCommitTextOverride = null
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

        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride = out.enterCommitText
        currentCandidates = ArrayList(out.candidates)

        resetUiSelectionToTop()
        renderComposingUi(out)
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
        val preferred = preferredCandidateIndexOrNull() ?: return false
        commitCandidateAt(preferred)
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

        materializeSegmentsIfNeeded(consumeSyllables)

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
                    updateComposingView()
                }
            }
            return
        }

        if (session.rawT9Digits.isNotEmpty()) {
            val inputLen = T9Lookup.encodeLetters(
                cand.input.replace("'", "")
            ).length
                .coerceAtLeast(1)
                .coerceAtMost(session.rawT9Digits.length)

            val pickCand = cand.copy(
                input = session.rawT9Digits.take(inputLen),
                pinyinCount = 0
            )

            when (val result = session.pickCandidate(
                cand = pickCand,
                useT9Layout = true,
                isChinese = true
            )) {
                is ComposingSession.PickResult.Commit -> {
                    resetUiSelectionToTop()
                    commitRaw(result.text)
                    clearComposing()
                }

                is ComposingSession.PickResult.Updated -> {
                    resetUiSelectionToTop()
                    updateCandidates()
                    updateComposingView()
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

    private fun materializeSegmentsIfNeeded(targetSyllables: Int) {
        if (!dictEngine.isLoaded) return

        while (session.pinyinStack.size < targetSyllables && session.rawT9Digits.isNotEmpty()) {
            val next = CnT9Handler.SentencePlanner.decodeNextSegment(
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
            val split = splitCandidatePinyin(py.lowercase(Locale.ROOT).replace("'", ""))
            if (split > 0) return split
        }

        val split = splitCandidatePinyin(cand.input.lowercase(Locale.ROOT).replace("'", ""))
        if (split > 0) return split

        return 1
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
