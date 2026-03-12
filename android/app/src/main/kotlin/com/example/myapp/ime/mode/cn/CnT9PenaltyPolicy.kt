package com.example.myapp.ime.mode.cn

/**
 * CN-T9 候选词惩罚项策略。
 *
 * 对应规则清单「Penalty（惩罚项）」：
 *  - 对生僻词、低频且无用户学习支撑的长词组施加惩罚
 *  - 避免候选"看起来不靠谱"
 *
 * 返回值：0–100 的惩罚分（越高惩罚越重，排序时取负值参与打分）。
 *
 * 惩罚维度（叠加）：
 *  1. 低频惩罚   — priority 低于阈值时施加，priority 越低惩罚越重
 *  2. 长词低频   — 词长 >= 5 且 priority 低于阈值时额外惩罚（长词更容易"看起来生僻"）
 *  3. 无学习支撑 — userBoost == 0 且 priority 极低时施加小额惩罚
 *  4. 单字兜底   — 单字候选 priority 极低时施加轻微惩罚（保留入口但不抢前排）
 *
 * 设计原则：
 *  - 惩罚不应完全屏蔽候选，只是降低排名
 *  - 有用户学习支撑（userBoost > 0）的词豁免或减半惩罚
 *  - priority 来自字典，范围约 0–65535（越高越常用）
 */
object CnT9PenaltyPolicy {

    // priority 分级阈值（字典 priority 字段，值越大越常用）
    private const val PRIORITY_HIGH   = 40_000   // 高频词：不惩罚
    private const val PRIORITY_MID    = 15_000   // 中频词：轻惩罚
    private const val PRIORITY_LOW    = 5_000    // 低频词：中惩罚
    private const val PRIORITY_RARE   = 1_000    // 生僻词：重惩罚

    // 惩罚分常量
    private const val PENALTY_LOW_FREQ_LIGHT  = 10
    private const val PENALTY_LOW_FREQ_MID    = 25
    private const val PENALTY_LOW_FREQ_HEAVY  = 50
    private const val PENALTY_LONG_LOW_FREQ   = 20   // 长词低频额外惩罚
    private const val PENALTY_NO_LEARNING     = 8    // 无学习支撑小额惩罚
    private const val PENALTY_SINGLE_RARE     = 15   // 单字生僻额外惩罚

    /**
     * 计算候选词的惩罚分。
     *
     * @param priority    候选词的字典词频权重（越大越常用）
     * @param wordLength  候选词的汉字数
     * @param userBoost   用户学习加分（来自 CnT9UserChoiceStore.getBoost）
     * @return            0–100 的惩罚分；0 = 无惩罚
     */
    fun penalty(
        priority: Int,
        wordLength: Int,
        userBoost: Int
    ): Int {
        var total = 0

        // 1. 低频惩罚
        val freqPenalty = when {
            priority >= PRIORITY_HIGH -> 0
            priority >= PRIORITY_MID  -> PENALTY_LOW_FREQ_LIGHT
            priority >= PRIORITY_LOW  -> PENALTY_LOW_FREQ_MID
            priority >= PRIORITY_RARE -> PENALTY_LOW_FREQ_HEAVY
            else                      -> PENALTY_LOW_FREQ_HEAVY + 10
        }
        total += freqPenalty

        // 2. 长词低频额外惩罚
        if (wordLength >= 5 && priority < PRIORITY_MID) {
            total += PENALTY_LONG_LOW_FREQ
        }

        // 3. 无学习支撑小额惩罚（仅对低频词生效）
        if (userBoost == 0 && priority < PRIORITY_LOW) {
            total += PENALTY_NO_LEARNING
        }

        // 4. 单字生僻额外惩罚
        if (wordLength == 1 && priority < PRIORITY_RARE) {
            total += PENALTY_SINGLE_RARE
        }

        // 有用户学习支撑时惩罚减半（用户选过就说明他想要）
        if (userBoost > 0) {
            total = total / 2
        }

        return total.coerceIn(0, 100)
    }

    /**
     * 快速判断候选是否属于「高置信度无惩罚」词（高频 + 词长合适）。
     * 供外部跳过惩罚计算时使用。
     */
    fun isHighConfidence(priority: Int, wordLength: Int): Boolean =
        priority >= PRIORITY_HIGH && wordLength in 1..5
}
