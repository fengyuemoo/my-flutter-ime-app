package com.example.myapp.ime.mode

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession

interface ImeModeHandler {

    data class Output(
        val candidates: ArrayList<Candidate>,
        val pinyinSidebar: List<String> = emptyList(),
        /**
         * Optional UI composing preview override (mainly for CN preedit segmentation / T9 preview line).
         * When null, UI should fall back to session.displayText(...).
         */
        val composingPreviewText: String? = null,
        /**
         * Optional direct-commit text on Enter for specific modes (CN-T9 preview letters commit).
         * When null, Enter falls back to strategy.onEnter()/default behavior.
         */
        val enterCommitText: String? = null
    )

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): Output
}
