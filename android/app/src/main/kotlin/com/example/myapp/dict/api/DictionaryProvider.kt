package com.example.myapp.dict.api

interface DictionaryProvider {
    val dictionary: Dictionary
    val isReady: Boolean
    val debugInfo: String?
    fun ensureReadyAsync(force: Boolean = false, onDone: ((Boolean) -> Unit)? = null)
}
