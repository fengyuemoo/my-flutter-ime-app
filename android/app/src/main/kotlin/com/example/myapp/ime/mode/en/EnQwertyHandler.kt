package com.example.myapp.ime.mode.en

import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler

object EnQwertyHandler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        candidateComposer: CandidateComposer,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val r = candidateComposer.compose(
            session = session,
            isChinese = false,
            useT9Layout = false,
            isT9Keyboard = false,
            singleCharMode = singleCharMode
        )

        session.setT9PreviewText(null)
        session.setQwertyPreviewText(null)

        return ImeModeHandler.Output(
            candidates = ArrayList(r.candidates),
            pinyinSidebar = r.pinyinSidebar
        )
    }
}
