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
        val text: String              // segments joined by '\n'
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

        // FINAL: 覆盖优先评分（防“新密灾难”）+ 词频优先（保证 一个/知道/侄儿 不会很靠后）+ 轻量长度偏好
        rerankByCoverageThenFreq(finalList, plans)

        // 排序完成后再截断展示长度（召回池可能 > 300）
        val displayLimit = 300
        if (finalList.size > displayLimit) {
            finalList.subList(displayLimit, finalList.size).clear()
        }

        // 顶部预览以“第 1 个候选词”作为基准对齐；若候选无法提供拼音，则回退到主预览路径。
        val composingPreviewCore = buildPreviewCoreByTopCandidate(
            top = finalList.firstOrNull(),
            plans = plans,
            fallback = previewPathText
        ) ?: ""

        // UI composing preview line: committedPrefix + 预览路径
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

        // 其它第一节分支：短输入时多取一些，避免常用组合（如 yi'ge）因为分支不足而不出现。
        val maxAltFirst = if (rawDigits.length <= 4) 30 else 10
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
        // 关键：每条 plan 都必须有机会贡献候选，避免“前几个 plan 塞满池子，后续 plan 饿死”
        // limit 是最终展示上限（build 里会截断），这里构建更大的“召回池”
        val perPlanLimit = when {
            plans.size <= 5 -> 160
            plans.size <= 12 -> 120
            else -> 80
        }

        val outMap = LinkedHashMap<String, Candidate>(limit * 4)

        for (p in plans) {
            if (p.text.isBlank()) continue
            val list = dict.getSuggestions(
                input = p.text,
                isT9 = false,
                isChineseMode = true
            )

            // 每条 plan 限额，保证公平
            var taken = 0
            for (c in list) {
                if (!outMap.containsKey(c.word)) {
                    outMap[c.word] = c
                    taken++
                    if (taken >= perPlanLimit) break
                }
            }
        }

        return outMap.values.toList()
    }

    // ---------- FINAL: “覆盖优先 + 词频优先 + 轻量长度偏好”排序 ----------

    private data class PlanInfo(
        val plan: PathPlan,
        val segDigits: IntArray,      // each segment's digit length
        val prefixDigits: IntArray,   // prefixDigits[k] = digits consumed by first k segments
        val totalDigits: Int
    )

    private enum class SegKind { STRONG_FULL, MEDIUM_INITIAL, WEAK }

    private data class Explain(
        val uncoveredDigits: Int,
        val matchedDigits: Int,
        val strongFull: Int,
        val mediumInitial: Int,
        val weakSeg: Int
    )

    private fun buildPlanInfos(plans: List<PathPlan>): List<PlanInfo> {
        val out = ArrayList<PlanInfo>(plans.size)
        for (p in plans) {
            val segs = p.segments
            val segDigits = IntArray(segs.size)
            val prefix = IntArray(segs.size + 1)
            prefix[0] = 0

            for (i in segs.indices) {
                val len = T9Lookup.encodeLetters(segs[i]).length.coerceAtLeast(1)
                segDigits[i] = len
                prefix[i + 1] = prefix[i] + len
            }

            out.add(
                PlanInfo(
                    plan = p,
                    segDigits = segDigits,
                    prefixDigits = prefix,
                    totalDigits = prefix.last()
                )
            )
        }
        return out
    }

    private fun matchKind(segLower: String, sylLower: String): SegKind? {
        // 1) 完整音节：必须完全相等；但 a/o/e 这种长度=1 的“完整音节”在 T9 里过于宽松，算 WEAK
        if (pinyinSet.contains(segLower)) {
            if (sylLower != segLower) return null
            return if (segLower.length >= 2) SegKind.STRONG_FULL else SegKind.WEAK
        }

        // 2) zh/ch/sh：声母节（中等强度）
        if (segLower == "zh" || segLower == "ch" || segLower == "sh") {
            return if (sylLower.startsWith(segLower)) SegKind.MEDIUM_INITIAL else null
        }

        // 3) 单字母节：只匹配音节首字母（弱）
        if (segLower.length == 1 && segLower[0] in 'a'..'z') {
            return if (sylLower.startsWith(segLower)) SegKind.WEAK else null
        }

        return null
    }

    private fun explainOnPlan(info: PlanInfo, syllables: List<String>): Explain {
        val segs = info.plan.segments
        val n = minOf(segs.size, syllables.size)

        var strong = 0
        var medium = 0
        var weak = 0
        var matchedSegs = 0

        for (i in 0 until n) {
            val seg = segs[i].lowercase()
            val syl = syllables[i].lowercase()

            val kind = matchKind(seg, syl) ?: break
            matchedSegs++

            when (kind) {
                SegKind.STRONG_FULL -> strong++
                SegKind.MEDIUM_INITIAL -> medium++
                SegKind.WEAK -> weak++
            }
        }

        val matchedDigits = info.prefixDigits[matchedSegs]
        val uncovered = (info.totalDigits - matchedDigits).coerceAtLeast(0)

        return Explain(
            uncoveredDigits = uncovered,
            matchedDigits = matchedDigits,
            strongFull = strong,
            mediumInitial = medium,
            weakSeg = weak
        )
    }

    private fun bestExplain(planInfos: List<PlanInfo>, cand: Candidate): Explain? {
        val py = cand.pinyin?.lowercase()?.trim() ?: return null
        val syl = splitConcatPinyinToSyllables(py)
        if (syl.isEmpty()) return null

        var best: Explain? = null

        for (info in planInfos) {
            val e = explainOnPlan(info, syl)

            // 选择“最佳解释”：先覆盖度（uncovered）最小，再强音节更多，再中等更多，再弱段更少，再解释 digits 更长
            if (best == null
                || e.uncoveredDigits < best!!.uncoveredDigits
                || (e.uncoveredDigits == best!!.uncoveredDigits && e.strongFull > best!!.strongFull)
                || (e.uncoveredDigits == best!!.uncoveredDigits && e.strongFull == best!!.strongFull && e.mediumInitial > best!!.mediumInitial)
                || (e.uncoveredDigits == best!!.uncoveredDigits && e.strongFull == best!!.strongFull && e.mediumInitial == best!!.mediumInitial && e.weakSeg < best!!.weakSeg)
                || (e.uncoveredDigits == best!!.uncoveredDigits
                    && e.strongFull == best!!.strongFull
                    && e.mediumInitial == best!!.mediumInitial
                    && e.weakSeg == best!!.weakSeg
                    && e.matchedDigits > best!!.matchedDigits)
            ) {
                best = e
            }
        }

        return best
    }

    private fun rerankByCoverageThenFreq(
        candidates: ArrayList<Candidate>,
        plans: List<PathPlan>
    ) {
        if (candidates.isEmpty()) return
        if (plans.isEmpty()) return

        val planInfos = buildPlanInfos(plans)

        val explainCache = HashMap<Candidate, Explain?>(candidates.size)
        val sylCountCache = HashMap<Candidate, Int>(candidates.size)

        fun syllableCount(c: Candidate): Int {
            sylCountCache[c]?.let { return it }
            val n = when {
                c.syllables > 0 -> c.syllables
                !c.pinyin.isNullOrBlank() -> splitConcatPinyinToSyllables(c.pinyin.lowercase().trim()).size
                else -> 0
            }
            sylCountCache[c] = n
            return n
        }

        for (c in candidates) {
            explainCache[c] = bestExplain(planInfos, c)
        }

        candidates.sortWith(
            compareBy<Candidate>(
                { explainCache[it]?.uncoveredDigits ?: Int.MAX_VALUE },   // 1) 覆盖度优先（硬约束，防“新密灾难”）
                { -(explainCache[it]?.strongFull ?: 0) },                 // 2) 强音节越多越靠前
                { -(explainCache[it]?.mediumInitial ?: 0) },              // 3) zh/ch/sh 声母节作为次优
                { -it.priority },                                         // 4) 词频（常用词靠前：一个/知道/侄儿）
                { -syllableCount(it) },                                   // 5) 轻量偏向多音节
                { -it.word.length },                                      // 6) 轻量偏向长词
                { explainCache[it]?.weakSeg ?: Int.MAX_VALUE },           // 7) 弱段越少越好（但不压过词频）
                { -(explainCache[it]?.matchedDigits ?: 0) },              // 8) 解释 digits 越长越好
                { it.word }
            )
        )
    }

    // 顶部预览以“第 1 个候选词”对齐；并用最贴合的 plan 补齐后续段（如果有）。
    private fun buildPreviewCoreByTopCandidate(
        top: Candidate?,
        plans: List<PathPlan>,
        fallback: String?
    ): String? {
        if (top == null) return fallback

        val py = top.pinyin?.lowercase()?.trim()
        if (py.isNullOrEmpty()) return fallback

        val syl = splitConcatPinyinToSyllables(py)
        if (syl.isEmpty()) return fallback

        var bestPlan: PathPlan? = null
        var bestMatched = -1
        var bestRank = Int.MAX_VALUE

        for (p in plans) {
            val m = countMatchedSegments(p.segments, syl)
            if (m > bestMatched || (m == bestMatched && p.rank < bestRank)) {
                bestMatched = m
                bestRank = p.rank
                bestPlan = p
            }
        }

        val out = ArrayList<String>(syl.size + (bestPlan?.segments?.size ?: 0))
        out.addAll(syl)

        if (bestPlan != null && bestPlan.segments.size > syl.size) {
            out.addAll(bestPlan.segments.drop(syl.size))
        }

        return out.joinToString("'")
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

        // 目标：只物化“本次需要消费的节数”，不要把剩余 rawT9Digits 全部吃掉，否则 sidebar 会变空。
        val desiredConsume = when {
            cand.syllables > 0 -> cand.syllables
            cand.word.isNotEmpty() -> cand.word.length
            else -> 1
        }.coerceAtLeast(1)

        // 只补齐到 desiredConsume；每次只物化 1 个 segment，保留剩余 digits 给下一节 sidebar。
        if (dictEngine.isLoaded) {
            while (session.pinyinStack.size < desiredConsume && session.rawT9Digits.isNotEmpty()) {
                val nextSeg = CnT9Handler.T9PathBuilder.buildAutoSegments(
                    digits = session.rawT9Digits,
                    manualCuts = session.t9ManualCuts,
                    dict = dictEngine
                ).firstOrNull() ?: break

                val code = T9Lookup.encodeLetters(nextSeg)
                if (code.isEmpty()) break

                session.onPinyinSidebarClick(nextSeg, code)
            }
        }

        val stackSize = session.pinyinStack.size
        if (stackSize <= 0) {
            updateCandidates()
            updateComposingView()
            return
        }

        val consume = desiredConsume.coerceAtMost(stackSize).coerceAtLeast(1)
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
