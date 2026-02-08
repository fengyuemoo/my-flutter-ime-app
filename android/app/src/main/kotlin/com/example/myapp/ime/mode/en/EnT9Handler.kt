package com.example.myapp.ime.mode.en

import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler

object EnT9Handler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        candidateComposer: CandidateComposer,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val r = candidateComposer.compose(
            session = session,
            isChinese = false,
            useT9Layout = true,
            isT9Keyboard = true,
            singleCharMode = singleCharMode
        )

        // Preserve current behavior: only CN T9 sets preview
        session.setT9PreviewText(null)
        session.setQwertyPreviewText(null)

        return ImeModeHandler.Output(
            candidates = ArrayList(r.candidates),
            pinyinSidebar = r.pinyinSidebar
        )
    }
}
