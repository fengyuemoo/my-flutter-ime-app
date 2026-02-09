package com.example.myapp.ime.mode.cn

import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.ImeModeHandler
import com.example.myapp.ime.ui.ImeUi

object CnQwertyHandler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val input = session.qwertyInput

        // 1) Candidates (dictionary)
        val candidates = ArrayList<Candidate>()
        if (dictEngine.isLoaded && input.isNotEmpty()) {
            candidates.addAll(dictEngine.getSuggestions(input, isT9 = false, isChineseMode = true))
        }

        // 2) Single char filter (CN only)
        val filtered = if (singleCharMode) {
            candidates.filter { it.word.length == 1 }
        } else {
            candidates
        }

        // 3) CN-mode English reorder strategy (exactly same idea as CandidateComposer)
        val finalCandidates = reorderForChineseModePreferHan(filtered, input)

        // 4) Fallback: show raw input if no candidates
        val finalList =
            if (finalCandidates.isEmpty() && input.isNotEmpty()) {
                arrayListOf(Candidate(input, input, 0, 0, 0))
            } else {
                ArrayList(finalCandidates)
            }

        val inputLower = input.lowercase()

        // 缩写词组（yg/ygr/hy/py）强置顶
        if (isAcronymLikeQwerty(inputLower)) {
            promoteChineseCandidateByAcronym(finalList, inputLower)
        }

        val top = finalList.firstOrNull()

        val preview = when {
            inputLower.contains("'") -> inputLower

            // 1) 首候选是英文精确词或变体：预览显示英文（不带分词符）
            top != null && isAsciiWord(top.word) -> {
                val w = top.word.lowercase()
                val variants = englishVariantsForPreview(inputLower)
                if (w == inputLower || w in variants) w else QwertyPreviewBuilder.build(inputLower)
            }

            else -> {
                val defaultPreview = QwertyPreviewBuilder.build(inputLower)
                val defaultPartsCount = defaultPreview
                    .split("'")
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .count()

                val canStrictlyProveTopIsAcronymHit =
                    top != null &&
                        top.acronym != null &&
                        top.acronym.lowercase() == inputLower &&
                        top.word.length == inputLower.length &&
                        inputLower.length in 2..12 &&
                        inputLower.all { it in 'a'..'z' }

                if (canStrictlyProveTopIsAcronymHit && defaultPartsCount < inputLower.length) {
                    inputLower.map { it.toString() }.joinToString("'")
                } else {
                    defaultPreview
                }
            }
        }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = emptyList(),
            composingPreviewText = session.committedPrefix + preview
        )
    }

    // -------- from old CandidateController (CN Qwerty preview & acronym promote) --------

    private object PinyinUtil {
        private val pinyinSet: Set<String> = PinyinTable.allPinyins.map { it.lowercase() }.toHashSet()
        private val maxPyLen: Int = PinyinTable.allPinyins.maxOfOrNull { it.length } ?: 8

        fun normalizeLetters(s: String): String {
            val sb = StringBuilder()
            for (ch in s.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }

        fun splitToSyllablesBest(letters: String): List<String> {
            val s = normalizeLetters(letters)
            if (s.isEmpty()) return emptyList()

            val dp: ArrayList<List<String>?> = ArrayList<List<String>?>(s.length + 1)
            repeat(s.length + 1) { dp.add(null) }
            dp[0] = emptyList()

            for (i in 0..s.length) {
                val base = dp[i] ?: continue
                val remain = s.length - i
                val tryMax = minOf(maxPyLen, remain)
                for (l in tryMax downTo 1) {
                    val sub = s.substring(i, i + l)
                    if (!pinyinSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    if (old == null || cand.size < old.size) {
                        dp[i + l] = cand
                    }
                }
            }

            return dp[s.length] ?: emptyList()
        }

        fun acronymOfPinyin(pinyin: String): String {
            val syl = splitToSyllablesBest(pinyin)
            if (syl.isEmpty()) return ""
            val sb = StringBuilder()
            for (s in syl) if (s.isNotEmpty()) sb.append(s[0])
            return sb.toString()
        }
    }

    private object QwertyPreviewBuilder {
        private val all = PinyinTable.allPinyins.map { it.lowercase() }
        private val syllableSet: Set<String> = all.toHashSet()
        private val maxLen: Int = all.maxOfOrNull { it.length } ?: 8

        private val prefixSet: Set<String> by lazy {
            val set = HashSet<String>(all.size * 3)
            for (py in all) {
                for (i in 1..py.length) set.add(py.substring(0, i))
            }
            set
        }

        private fun normalizeLetters(s: String): String {
            val sb = StringBuilder()
            for (ch in s.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }

        private fun splitBestExactPrefix(s: String): Pair<List<String>, Int> {
            if (s.isEmpty()) return emptyList<String>() to 0

            val dp = arrayOfNulls<List<String>>(s.length + 1)
            dp[0] = emptyList()

            for (i in 0..s.length) {
                val base = dp[i] ?: continue
                val remain = s.length - i
                val tryMax = minOf(maxLen, remain)
                for (l in tryMax downTo 1) {
                    val sub = s.substring(i, i + l)
                    if (!syllableSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    if (old == null || cand.size < old.size) dp[i + l] = cand
                }
            }

            var bestCut = 0
            for (k in s.length downTo 0) {
                if (dp[k] != null) {
                    bestCut = k
                    break
                }
            }
            return (dp[bestCut] ?: emptyList()) to bestCut
        }

        private fun longestPrefix(rem: String): String {
            if (rem.isEmpty()) return ""
            val max = minOf(maxLen, rem.length)
            for (l in max downTo 1) {
                val sub = rem.substring(0, l)
                if (prefixSet.contains(sub)) return sub
            }
            return rem.take(1)
        }

        fun build(rawLower: String): String {
            val s = rawLower.trim().lowercase()
            if (s.isEmpty()) return s
            if (s.contains("'")) return s

            val letters = normalizeLetters(s)
            if (letters.isEmpty() || letters != s) return s

            val (parts, cut) = splitBestExactPrefix(letters)
            val out = ArrayList<String>()
            out.addAll(parts)

            if (cut < letters.length) {
                val rem = letters.substring(cut)
                val p = longestPrefix(rem)
                out.add(p)
                val rest = rem.substring(p.length)
                if (rest.isNotEmpty()) out.add(rest)
            }

            if (out.isEmpty()) out.add(letters)
            return out.joinToString("'")
        }
    }

    private fun isAcronymLikeQwerty(inputLower: String): Boolean {
        if (inputLower.length !in 2..6) return false
        if (!inputLower.all { it in 'a'..'z' }) return false
        if (inputLower == "zh" || inputLower == "ch" || inputLower == "sh") return false
        return inputLower.none { it == 'a' || it == 'e' || it == 'i' || it == 'o' || it == 'u' || it == 'v' }
    }

    private fun promoteChineseCandidateByAcronym(
        candidates: ArrayList<Candidate>,
        acronymKey: String
    ) {
        if (acronymKey.isEmpty()) return
        if (candidates.isEmpty()) return

        var bestIdx = -1
        var bestScore = Long.MIN_VALUE

        for (i in candidates.indices) {
            val c = candidates[i]

            // 优先用 DB 的 acronym；缺失才回退到从 pinyin 反算
            val ac = c.acronym
                ?: run {
                    val py = c.pinyin ?: return@run null
                    PinyinUtil.acronymOfPinyin(py).takeIf { it.isNotEmpty() }
                }
                ?: continue

            if (ac != acronymKey) continue

            val wordLenBoost = if (c.word.length == acronymKey.length) 200_000_000L else 0L
            val syllablesBoost = if (c.syllables > 0 && c.syllables == acronymKey.length) 100_000_000L else 0L
            val freqScore = c.priority.toLong()

            val score = wordLenBoost + syllablesBoost + freqScore
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        if (bestIdx <= 0) return
        val matched = candidates.removeAt(bestIdx)
        candidates.add(0, matched)
    }

    // -------- from old CandidateComposer (CN-mode prefer Han) --------

    private fun reorderForChineseModePreferHan(
        list: List<Candidate>,
        rawInput: String
    ): List<Candidate> {
        if (list.isEmpty()) return list
        if (rawInput.isEmpty()) return list

        val isAlphaInput = rawInput.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (!isAlphaInput) {
            return groupChineseFirst(list, englishTailLimit = 5)
        }

        val inputLower = rawInput.lowercase()

        val (english, nonEnglish) = list.partition { isAsciiWord(it.word) }

        val englishExact = ArrayList<Candidate>()
        val englishVariants = ArrayList<Candidate>()
        val englishOthers = ArrayList<Candidate>()

        val variantWhitelist = englishVariantsForOrder(inputLower).toHashSet()

        for (c in english) {
            val w = c.word.lowercase()
            when {
                w == inputLower -> englishExact.add(c)
                w in variantWhitelist -> englishVariants.add(c)
                else -> englishOthers.add(c)
            }
        }

        val shouldPromoteEnglish = inputLower.length >= 4

        val promotedEnglish = if (shouldPromoteEnglish && englishExact.isNotEmpty()) {
            englishExact + englishVariants.take(2)
        } else {
            emptyList()
        }

        val promotedSet = promotedEnglish.toHashSet()

        val nonEnglishKept = nonEnglish.filter { it !in promotedSet }
        val englishVariantsKept = englishVariants.filter { it !in promotedSet }
        val englishExactKept = englishExact.filter { it !in promotedSet }
        val englishOthersKept = englishOthers.filter { it !in promotedSet }

        val tail = (englishExactKept + englishVariantsKept + englishOthersKept).take(5)

        return promotedEnglish + nonEnglishKept + tail
    }

    private fun englishVariantsForOrder(inputLower: String): List<String> {
        if (inputLower.length < 2) return emptyList()

        fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

        val out = ArrayList<String>()

        if (inputLower.endsWith("y") && inputLower.length >= 2) {
            val prev = inputLower[inputLower.length - 2]
            if (!isVowel(prev)) {
                out.add(inputLower.dropLast(1) + "ies")
                return out
            }
        }

        out.add("${inputLower}s")

        val needEs =
            inputLower.endsWith("s")
                    || inputLower.endsWith("x")
                    || inputLower.endsWith("z")
                    || inputLower.endsWith("ch")
                    || inputLower.endsWith("sh")
                    || inputLower.endsWith("o")

        if (needEs) out.add("${inputLower}es")

        return out
    }

    private fun groupChineseFirst(list: List<Candidate>, englishTailLimit: Int): List<Candidate> {
        val (english, nonEnglish) = list.partition { isAsciiWord(it.word) }
        return nonEnglish + english.take(englishTailLimit)
    }

    private fun isAsciiWord(word: String): Boolean {
        if (word.isEmpty()) return false
        return word.all { it in 'a'..'z' || it in 'A'..'Z' }
    }

    // Preview uses a simpler whitelist (matches old CandidateController behavior)
    private fun englishVariantsForPreview(inputLower: String): Set<String> {
        if (inputLower.length < 2) return emptySet()
        val out = LinkedHashSet<String>()
        out.add("${inputLower}s")
        out.add("${inputLower}es")
        return out
    }
}

/**
 * Strong-isolated candidate engine for CN-QWERTY: holds its own UI-state + candidate chain.
 */
class CnQwertyCandidateEngine(
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

        val out = CnQwertyHandler.build(
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
            val msg = "Candidate index out of range: CN_QWERTY index=$index size=${currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("CnQwertyCandidateEngine", msg)
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
            useT9Layout = false,
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
            val msg = "Candidate not in current CN_QWERTY list: cand=$cand size=${currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("CnQwertyCandidateEngine", msg)
                throw AssertionError(msg)
            }
            return
        }
        commitCandidateAt(idx)
    }
}
