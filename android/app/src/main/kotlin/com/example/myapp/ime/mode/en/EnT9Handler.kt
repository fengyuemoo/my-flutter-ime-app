package com.example.myapp.ime.mode.en

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler

object EnT9Handler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {
        @Suppress("UNUSED_PARAMETER")
        val ignored = singleCharMode

        val input = session.rawT9Digits

        val candidates = ArrayList<Candidate>()
        if (dictEngine.isLoaded && input.isNotEmpty()) {
            candidates.addAll(dictEngine.getSuggestions(input, isT9 = true, isChineseMode = false))
        }

        val finalList =
            if (candidates.isEmpty() && input.isNotEmpty()) {
                arrayListOf(Candidate(input, input, 0, 0, 0))
            } else {
                candidates
            }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = emptyList()
        )
    }
}
