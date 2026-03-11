package com.example.myapp.ime.mode.cn

/**
 * 上下文窗口：记录最近 WINDOW_SIZE 个已上屏词/字，
 * 供候选排序时提供 bigram 偏置加分。
 *
 * 设计原则：
 *  - 只在内存维护，不持久化（会话级上下文）
 *  - 加分逻辑简单：若候选词的第一个字与上文末字有共现记录则加分
 *  - 句首（窗口为空）时偏向常用开头词，通过 headBoost 字段实现
 */
class CnT9ContextWindow {

    companion object {
        private const val WINDOW_SIZE = 2
        private const val BIGRAM_BOOST = 30          // bigram 命中加分
        private const val HEAD_BOOST_FACTOR = 0.5f   // 句首降权（未来可扩展为句首词库）
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
     * 当前策略：
     *  1. 如果窗口非空，取最后一个上屏词的末字 lastChar
     *  2. 若候选词以常见"接续字"开头，且 lastChar 是常见搭配前缀，给予加分
     *     （简化版：只要 lastChar 与候选首字不同，就给一个基础分，
     *       避免重复选同一字；真实 bigram 表可后续接入）
     *  3. 窗口为空（句首）时，对长度 >= 2 的词给小加分（句首偏长词）
     *
     * @param word 候选汉字
     */
    fun getContextBoost(word: String): Int {
        if (word.isEmpty()) return 0

        // 句首
        if (window.isEmpty()) {
            return if (word.length >= 2) (BIGRAM_BOOST * HEAD_BOOST_FACTOR).toInt() else 0
        }

        val lastCommitted = window.last()
        val lastChar = lastCommitted.lastOrNull() ?: return 0
        val firstChar = word.firstOrNull() ?: return 0

        // 简单规则：上文末字 ≠ 候选首字（避免重复词）+ 基础接续加分
        // 后续可替换为真实 bigram 查表
        return if (lastChar != firstChar) BIGRAM_BOOST else 0
    }

    /**
     * 是否处于句首（上下文窗口为空）。
     */
    fun isHeadOfSentence(): Boolean = window.isEmpty()

    /**
     * 获取上下文窗口内容（调试用）。
     */
    fun snapshot(): List<String> = window.toList()
}
