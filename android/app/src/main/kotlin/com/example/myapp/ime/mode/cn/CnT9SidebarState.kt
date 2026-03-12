package com.example.myapp.ime.mode.cn

import com.example.myapp.ime.compose.common.ComposingSession

/**
 * CN-T9 左侧音节栏焦点状态持有者。
 *
 * 焦点（focusedSegmentIndex）语义：
 *  -1    → 无焦点，sidebar 展示 rawDigits 当前前段的拼音候选（正常输入模式）
 *  >= 0  → 焦点锁定在某个已物化段（重切分 / 消歧模式）
 *
 * 生命周期事件：
 *  ┌─────────────────────────────────────────────────────────────────┐
 *  │ 用户操作                        → 调用方法                      │
 *  ├─────────────────────────────────────────────────────────────────┤
 *  │ 点击已物化的拼音段               → setFocus(index)               │
 *  │   同时 session.rollbackFrom(i)  →（调用方负责 rollback）         │
 *  │ 点选 sidebar 中一个拼音          → advanceFocus(session)         │
 *  │ 退格删掉一个 digit / 一个物化段  → retreatFocus() / clearFocus() │
 *  │ 选词上屏 / session.clear()      → clearFocus()                  │
 *  └─────────────────────────────────────────────────────────────────┘
 */
class CnT9SidebarState {

    /** 当前焦点段下标；-1 = 无焦点。*/
    var focusedSegmentIndex: Int = -1
        private set

    /**
     * 设置焦点到指定物化段（用户点击已物化段时）。
     * 调用方需同时调 session.rollbackMaterializedSegmentsFrom(index)。
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
     * 用户点选了 sidebar 中的一个音节后，焦点自动前进：
     *  - 若下一段仍是已物化段（即原本在重切分流程中） → 前进到下一段
     *  - 否则回到 -1（正常输入模式，rawDigits 继续接收输入）
     */
    fun advanceFocus(session: ComposingSession) {
        if (focusedSegmentIndex < 0) return
        val next = focusedSegmentIndex + 1
        focusedSegmentIndex = if (next < session.t9MaterializedSegments.size) next else -1
    }

    /**
     * 退格时反向移动焦点：
     *  - 若焦点 > 0 → 前退一段
     *  - 若焦点 == 0 → 清焦（回到正常模式）
     *  - 若焦点 == -1 → 什么都不做（正常模式退格由 session 处理）
     */
    fun retreatFocus() {
        if (focusedSegmentIndex > 0) {
            focusedSegmentIndex -= 1
        } else if (focusedSegmentIndex == 0) {
            focusedSegmentIndex = -1
        }
    }

    /** 当前是否处于重切分/消歧模式。*/
    val isDisambiguating: Boolean
        get() = focusedSegmentIndex >= 0
}
