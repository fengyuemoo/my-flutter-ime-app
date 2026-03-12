package com.example.myapp.ime.mode.cn

/**
 * CN-T9 音节段锁定状态表。
 *
 * 职责：
 *  - 维护 segmentIndex → isLocked 的映射
 *  - 与 CnT9SidebarState 解耦，单独管理锁定生命周期
 *
 * 锁定语义（对应规则清单「音节栏的锁定策略」）：
 *  - 用户手动点选 sidebar 中某个音节并确认后，该段被锁定
 *  - 后续继续输入新 digits 时，锁定段不参与重新解析
 *  - 只有锁定段之后的 rawDigits 部分重新走 SentencePlanner
 *  - 用户退格删除某段、或 session.clear() 时，该段锁定自动解除
 */
class CnT9SegmentLockMap {

    private val lockedIndices = HashSet<Int>()

    /** 锁定指定段。*/
    fun lock(index: Int) {
        if (index >= 0) lockedIndices.add(index)
    }

    /** 解锁指定段。*/
    fun unlock(index: Int) {
        lockedIndices.remove(index)
    }

    /** 查询某段是否已锁定。*/
    fun isLocked(index: Int): Boolean = lockedIndices.contains(index)

    /**
     * 当某段被删除（退格或 rollback）后，需要把该段及其之后所有段的锁定都移除，
     * 并将高于 removedIndex 的 index 整体下移一位。
     *
     * 例：原来锁定了 [1, 2, 3]，删除 index=1 后，变为锁定 [1, 2]（原 2→1, 3→2）。
     */
    fun onSegmentRemoved(removedIndex: Int) {
        val toKeep = lockedIndices
            .filter { it < removedIndex }
            .toHashSet()
        val shifted = lockedIndices
            .filter { it > removedIndex }
            .map { it - 1 }
        lockedIndices.clear()
        lockedIndices.addAll(toKeep)
        lockedIndices.addAll(shifted)
    }

    /**
     * 从 fromIndex 开始（含），清除该段及之后所有段的锁定。
     * 用于 rollbackMaterializedSegmentsFrom 时批量清锁。
     */
    fun clearFrom(fromIndex: Int) {
        lockedIndices.removeAll { it >= fromIndex }
    }

    /** 清除全部锁定（session.clear() 时调用）。*/
    fun clearAll() {
        lockedIndices.clear()
    }

    /** 当前所有已锁定段的下标快照（升序）。*/
    val lockedSnapshot: List<Int>
        get() = lockedIndices.sorted()

    /** 已锁定段数量。*/
    val size: Int get() = lockedIndices.size
}
