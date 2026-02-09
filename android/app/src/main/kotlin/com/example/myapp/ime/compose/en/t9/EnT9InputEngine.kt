package com.example.myapp.ime.compose.en.t9

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.EnBaseInputEngine
import com.example.myapp.ime.ui.ImeUi

/**
 * EN-T9 input engine.
 */
class EnT9InputEngine(
    ui: ImeUi,
    keyboardController: KeyboardController,
    candidateController: CandidateController,
    session: ComposingSession,
    inputConnectionProvider: () -> InputConnection?
) : EnBaseInputEngine(
    ui = ui,
    keyboardController = keyboardController,
    candidateController = candidateController,
    session = session,
    inputConnectionProvider = inputConnectionProvider,
    useT9Layout = true,
    strategy = EnT9ComposeStrategy(
        sessionProvider = { session },
        inputConnectionProvider = { inputConnectionProvider() }
    )
)
