package com.example.myapp.ime.compose.common

interface EnglishPredictable {
    fun getEnglishPredictEnabled(): Boolean
    fun setEnglishPredictEnabled(enabled: Boolean)

    /**
     * Default toggle implementation.
     * Concrete strategies keep state; callers don't need to re-implement toggle logic.
     */
    fun toggleEnglishPredict() = setEnglishPredictEnabled(!getEnglishPredictEnabled())
}
