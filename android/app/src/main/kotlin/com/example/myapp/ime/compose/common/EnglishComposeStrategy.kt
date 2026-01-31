package com.example.myapp.ime.compose.common

/**
 * English compose strategy that supports prediction toggling.
 *
 * This interface bridges old method names (isPredicting / setEnglishComposePredict)
 * to the new EnglishPredictable API.
 */
interface EnglishComposeStrategy : ComposeStrategy, EnglishPredictable {

    /**
     * @deprecated Backward compatibility. Prefer getEnglishPredictEnabled().
     */
    fun isPredicting(): Boolean = getEnglishPredictEnabled()

    /**
     * @deprecated Backward compatibility. Prefer setEnglishPredictEnabled(enabled).
     */
    fun setEnglishComposePredict(enabled: Boolean) = setEnglishPredictEnabled(enabled)
}
