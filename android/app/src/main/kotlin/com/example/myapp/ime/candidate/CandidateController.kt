package com.example.myapp.ime.candidate

import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.ime.ui.api.UiStateActions

class CandidateController(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateComposer: CandidateComposer,
    private val sessions: ComposingSessionHub,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val updateComposingView: () -> Unit,
) : UiStateActions {

    private var isExpanded = false
    private var isSingleCharMode = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()

    private fun session(): ComposingSession = sessions.current()

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

    private fun isAcronymLikeQwerty(inputLower: String): Boolean {
        if (inputLower.length !in 2..6) return false
        if (!inputLower.all { it in 'a'..'z' }) return false
        if (inputLower == "zh" || inputLower == "ch" || inputLower == "sh") return false
        return inputLower.none { it == 'a' || it == 'e' || it == 'i' || it == 'o' || it == 'u' || it == 'v' }
    }

    private fun isAsciiWord(word: String): Boolean {
        if (word.isEmpty()) return false
        return word.all { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun englishVariantsFor(inputLower: String): Set<String> {
        if (inputLower.length < 2) return emptySet()
        val out = LinkedHashSet<String>()
        out.add("${inputLower}s")
        out.add("${inputLower}es")
        return out
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

            // 优先用 DB 的 acronym（严格且不受拼音切分影响）；缺失才回退到从 pinyin 反算
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

    override fun toggleCandidatesExpanded() {
        toggleExpand()
    }

    override fun syncFilterButtonState() {
        syncFilterButton()
    }

    override fun toggleSingleCharMode() {
        toggleSingleCharModeInternal()
    }

    fun syncFilterButton() {
        ui.setFilterButton(isSingleCharMode)
    }

    private fun toggleSingleCharModeInternal() {
        isSingleCharMode = !isSingleCharMode
        ui.setFilterButton(isSingleCharMode)
        updateCandidates()
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
        ui.setExpanded(isExpanded, session().isComposing())
    }

    fun updateCandidates() {
        val s = session()
        currentCandidates.clear()

        if (!s.isComposing()) {
            ui.showIdleState()
            keyboardController.updateSidebar(emptyList())
            s.setT9PreviewText(null)
            s.setQwertyPreviewText(null)

            if (isExpanded) {
                isExpanded = false
                ui.setExpanded(false, isComposing = false)
            }
            return
        }

        ui.showComposingState(isExpanded)

        val mainMode = keyboardController.getMainMode()

        val r = candidateComposer.compose(
            session = s,
            isChinese = mainMode.isChinese,
            useT9Layout = mainMode.useT9Layout,
            isT9Keyboard = mainMode.useT9Layout,
            singleCharMode = isSingleCharMode
        )

        keyboardController.updateSidebar(r.pinyinSidebar)
        currentCandidates = ArrayList(r.candidates)

        // --- T9 预览（用于悬浮） ---
        val t9PreviewText =
            if (mainMode.isChinese && mainMode.useT9Layout && s.rawT9Digits.isNotEmpty()) {
                T9PreviewBuilder.buildPreview(s.rawT9Digits, currentCandidates)
            } else {
                null
            }
        s.setT9PreviewText(t9PreviewText)

        if (mainMode.isChinese && mainMode.useT9Layout) {
            promoteCandidateMatchingPreview(currentCandidates, t9PreviewText)
        }

        // --- 中文全键盘：预览与首候选保持一致/可解释 ---
        if (mainMode.isChinese && !mainMode.useT9Layout) {
            val inputLower = s.qwertyInput.lowercase()

            // 缩写词组（yg/ygr/hy/py）强置顶（保持你原逻辑）
            if (isAcronymLikeQwerty(inputLower)) {
                promoteChineseCandidateByAcronym(currentCandidates, inputLower)
            }

            val top = currentCandidates.firstOrNull()

            val preview = when {
                inputLower.contains("'") -> inputLower

                // 1) 首候选是英文精确词或变体：预览显示英文（不带分词符）
                top != null && isAsciiWord(top.word) -> {
                    val w = top.word.lowercase()
                    val variants = englishVariantsFor(inputLower)
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

            s.setQwertyPreviewText(preview)
        } else {
            s.setQwertyPreviewText(null)
        }

        ui.setCandidates(currentCandidates)
        ui.setComposingPreview(s.displayText(mainMode.useT9Layout))
    }

    fun handleSpaceKey() {
        if (currentCandidates.isNotEmpty()) {
            commitCandidate(currentCandidates[0])
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        if (currentCandidates.isEmpty()) return false
        commitCandidate(currentCandidates[0])
        return true
    }

    fun commitCandidate(cand: Candidate) {
        if (keyboardController.isRawCommitMode()) {
            commitRaw(cand.word)
            clearComposing()
            return
        }

        val mainMode = keyboardController.getMainMode()

        when (val r = session().pickCandidate(
            cand,
            mainMode.useT9Layout,
            mainMode.isChinese
        )) {
            is ComposingSession.PickResult.Commit -> {
                commitRaw(r.text)
                clearComposing()
            }

            is ComposingSession.PickResult.Updated -> {
                updateComposingView()
                updateCandidates()
            }
        }
    }
}
