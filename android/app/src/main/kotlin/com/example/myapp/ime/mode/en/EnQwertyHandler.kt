package com.example.myapp.ime.mode.en

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler

object EnQwertyHandler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {
        @Suppress("UNUSED_PARAMETER")
        val ignored = singleCharMode

        val input = session.qwertyInput

        val candidates = ArrayList<Candidate>()
        if (dictEngine.isLoaded && input.isNotEmpty()) {
            candidates.addAll(dictEngine.getSuggestions(input, isT9 = false, isChineseMode = false))
        }

        val finalList =
            if (candidates.isEmpty() && input.isNotEmpty()) {
                arrayListOf(Candidate(input, input, 0, 0, 0))
            } else {
                candidates
            }

        // English modes do not use these previews
        session.setT9PreviewText(null)
        session.setQwertyPreviewText(null)

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = emptyList()
        )
    }
}
