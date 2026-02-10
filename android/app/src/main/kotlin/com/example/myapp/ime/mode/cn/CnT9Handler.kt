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

    private data class PathPlan(
        val rank: Int,                // 0 = 主预览路径，其它分支依次递增
        val segments: List<String>,    // stack + first + autoTail
        val text: String              // segments joined by '
    )

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val rawDigits = session.rawT9Digits

        // Sidebar: “下一节”候选
        val sidebar = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            dictEngine.getPinyinPossibilities(rawDigits)
        } else {
            emptyList()
        }

        // --- 主预览路径（stack + auto） ---
        val stackSegs = session.pinyinStack.map { it.lowercase() }
        val autoSegsMain = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            T9PathBuilder.buildAutoSegments(
                digits = rawDigits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
        } else {
            emptyList()
        }

        val fullSegsMain = ArrayList<String>(stackSegs.size + autoSegsMain.size).apply {
            addAll(stackSegs)
            addAll(autoSegsMain)
        }

        val previewPathText = fullSegsMain.joinToString("'").takeIf { it.isNotBlank() }

        // --- 候选：主分支优先 + 其它第一节分支补齐 ---
        val plans = buildPathPlans(
            dict = dictEngine,
            stackSegs = stackSegs,
            rawDigits = rawDigits,
            manualCuts = session.t9ManualCuts,
            sidebar = sidebar,
            mainAutoSegs = autoSegsMain
        )

        val candidates = if (dictEngine.isLoaded) {
            queryCandidatesByPlans(dictEngine, plans, limit = 300)
        } else {
            emptyList()
        }

        // Apply single-char filter if enabled.
        val filtered = if (singleCharMode) {
            candidates.filter { it.word.length == 1 }
        } else {
            candidates
        }

        val finalList =
            if (filtered.isEmpty() && rawDigits.isNotEmpty()) {
                arrayListOf(Candidate(rawDigits, rawDigits, 0, 0, 0))
            } else {
                ArrayList(filtered)
            }

        // Re-rank: 主分支优先，其次严格匹配段数，再按 freq
        rerankCandidatesByBestPlan(finalList, plans)

        // UI composing preview line: committedPrefix + 主预览路径
        val composingPreviewCore = previewPathText ?: ""
        val composingPreviewText = buildString {
            append(session.committedPrefix)
            if (composingPreviewCore.isNotEmpty()) append(composingPreviewCore)
        }.takeIf { it.isNotBlank() }

        // Enter commits preview letters only
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

    private fun buildPathPlans(
        dict: Dictionary,
        stackSegs: List<String>,
        rawDigits: String,
        manualCuts: List<Int>,
        sidebar: List<String>,
        mainAutoSegs: List<String>
    ): List<PathPlan> {
        val plans = ArrayList<PathPlan>()
        val mainSegs = ArrayList<String>(stackSegs.size + mainAutoSegs.size).apply {
            addAll(stackSegs)
            addAll(mainAutoSegs)
        }
        val mainText = mainSegs.joinToString("'")
        plans.add(PathPlan(rank = 0, segments = mainSegs, text = mainText))

        if (!dict.isLoaded) return plans
        if (rawDigits.isEmpty()) return plans
        if (sidebar.isEmpty()) return plans

        val mainFirst = mainAutoSegs.firstOrNull()?.lowercase()

        // 其它第一节分支：取 sidebar 前若干个（避免太多 DB query）
        val maxAltFirst = 10
        var rank = 1

        for (first in sidebar) {
            if (rank > maxAltFirst) break

            val f = first.lowercase().trim()
            if (f.isEmpty()) continue
            if (f == mainFirst) continue

            val firstCodeLen = T9Lookup.encodeLetters(f).length.coerceAtLeast(1)
            if (firstCodeLen > rawDigits.length) continue

            val remainDigits = rawDigits.substring(firstCodeLen)
            val shiftedCuts = shiftCutsAfterConsumingPrefix(manualCuts, firstCodeLen, remainDigits.length)

            val tailSegs = T9PathBuilder.buildAutoSegments(
                digits = remainDigits,
                manualCuts = shiftedCuts,
                dict = dict
            )

            val segs = ArrayList<String>(stackSegs.size + 1 + tailSegs.size).apply {
                addAll(stackSegs)
                add(f)
                addAll(tailSegs)
            }

            plans.add(PathPlan(rank = rank, segments = segs, text = segs.joinToString("'")))
            rank++
        }

        return plans
    }

    private fun shiftCutsAfterConsumingPrefix(
        cuts: List<Int>,
        consumedLen: Int,
        remainLen: Int
    ): List<Int> {
        if (cuts.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        for (c in cuts) {
            if (c <= consumedLen) continue
            val shifted = c - consumedLen
            if (shifted in 1 until remainLen) out.add(shifted)
        }
        out.sort()
        return out
    }

    private fun queryCandidatesByPlans(
        dict: Dictionary,
        plans: List<PathPlan>,
        limit: Int
    ): List<Candidate> {
        val outMap = LinkedHashMap<String, Candidate>(limit)
        for (p in plans) {
            if (p.text.isBlank()) continue
            val list = dict.getSuggestions(
                input = p.text,
                isT9 = false,
                isChineseMode = true
            )
            for (c in list) {
                if (!outMap.containsKey(c.word)) {
                    outMap[c.word] = c
                    if (outMap.size >= limit) break
                }
            }
            if (outMap.size >= limit) break
        }
        return outMap.values.toList()
    }

    private fun rerankCandidatesByBestPlan(
        candidates: ArrayList<Candidate>,
        plans: List<PathPlan>
    ) {
        if (candidates.isEmpty()) return
        if (plans.isEmpty()) return

        data class Best(val rank: Int, val matched: Int)

        val bestMap = HashMap<Candidate, Best>(candidates.size)

        for (c in candidates) {
            val py = c.pinyin?.lowercase()?.trim()
            val syllables = if (!py.isNullOrEmpty()) splitConcatPinyinToSyllables(py) else emptyList()

            var bestRank = Int.MAX_VALUE
            var bestMatched = 0

            if (syllables.isNotEmpty()) {
                for (p in plans) {
                    val m = countMatchedSegments(p.segments, syllables)
                    if (m > bestMatched || (m == bestMatched && p.rank < bestRank)) {
                        bestMatched = m
                        bestRank = p.rank
                    }
                }
            } else {
                bestRank = plans.first().rank
                bestMatched = 0
            }

            bestMap[c] = Best(rank = bestRank, matched = bestMatched)
        }

        candidates.sortWith(
            compareBy<Candidate>(
                { bestMap[it]?.rank ?: Int.MAX_VALUE },
                { -(bestMap[it]?.matched ?: 0) },
                { -it.priority },
                { it.word }
            )
        )
    }

    internal object T9PathBuilder {
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

        // CN-T9: 提交前先把当前 rawT9Digits 的“默认自动段”物化进 stack（等价于用户一路点默认下一节）
        if (dictEngine.isLoaded && session.rawT9Digits.isNotEmpty()) {
            val autoSegs = CnT9Handler.T9PathBuilder.buildAutoSegments(
                digits = session.rawT9Digits,
                manualCuts = session.t9ManualCuts,
                dict = dictEngine
            )
            for (seg in autoSegs) {
                val code = T9Lookup.encodeLetters(seg)
                session.onPinyinSidebarClick(seg, code)
            }
        }

        // 使用词库 syllables 作为“消费多少节”，这样 pickCandidate() 会按节推进、并支持逐节回退后的继续组合
        val stackSize = session.pinyinStack.size
        val consume = when {
            cand.syllables > 0 -> cand.syllables
            cand.word.isNotEmpty() -> cand.word.length
            else -> 1
        }.coerceAtLeast(1).coerceAtMost(stackSize)

        val candForPick = cand.copy(pinyinCount = consume)

        when (val r = session.pickCandidate(
            cand = candForPick,
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
