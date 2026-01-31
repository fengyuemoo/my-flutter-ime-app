package com.example.myapp.ime.api

interface ComposingActions {
    fun handleComposingInput(text: String)
    fun handleT9Input(digit: String)
    fun onPinyinSidebarClick(pinyin: String)
    fun clearComposing()
}
