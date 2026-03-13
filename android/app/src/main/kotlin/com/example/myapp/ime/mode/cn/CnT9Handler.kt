package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionSnapshot
import com.example.myapp.ime.mode.ImeModeHandler
import java.util.Locale

object CnT9Handler : ImeModeHandler {

    private const val MAX_DISPLAY_CANDIDATES = 120

    // ── 公开入口 1：来自主线程 Session（原有接口，零破坏）────────────────────

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output = build(
        session         = session,
        dictEngine      = dictEngine,
        singleCharMode  = singleCharMode,
        userChoiceStore = null,
        contextWindow   = null,
        sidebarState    = null
    )

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        sidebarState: CnT9SidebarState?
    ): ImeModeHandler.Output {
        // 将可变 Session 快照为不可变数据，统一走 buildInternal()
        return buildInternal(
            snapshot        = session.buildSnapshot(),
            dictEngine      = dictEngine,
            singleCharMode  = singleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow,
            sidebarState    = sidebarState
        )
    }

    // ── 公开入口 2：来自后台线程快照（新增，线程安全）────────────────────────

    /**
     * 与 [build] 对称的快照版入口，接受不可变的 [ComposingSessionSnapshot]。
     *
     * 使用场景：
     *  1. 主线程在按键处理完毕后立即调用 `session.buildSnapshot()` 并投递到后台线程池
     *  2. 后台线程持有快照，调用此方法完成全部候选计算（planAll / queryCandidates / sort）
     *  3. 计算结果通过 Handler.post 回主线程后再更新 UI
     *
     * 线程安全保证：
     *  - [ComposingSessionSnapshot] 是 data class，所有字段均为 List（只读），完全不可变
     *  - 此方法本身不访问任何主线程可变状态
     *  - [dictEngine] / [userChoiceStore] / [contextWindow] 需调用方自行保证线程安全
     *    （通常 dictEngine 为只读，userChoiceStore 读操作有内存可见性保证）
     *
     * @param snapshot        来自 [ComposingSession.buildSnapshot] 的只读快照
     * @param dictEngine      字典引擎（只读使用）
     * @param singleCharMode  是否单字模式
     * @param userChoiceStore 用户学习权重存储（getBoost 为只读，线程安全）
     * @param contextWindow   上下文 bigram 偏置（只读）
     * @param sidebarState    当前 sidebar 状态（由调用方在主线程读取后作为参数传入）
     */
    fun buildFromSnapshot(
        snapshot: ComposingSessionSnapshot,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore? = null,
        contextWindow: CnT9ContextWindow? = null,
        sidebarState: CnT9SidebarState? = null
    ): ImeModeHandler.Output {
        return buildInternal(
            snapshot        = snapshot,
            dictEngine      = dictEngine,
            singleCharMode  = singleCharMode,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow,
            sidebarState    = sidebarState
        )
    }

    // ── 内部核心实现（两个公开入口共用）────────────────────────────────────

    private fun buildInternal(
        snapshot: ComposingSessionSnapshot,
        dictEngine: Dictionary,
        singleCharMode: Boolean,
        userChoiceStore: CnT9UserChoiceStore?,
        contextWindow: CnT9ContextWindow?,
        sidebarState: CnT9SidebarState?
    ): ImeModeHandler.Output {
        val rawDigits           = snapshot.rawT9Digits
        val stackSegs           = snapshot.pinyinStack.map { it.lowercase(Locale.ROOT) }
        val focusedSegmentIndex = sidebarState?.focusedSegmentIndex ?: -1

        // ── Sidebar（一次性构建）────────────────────────────────────────
        // Sidebar 仍需要访问 session；此处从快照重建最小所需信息。
        // SidebarBuilder 只读 rawDigits 与 pinyinStack，与快照字段对齐。
        val sidebarResult = CnT9SidebarBuilder.buildFromSnapshot(
            dictEngine          = dictEngine,
            snapshot            = snapshot,
            focusedSegmentIndex = focusedSegmentIndex,
            rawDigits           = rawDigits
        )

        // ── 锁定段信息 ──────────────────────────────────────────────────
        val lockedSegmentIndices: List<Int> =
            sidebarState?.lockMap?.lockedSnapshot ?: emptyList()

        // ── 候选规划 ────────────────────────────────────────────────────
        val autoPlans = if (dictEngine.isLoaded && rawDigits.isNotEmpty()) {
            CnT9SentencePlanner.planAll(
                digits     = rawDigits,
                manualCuts = snapshot.t9ManualCuts,
                dict       = dictEngine
            )
        } else emptyList()

        val plans = buildPlans(stackSegs, autoPlans, lockedSegmentIndices)

        val queried = if (dictEngine.isLoaded && plans.isNotEmpty()) {
            CnT9CandidateFilter.queryCandidates(dictEngine, plans)
        } else emptyList()

        val filtered = if (singleCharMode) queried.filter { it.word.length == 1 } else queried

        val finalList = ArrayList<Candidate>(filtered)

        val scoreCache = CnT9CandidateScorer.buildScoreCache(
            candidates      = finalList,
            plans           = plans,
            rawDigits       = rawDigits,
            lockedIndices   = lockedSegmentIndices,
            userChoiceStore = userChoiceStore,
            contextWindow   = contextWindow
        )

        CnT9CandidateScorer.sortCandidates(finalList, scoreCache)

        if (finalList.size > MAX_DISPLAY_CANDIDATES) {
            finalList.subList(MAX_DISPLAY_CANDIDATES, finalList.size).clear()
        }

        // ── Fallback：词库查无结果时提供 Unicode CJK 兜底 ───────────────
        if (finalList.isEmpty() && rawDigits.isNotEmpty()) {
            val fallbacks = CnT9UnicodeFallback.buildFallbackCandidates(rawDigits, dictEngine)
            if (fallbacks.isNotEmpty()) {
                finalList.addAll(fallbacks)
            } else {
                finalList.add(
                    Candidate(
                        word          = rawDigits,
                        input         = rawDigits,
                        priority      = 0,
                        matchedLength = 0,
                        pinyinCount   = 0,
                        pinyin        = null,
                        syllables     = 0,
                        acronym       = null
                    )
                )
            }
        }

        return ImeModeHandler.Output(
            candidates        = finalList,
            pinyinSidebar     = sidebarResult.syllables,
            sidebarTitle      = sidebarResult.title,
            resegmentPaths    = sidebarResult.resegmentPaths,
            composingPreviewText = null,
            enterCommitText   = null
        )
    }

    // ── 私有辅助 ─────────────────────────────────────────────────────────────

    /**
     * 构建候选规划列表。
     *
     * 锁定策略：
     *  - 已锁定的 stackSegs 段不参与重算，直接作为前缀保留
     *  - autoPlans 接在锁定段之后拼接
     *  - 未锁定的 stackSegs 段同样作为前缀（与原逻辑一致）
     */
    private fun buildPlans(
        stackSegs: List<String>,
        autoPlans: List<CnT9SentencePlanner.PathPlan>,
        lockedSegmentIndices: List<Int>
    ): List<CnT9SentencePlanner.PathPlan> {
        if (stackSegs.isEmpty() && autoPlans.isEmpty()) return emptyList()

        if (autoPlans.isEmpty()) {
            return listOf(
                CnT9SentencePlanner.PathPlan(
                    rank           = 0,
                    segments       = stackSegs,
                    consumedDigits = 0
                )
            )
        }

        return autoPlans.map { auto ->
            CnT9SentencePlanner.PathPlan(
                rank           = auto.rank,
                segments       = stackSegs + auto.segments,
                consumedDigits = auto.consumedDigits
            )
        }
    }
}
