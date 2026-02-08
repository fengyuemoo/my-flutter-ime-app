package com.example.myapp.ime.mode

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession

interface ImeModeHandler {

    data class Output(
        val candidates: ArrayList<Candidate>,
        val pinyinSidebar: List<String> = emptyList()
    )

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): Output
}
