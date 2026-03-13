package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.model.Candidate

/**
 * CN-T9 候选排序稳定化器。
 *
 * 对应规则清单「稳定性规则（非常重要）」：
 *  - 相同输入下候选顺序应尽量稳定
 *  - 只有当用户显式选词或新增输入导致 score 发生足够大变化时才重排
 *  - 否则保持上一次排序，避免「肌肉记忆失效」
 *
 * 工作原理：
 *  1. 每次收到新排好序的候选列表（newRanked）时，
 *     与上一次快照（snapshot）对比每个候选的分数变化
 *  2. 若变化幅度在 STABILITY_THRESHOLD 以内，则保持上次顺序
 *     （把上次排名靠前的候选尽量保留在原位）
 *  3. 若变化超过阈值（或输入串变化），则接受新排序并更新快照
 *  4. 新出现的候选（上次没有的）直接插入新排序中的对应位置
 *
 * 触发强制重排的场景（直接接受新排序）：
 *  - rawDigits 发生变化（用户继续输入或退格）
 *  - 用户显式选词（外部调用 invalidate()）
 *  - 快照为空（首次排序）
 *  - 候选集合发生结构性变化（新增/删除候选数量超过阈值）
 *
 * 线程安全：
 *  - 仅在主线程（IME 事件线程）调用，无需加锁
 */
class CnT9CandidateStabilizer {

    companion object {
        /**
         * 综合分数变化门槛：两次排序中同一候选的「排名差」超过此值才视为"发生足够大变化"。
         * 设为 2：排名在相邻 ±2 以内视为稳定，不重排。
         * 可根据实际体验调整。
         */
        private const val RANK_SHIFT_THRESHOLD = 2

        /**
         * 结构性变化门槛：候选集合新增/消失的数量超过此比例则强制重排。
         * 设为 0.4：超过 40% 的候选发生变化时强制重排。
         */
        private const val STRUCTURAL_CHANGE_RATIO = 0.4f

        /**
         * 快照最大保留条目数，超出时直接接受新排序（避免内存浪费）。
         */
        private const val MAX_SNAPSHOT_SIZE = 60
    }

    /**
     * 上次稳定输出的候选顺序快照。
     * key = word，value = 上次排名（0-based）
     */
    private val snapshot = LinkedHashMap<String, Int>()

    /**
     * 上次处理时的 rawDigits，用于检测输入是否变化。
     */
    private var lastRawDigits: String = ""

    /**
     * 是否已被外部标记为需要强制重排（如用户选词后）。
     */
    private var forceNextRefresh: Boolean = true

    // ── 公开 API ──────────────────────────────────────────────────

    /**
     * 主入口：对新排好序的候选列表应用稳定化，返回最终输出顺序。
     *
     * @param newRanked   本次由 CnT9CandidateScorer 排好序的候选列表
     * @param rawDigits   当前 rawDigits（用于检测输入变化）
     * @return            稳定化后的候选列表（原地修改 newRanked 并返回）
     */
    fun stabilize(
        newRanked: ArrayList<Candidate>,
        rawDigits: String
    ): ArrayList<Candidate> {
        if (newRanked.isEmpty()) {
            updateSnapshot(newRanked, rawDigits)
            return newRanked
        }

        // 强制重排条件检查
        val shouldForce = forceNextRefresh
            || snapshot.isEmpty()
            || rawDigits != lastRawDigits
            || isStructuralChange(newRanked)

        if (shouldForce) {
            updateSnapshot(newRanked, rawDigits)
            forceNextRefresh = false
            return newRanked
        }

        // 应用稳定化：计算每个候选的「稳定排名」
        val stabilized = applyStability(newRanked)
        updateSnapshot(stabilized, rawDigits)
        return stabilized
    }

    /**
     * 外部通知：用户刚刚选词，下次必须强制重排。
     * 在 commitCandidateAt() 后调用。
     */
    fun invalidate() {
        forceNextRefresh = true
        snapshot.clear()
        lastRawDigits = ""
    }

    /**
     * 清空快照（session clear 时调用）。
     */
    fun reset() {
        forceNextRefresh = true
        snapshot.clear()
        lastRawDigits = ""
    }

    // ── 私有逻辑 ──────────────────────────────────────────────────

    /**
     * 应用稳定化：
     * 对 newRanked 中每个候选，查找其在 snapshot 中的旧排名；
     * 若新旧排名差在 RANK_SHIFT_THRESHOLD 以内，则保持旧排名位置；
     * 超出阈值或新出现的候选，保留新排名。
     *
     * 算法：
     *  1. 将 newRanked 按新排名建立索引
     *  2. 对每个候选计算「有效排名」= min(旧排名, 新排名 + threshold)
     *  3. 按有效排名稳定排序（stable sort 保证同分时保留新排序顺序）
     */
    private fun applyStability(newRanked: ArrayList<Candidate>): ArrayList<Candidate> {
        data class RankedEntry(
            val candidate: Candidate,
            val effectiveRank: Int,
            val newRank: Int
        )

        val entries = newRanked.mapIndexed { newRank, cand ->
            val oldRank = snapshot[cand.word]
            val effectiveRank = if (oldRank != null) {
                val shift = newRank - oldRank
                when {
                    // 排名提升超过阈值：允许提升（新分数明显更好）
                    shift < -RANK_SHIFT_THRESHOLD -> newRank
                    // 排名下降超过阈值：允许下降（新分数明显更差）
                    shift > RANK_SHIFT_THRESHOLD  -> newRank
                    // 排名变化在阈值内：保持旧排名（稳定化）
                    else                           -> oldRank
                }
            } else {
                // 新出现的候选，直接用新排名
                newRank
            }
            RankedEntry(cand, effectiveRank, newRank)
        }

        // 稳定排序：effectiveRank 优先，同名时用 newRank 保证确定性
        val sorted = entries.sortedWith(
            compareBy({ it.effectiveRank }, { it.newRank })
        )

        return ArrayList(sorted.map { it.candidate })
    }

    /**
     * 判断候选集合是否发生了结构性变化。
     * 超过 STRUCTURAL_CHANGE_RATIO 比例的候选发生新增/消失，则视为结构性变化。
     */
    private fun isStructuralChange(newRanked: List<Candidate>): Boolean {
        if (snapshot.isEmpty()) return true
        if (snapshot.size > MAX_SNAPSHOT_SIZE) return true

        val oldWords = snapshot.keys
        val newWords = newRanked.map { it.word }.toHashSet()

        val disappeared = oldWords.count { it !in newWords }
        val appeared = newWords.count { it !in oldWords }
        val totalChange = disappeared + appeared
        val baseSize = maxOf(oldWords.size, newWords.size).coerceAtLeast(1)

        return totalChange.toFloat() / baseSize >= STRUCTURAL_CHANGE_RATIO
    }

    /**
     * 更新内部快照。
     */
    private fun updateSnapshot(ranked: List<Candidate>, rawDigits: String) {
        snapshot.clear()
        ranked.forEachIndexed { index, cand ->
            if (index < MAX_SNAPSHOT_SIZE) {
                snapshot[cand.word] = index
            }
        }
        lastRawDigits = rawDigits
    }
}
