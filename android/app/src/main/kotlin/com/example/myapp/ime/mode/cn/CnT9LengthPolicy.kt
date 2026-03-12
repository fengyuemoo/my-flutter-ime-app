package com.example.myapp.ime.mode.cn

/**
 * CN-T9 候选词长度偏好策略。
 *
 * 对应规则清单「Length（长度偏好）」：
 *  - 九键更偏向词组效率，候选中优先给出 2–4 字常用词组
 *  - 保留单字入口以便生僻组合
 *  - 过长词组（>= 6 字）施加轻微惩罚，避免"看起来不靠谱"
 *
 * 返回值：0–100 的加分（越高越优先）。
 * 不返回负数，惩罚通过 CnT9PenaltyPolicy 处理。
 *
 * 设计原则：
 *  - 与 digits 长度联动：当用户只输入 2 个 digits 时，单字/双字更合适；
 *    输入 4+ digits 时，2–4 字词组应得到更高提升
 *  - digitLen <= 0 时退化为纯词长偏好
 */
object CnT9LengthPolicy {

    /**
     * 计算候选词的长度偏好加分。
     *
     * @param wordLength  候选词的汉字数（字符数）
     * @param digitLen    当前 rawDigits 的长度（0 表示不考虑 digits 联动）
     * @return            0–100 的加分
     */
    fun score(wordLength: Int, digitLen: Int): Int {
        if (wordLength <= 0) return 0

        // 基础分：按词长分区
        val baseScore = when (wordLength) {
            1    -> 20   // 单字保留入口，但不优先
            2    -> 80   // 双字词：最高优先
            3    -> 90   // 三字词：最高优先（与双字并列）
            4    -> 75   // 四字成语/常用词组
            5    -> 50   // 五字词组：中等
            else -> 20   // 六字及以上：轻微偏低
        }

        // digits 联动修正：
        //  - 输入较短（<= 2 digits）时适当压低长词的优势
        //  - 输入较长（>= 6 digits）时适当提升长词优势
        val digitBonus = when {
            digitLen <= 0 -> 0
            digitLen <= 2 -> when (wordLength) {
                1 -> 10; 2 -> 5; else -> -10
            }
            digitLen in 3..5 -> when (wordLength) {
                2, 3 -> 10; 4 -> 5; else -> 0
            }
            else -> when (wordLength) {
                3, 4 -> 10; 5 -> 5; else -> 0
            }
        }

        return (baseScore + digitBonus).coerceIn(0, 100)
    }

    /**
     * 词长是否在「九键黄金区间」（2–4 字）。
     * 供外部快速判断，不计算具体分值。
     */
    fun isPreferredLength(wordLength: Int): Boolean = wordLength in 2..4
}
