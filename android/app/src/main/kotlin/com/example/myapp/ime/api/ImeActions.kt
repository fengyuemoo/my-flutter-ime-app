package com.example.myapp.ime.api

import android.view.inputmethod.InputConnection

interface ImeActions {

    fun inputConnection(): InputConnection?

    fun clearComposing()

    fun handleComposingInput(text: String)
    fun handleT9Input(digit: String)

    /**
     * 用户点选 sidebar 中的拼音音节。
     * @param pinyin  点选的拼音（如 "zhong"）
     * @param t9Code  该拼音对应的 T9 数字串（如 "94664"），用于确定消费多少 rawDigits
     */
    fun onPinyinSidebarClick(pinyin: String, t9Code: String = "")

    /**
     * 用户点击了已物化的拼音段（触发重切分 / 消歧）。
     * @param index  被点击的物化段下标（0-based）
     */
    fun onPinyinSidebarSegmentClick(index: Int)

    fun commitText(text: String)
    fun handleSpaceKey()
    fun handleBackspace()
    fun handleSpecialKey(keyLabel: String)

    fun switchToEnglishMode()
    fun switchToChineseMode()

    fun switchToNumericMode()
    fun openSymbolPanel()
    fun closeSymbolPanel()
    fun exitNumericMode()

    /** Symbol panel categories (Sogou-like order). */
    enum class SymbolCategory {
        COMMON,
        CN,
        EN,
        WEB,
        EMAIL,
        KAOMOJI,
        MATH,
        SUPER,
        SERIAL,
        IPA,
        JAPANESE,
        ARROWS,
        SPECIAL,
        PINYIN,
        ZHUYIN,
        VERTICAL,
        RADICALS,
        RUSSIAN,
        GREEK,
        LATIN,
        BOX,
        SYLLABICS,
        TIBETAN
    }

    fun setSymbolCategory(category: SymbolCategory)
    fun symbolPageUp()
    fun symbolPageDown()
    fun toggleSymbolLock()

    fun commitSymbolFromPanel(symbol: String)

    fun getEnglishPredictEnabled(): Boolean
    fun setEnglishPredict(enabled: Boolean)
    fun toggleEnglishPredict()
}
