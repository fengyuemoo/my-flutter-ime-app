package com.example.myapp.ime.mode.cn

/**
 * CN-T9 上下文窗口：记录最近已上屏词/字，供候选排序提供 bigram 偏置加分。
 *
 * 对应规则清单「Context（上下文）」：
 *  - 用「已上屏内容的最后 1–2 个词/字」来做下一词偏置
 *  - 如果当前是句首，偏向常用开头词
 *
 * 设计原则：
 *  - 只在内存维护，不持久化（会话级上下文）
 *  - 加分逻辑分三层：句首加分 / bigram 接续加分 / 重复抑制
 *  - 后续可替换 getContextBoost() 内部实现接入真实 bigram 表，外部接口不变
 */
class CnT9ContextWindow {

    companion object {

        private const val WINDOW_SIZE = 2

        // 加分常量
        private const val BIGRAM_STRONG_BOOST  = 40   // 强接续（量词/助词/补语等固定搭配）
        private const val BIGRAM_WEAK_BOOST    = 20   // 弱接续（普通非重复接续）
        private const val REPEAT_PENALTY       = -15  // 与上文末字重复首字（抑制重复词）
        private const val HEAD_BOOST_MAX       = 35   // 句首高频词最大加分

        /**
         * 常用句首字集合（高频开头字，偏向这些字开头的候选词）。
         * 覆盖：人称、指示、动词、助动词、连词、时间词等高频句首模式。
         */
        private val HEAD_FIRST_CHARS = setOf(
            '我', '你', '他', '她', '它', '我们', '你们',  // 人称（取首字）
            '这', '那', '此', '该',                        // 指示
            '是', '有', '在', '会', '能', '要', '想', '让',// 动词/助动词
            '因', '但', '而', '所', '如', '虽', '既', '不',// 连词/副词
            '今', '明', '昨', '已', '正', '将', '曾',      // 时间/状态
            '请', '希', '感', '非', '对', '当', '从', '为' // 其他高频
        ).map { it.toString().first() }.toHashSet()

        /**
         * 强接续字对（前字 → 后字首字集合）：
         * 这些组合在汉语中高频固定搭配，命中时给予更高加分。
         * 示例：「的」后接名词首字、「了」后接动词、「在」后接地点词等。
         *
         * 简化版：只维护最高频的前字集合，后字不限定（命中前字即加分）。
         */
        private val STRONG_PREV_CHARS = setOf(
            '的', '地', '得', '了', '着', '过',  // 结构助词/动态助词
            '和', '与', '及', '或',               // 并列连词
            '在', '从', '到', '向', '对', '把',   // 介词
            '是', '有', '被', '让', '使'           // 高频动词
        )
    }

    // 最近上屏词列表，index 0 = 最旧，last = 最新
    private val window = ArrayDeque<String>(WINDOW_SIZE + 1)

    /**
     * 上屏一个词后调用，更新上下文窗口。
     */
    fun record(committedWord: String) {
        if (committedWord.isEmpty()) return
        window.addLast(committedWord)
        while (window.size > WINDOW_SIZE) window.removeFirst()
    }

    /**
     * 清空上下文（换行/焦点切换时调用）。
     */
    fun clear() {
        window.clear()
    }

    /**
     * 获取候选词的上下文加分。
     *
     * 三层策略：
     *  1. 句首（窗口为空）→ getHeadBoost()
     *  2. 有上文 → bigram 接续打分
     *     a. 上文末字在 STRONG_PREV_CHARS → BIGRAM_STRONG_BOOST
     *     b. 候选首字 ≠ 上文末字（普通非重复）→ BIGRAM_WEAK_BOOST
     *     c. 候选首字 == 上文末字（重复抑制）→ REPEAT_PENALTY
     *
     * @param word 候选汉字/词
     * @return 加分（可为负，表示抑制）
     */
    fun getContextBoost(word: String): Int {
        if (word.isEmpty()) return 0

        // 句首
        if (window.isEmpty()) return getHeadBoost(word)

        val lastCommitted = window.last()
        val lastChar = lastCommitted.lastOrNull() ?: return 0
        val firstChar = word.firstOrNull() ?: return 0

        return when {
            // 强接续：上文末字是高频结构词
            lastChar in STRONG_PREV_CHARS -> BIGRAM_STRONG_BOOST
            // 重复抑制：候选首字与上文末字相同
            firstChar == lastChar -> REPEAT_PENALTY
            // 弱接续：普通非重复接续
            else -> BIGRAM_WEAK_BOOST
        }
    }

    /**
     * 句首偏向常用开头词的加分。
     *
     * 规则：
     *  - 候选词首字在 HEAD_FIRST_CHARS 集合中 → HEAD_BOOST_MAX
     *  - 词长 >= 2 且首字不在集合 → HEAD_BOOST_MAX / 2（句首偏长词基础分）
     *  - 单字且首字不在集合 → 0
     */
    fun getHeadBoost(word: String): Int {
        if (word.isEmpty()) return 0
        val firstChar = word.firstOrNull() ?: return 0
        return when {
            firstChar in HEAD_FIRST_CHARS -> HEAD_BOOST_MAX
            word.length >= 2              -> HEAD_BOOST_MAX / 2
            else                          -> 0
        }
    }

    /**
     * 是否处于句首（上下文窗口为空）。
     */
    fun isHeadOfSentence(): Boolean = window.isEmpty()

    /**
     * 获取上文最后一个词（调试/测试用）。
     */
    fun getPrevWord(): String? = window.lastOrNull()

    /**
     * 获取上下文窗口内容快照（调试用）。
     */
    fun snapshot(): List<String> = window.toList()
}
