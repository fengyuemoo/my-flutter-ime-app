package com.example.myapp.ime.mode.cn

import com.example.myapp.ime.compose.common.ComposingSession

/**
 * CN-T9 左侧音节栏焦点 + 锁定状态持有者。
 *
 * 焦点（focusedSegmentIndex）语义：
 *  -1    → 无焦点，sidebar 展示 rawDigits 当前前段的拼音候选（正常输入模式）
 *  >= 0  → 焦点锁定在某个已物化段（重切分 / 消歧模式）
 *
 * 锁定（lockMap）语义（对应规则清单「音节栏的锁定策略」）：
 *  - 用户手动点选 sidebar 中某个音节并通过 advanceFocus() 确认后，该段自动上锁
 *  - 锁定段在后续输入时不被重新解析，只重算锁后部分
 *  - 退格删除某段时通过 onSegmentRemoved() 自动下移锁定下标
 *  - session.clear() 时通过 clearAll() 全部解锁
 *
 * 生命周期事件：
 *  ┌──────────────────────────────────────────────────────────────────────┐
 *  │ 用户操作                          → 调用方法                          │
 *  ├──────────────────────────────────────────────────────────────────────┤
 *  │ 点击已物化的拼音段                  → setFocus(index)                  │
 *  │   同时 session.rollbackFrom(i)    →（调用方负责 rollback）             │
 *  │   rollback 后批量清锁              → clearLocksFrom(index)            │
 *  │ 点选 sidebar 中一个拼音（确认）     → advanceFocus(session)             │
 *  │   → 自动锁定刚确认的段              （内部调用 lockMap.lock）           │
 *  │ 退格删掉一个 digit / 一个物化段     → retreatFocus() / onSegmentRemoved│
 *  │ 选词上屏 / session.clear()        → clearAll()                       │
 *  └──────────────────────────────────────────────────────────────────────┘
 */
class CnT9SidebarState {

    /** 当前焦点段下标；-1 = 无焦点。*/
    var focusedSegmentIndex: Int = -1
        private set

    /** 段锁定状态表，与焦点解耦独立管理。*/
    val lockMap = CnT9SegmentLockMap()

    // ── 焦点管理 ─────────────────────────────────────────────────────────

    /**
     * 设置焦点到指定物化段（用户点击已物化段时）。
     * 调用方需同时：
     *  1. 调 session.rollbackMaterializedSegmentsFrom(index)
     *  2. 调 clearLocksFrom(index) 清除该段及之后的锁定
     */
    fun setFocus(index: Int) {
        focusedSegmentIndex = index.coerceAtLeast(-1)
    }

    /**
     * 清除焦点（选词上屏后 / session clear 时）。
     */
    fun clearFocus() {
        focusedSegmentIndex = -1
    }

    /**
     * 用户点选了 sidebar 中的一个音节后，当前焦点段自动上锁，焦点前进：
     *  - 先锁定当前焦点段
     *  - 若下一段仍是已物化段 → 前进到下一段（继续消歧）
     *  - 否则回到 -1（正常输入模式，rawDigits 继续接收输入）
     */
    fun advanceFocus(session: ComposingSession) {
        if (focusedSegmentIndex < 0) return

        // 锁定刚刚确认的段
        lockMap.lock(focusedSegmentIndex)

        val next = focusedSegmentIndex + 1
        focusedSegmentIndex =
            if (next < session.t9MaterializedSegments.size) next else -1
    }

    /**
     * 退格时反向移动焦点：
     *  - 焦点 > 0 → 前退一段
     *  - 焦点 == 0 → 清焦（回到正常模式）
     *  - 焦点 == -1 → 什么都不做（正常模式退格由 session 处理）
     */
    fun retreatFocus() {
        when {
            focusedSegmentIndex > 0 -> focusedSegmentIndex -= 1
            focusedSegmentIndex == 0 -> focusedSegmentIndex = -1
        }
    }

    // ── 锁定管理 ─────────────────────────────────────────────────────────

    /**
     * 手动锁定当前焦点段（不前进焦点）。
     * 用于用户明确确认某段但不立即继续消歧的场景。
     */
    fun lockFocusedSegment() {
        if (focusedSegmentIndex >= 0) {
            lockMap.lock(focusedSegmentIndex)
        }
    }

    /**
     * 查询某段是否已锁定。
     */
    fun isLocked(index: Int): Boolean = lockMap.isLocked(index)

    /**
     * 某段被退格删除后，通知 lockMap 下移索引。
     * 对应规则清单「音节栏的回退规则」中删空某段的情况。
     */
    fun onSegmentRemoved(removedIndex: Int) {
        lockMap.onSegmentRemoved(removedIndex)
        // 焦点同步：若焦点在被删段之后，下移一位
        if (focusedSegmentIndex > removedIndex) {
            focusedSegmentIndex -= 1
        } else if (focusedSegmentIndex == removedIndex) {
            focusedSegmentIndex = -1
        }
    }

    /**
     * 从 fromIndex 开始（含）批量清锁。
     * 用于 rollbackMaterializedSegmentsFrom 后同步清除已失效的锁定。
     */
    fun clearLocksFrom(fromIndex: Int) {
        lockMap.clearFrom(fromIndex)
    }

    /**
     * 清除全部焦点与锁定（session.clear() 时调用）。
     */
    fun clearAll() {
        focusedSegmentIndex = -1
        lockMap.clearAll()
    }

    // ── 状态查询 ─────────────────────────────────────────────────────────

    /** 当前是否处于重切分/消歧模式。*/
    val isDisambiguating: Boolean
        get() = focusedSegmentIndex >= 0
}
