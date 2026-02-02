package com.example.myapp.ime.api

import android.view.inputmethod.InputConnection

interface ImeActions {

    fun inputConnection(): InputConnection?

    fun clearComposing()

    fun handleComposingInput(text: String)
    fun handleT9Input(digit: String)
    fun onPinyinSidebarClick(pinyin: String)

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
        COMMON,         // 1 常用
        CN,             // 2 中文
        EN,             // 3 英文
        WEB,            // 4 网络
        EMAIL,          // 5 邮箱
        KAOMOJI,        // 6 颜文
        MATH,           // 7 数学
        SUPER,          // 8 角标
        SERIAL,         // 9 序号
        IPA,            // 10 音标
        JAPANESE,       // 11 日文
        ARROWS,         // 12 箭头
        SPECIAL,        // 13 特殊
        PINYIN,         // 14 拼音
        ZHUYIN,         // 15 注音
        VERTICAL,       // 16 竖标（竖排标点）
        RADICALS,       // 17 部首（汉字部首）
        RUSSIAN,        // 18 俄文
        GREEK,          // 19 希腊
        LATIN,          // 20 拉丁
        BOX,            // 21 制表（表格绘制符号）
        SYLLABICS,      // 22 土音（加拿大原住民音节文字）
        TIBETAN         // 23 藏文
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
