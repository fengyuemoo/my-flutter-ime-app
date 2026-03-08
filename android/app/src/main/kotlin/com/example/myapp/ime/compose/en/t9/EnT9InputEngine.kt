package com.example.myapp.ime.compose.en.t9

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.EnBaseInputEngine
import com.example.myapp.ime.ui.ImeUi

class EnT9InputEngine private constructor(
    ui: ImeUi,
    keyboardController: KeyboardController,
    candidateController: CandidateController,
    session: ComposingSession,
    inputConnectionProvider: () -> InputConnection?,
    private val strategyImpl: EnT9ComposeStrategy
) : EnBaseInputEngine(
    ui = ui,
    keyboardController = keyboardController,
    candidateController = candidateController,
    session = session,
    inputConnectionProvider = inputConnectionProvider,
    useT9Layout = true,
    strategy = strategyImpl
) {
    constructor(
        ui: ImeUi,
        keyboardController: KeyboardController,
        candidateController: CandidateController,
        session: ComposingSession,
        inputConnectionProvider: () -> InputConnection?,
        onTransientPreviewChanged: () -> Unit
    ) : this(
        ui = ui,
        keyboardController = keyboardController,
        candidateController = candidateController,
        session = session,
        inputConnectionProvider = inputConnectionProvider,
        strategyImpl = EnT9ComposeStrategy(
            sessionProvider = { session },
            inputConnectionProvider = { inputConnectionProvider() },
            onPreviewStateChanged = onTransientPreviewChanged
        )
    )

    override fun getTransientComposingPreviewText(): String? {
        return strategyImpl.getPendingMultiTapPreviewText()
    }
}
