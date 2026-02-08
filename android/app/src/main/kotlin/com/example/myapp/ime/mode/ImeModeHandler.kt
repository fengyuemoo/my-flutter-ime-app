package com.example.myapp.ime.mode

import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession

interface ImeModeHandler {

    data class Output(
        val candidates: ArrayList<Candidate>,
        val pinyinSidebar: List<String> = emptyList()
    )

    fun build(
        session: ComposingSession,
        candidateComposer: CandidateComposer,
        singleCharMode: Boolean
    ): Output
}
