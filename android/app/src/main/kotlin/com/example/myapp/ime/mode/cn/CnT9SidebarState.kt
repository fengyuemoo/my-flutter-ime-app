package com.example.myapp.ime.mode.cn

import com.example.myapp.ime.compose.common.ComposingSession

/**
 * CN-T9 左侧音节栏焦点状态管理。
 *
 * 焦点（focusedSegmentIndex）语义：
 *  - -1      → 无焦点，sidebar 展示 rawDigits 前段的拼音候选（正常输入模式）
 *  - >= 0    → 焦点在某个已物化的段（重切分/消歧模式）
 *
 * 生命周期：
 *  - appendDigit / backspace 时由 CnT9CandidateEngine 调 advanceFocus() / retreatFocus()
 *  - 选词上屏后调 clearFocus()
 *  - 点击物化段调 setFocus(index)，同时触发 rollback
 */
class CnT9SidebarState {

    /** 当前焦点段下标，-1 表示无焦点（关注 rawDigits）。*/
    var focusedSegmentIndex: Int = -1
        private set

    /**
     * 设置焦点到指定物化段（点击已物化段时调用）。
     * 调用方需同时调 session.rollbackMaterializedSegmentsFrom(index)。
     */
    fun setFocus(index: Int) {
        focusedSegmentIndex = index.coerceAtLeast(-1)
    }

    /**
     * 清除焦点（选词上屏后 / 整体 clear 时调用）。
     */
    fun clearFocus() {
        focusedSegmentIndex = -1
    }

    /**
     * 用户点选了一个 sidebar 音节后，焦点自动前进到下一物化段（若有），
     * 否则回到 rawDigits 模式（-1）。
     *
     * @param session 当前 composing session（需获取物化段数量）
     */
    fun advanceFocus(session: ComposingSession) {
        if (focusedSegmentIndex < 0) return  // 正常模式，不前进
        val nextIndex = focusedSegmentIndex + 1
        focusedSegmentIndex = if (nextIndex < session.t9MaterializedSegments.size) {
            nextIndex
        } else {
            -1  // 超出物化段范围，回到正常模式
        }
    }

    /**
     * 退格时，若焦点在物化段，向前退一格（或清焦）。
     */
    fun retreatFocus() {
        if (focusedSegmentIndex > 0) {
            focusedSegmentIndex -= 1
        } else {
            focusedSegmentIndex = -1
        }
    }

    /**
     * 当前是否处于"重切分/消歧模式"（有焦点在某个物化段）。
     */
    fun isDisambiguating(): Boolean = focusedSegmentIndex >= 0
}
