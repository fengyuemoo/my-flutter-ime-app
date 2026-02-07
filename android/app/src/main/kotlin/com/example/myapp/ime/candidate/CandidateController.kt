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

        /**
         * 把完整拼音（如 yige / yinggai / yiger）尽量切成更多音节（yi + ge，ying + gai，yi + ge + ren）。
         * 如果无法完全切分，返回 emptyList。
         */
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
                for (l in 1..tryMax) {
                    val sub = s.substring(i, i + l)
                    if (!pinyinSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    // 优先音节更多（更符合 yg/ygr 这种缩写）
                    if (old == null || cand.size > old.size) {
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
        // 无元音：更像 yg/ygr/hy/py 这类缩写，而不是 year/near 这类英文词
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
            val py = c.pinyin ?: continue // 只提升中文（有拼音信息）的候选
            val ac = PinyinUtil.acronymOfPinyin(py)
            if (ac != acronymKey) continue

            // 更强置顶策略：
            // - 完全缩写匹配：必选集合
            // - 词长 == 缩写长度：更像“一个(2)/一个人(3)”这种短语
            // - syllables == 缩写长度：如果词库提供更可靠（没有也不影响）
            // - priority(freq)：仍然作为主要排序依据
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

        // T9 预览（用于悬浮）
        val previewText =
            if (mainMode.isChinese && mainMode.useT9Layout && s.rawT9Digits.isNotEmpty()) {
                T9PreviewBuilder.buildPreview(s.rawT9Digits, currentCandidates)
            } else {
                null
            }
        s.setT9PreviewText(previewText)

        // 中文 T9：把与预览一致的中文候选提升到第 1 位
        if (mainMode.isChinese && mainMode.useT9Layout) {
            promoteCandidateMatchingPreview(currentCandidates, previewText)
        }

        // 中文全键盘：缩写词组（yg/ygr/hy/py）进一步强置顶（确保“一个/一个人...”稳定排第 1）
        if (mainMode.isChinese && !mainMode.useT9Layout) {
            val key = s.qwertyInput.lowercase()
            if (isAcronymLikeQwerty(key)) {
                promoteChineseCandidateByAcronym(currentCandidates, key)
            }
        }

        ui.setCandidates(currentCandidates)
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
