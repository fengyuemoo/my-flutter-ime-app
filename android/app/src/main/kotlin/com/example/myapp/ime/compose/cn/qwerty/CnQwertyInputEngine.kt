package com.example.myapp.ime.compose.cn.qwerty

import android.content.Context
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.CnBaseInputEngine
import com.example.myapp.ime.ui.ImeUi

/**
 * CN-QWERTY input engine.
 */
class CnQwertyInputEngine(
    context: Context,
    ui: ImeUi,
    keyboardController: KeyboardController,
    candidateController: CandidateController,
    session: ComposingSession,
    inputConnectionProvider: () -> InputConnection?
) : CnBaseInputEngine(
    context = context,
    ui = ui,
    keyboardController = keyboardController,
    candidateController = candidateController,
    session = session,
    inputConnectionProvider = inputConnectionProvider,
    useT9Layout = false,
    logTag = "CnQwertyInputEngine",
    strategy = CnQwertyComposeStrategy(sessionProvider = { session })
)
