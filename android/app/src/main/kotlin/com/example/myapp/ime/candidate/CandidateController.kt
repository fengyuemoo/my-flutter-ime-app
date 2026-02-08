package com.example.myapp.ime.candidate

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.ImeModeHandler
import com.example.myapp.ime.mode.cn.CnQwertyHandler
import com.example.myapp.ime.mode.cn.CnT9Handler
import com.example.myapp.ime.mode.en.EnQwertyHandler
import com.example.myapp.ime.mode.en.EnT9Handler
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.ime.ui.api.UiStateActions

class CandidateController(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val dictEngine: Dictionary,
    private val sessions: ComposingSessionHub,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val updateComposingView: () -> Unit,
) : UiStateActions {

    private var isExpanded = false
    private var isSingleCharMode = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()

    // NEW: handler computed composing preview (CN-Qwerty segmentation)
    private var composingPreviewOverride: String? = null

    fun getComposingPreviewOverride(): String? = composingPreviewOverride

    private fun session(): ComposingSession = sessions.current()

    override fun toggleCandidatesExpanded() {
        toggleExpand()
    }

    override fun syncFilterButtonState() {
        syncFilterButton()
    }

    override fun toggleSingleCharMode() {
        toggleSingleCharModeInternal()
    }

    fun syncFilterButton() {
        ui.setFilterButton(isSingleCharMode)
    }

    private fun toggleSingleCharModeInternal() {
        isSingleCharMode = !isSingleCharMode
        ui.setFilterButton(isSingleCharMode)
        updateCandidates()
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
        ui.setExpanded(isExpanded, session().isComposing())
    }

    private fun resolveHandler(): ImeModeHandler {
        val mainMode = keyboardController.getMainMode()
        return when {
            mainMode.isChinese && mainMode.useT9Layout -> CnT9Handler
            mainMode.isChinese && !mainMode.useT9Layout -> CnQwertyHandler
            !mainMode.isChinese && mainMode.useT9Layout -> EnT9Handler
            else -> EnQwertyHandler
        }
    }

    fun updateCandidates() {
        val s = session()
        currentCandidates.clear()

        if (!s.isComposing()) {
            composingPreviewOverride = null
            ui.showIdleState()
            keyboardController.updateSidebar(emptyList())

            if (isExpanded) {
                isExpanded = false
                ui.setExpanded(false, isComposing = false)
            }
            return
        }

        ui.showComposingState(isExpanded)

        val handler = resolveHandler()
        val out = handler.build(
            session = s,
            dictEngine = dictEngine,
            singleCharMode = isSingleCharMode
        )

        composingPreviewOverride = out.composingPreviewText

        keyboardController.updateSidebar(out.pinyinSidebar)
        currentCandidates = out.candidates

        ui.setCandidates(currentCandidates)
    }

    fun handleSpaceKey() {
        if (currentCandidates.isNotEmpty()) {
            commitCandidate(currentCandidates[0])
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        if (currentCandidates.isEmpty()) return false
        commitCandidate(currentCandidates[0])
        return true
    }

    fun commitCandidate(cand: Candidate) {
        if (keyboardController.isRawCommitMode()) {
            commitRaw(cand.word)
            clearComposing()
            return
        }

        val mainMode = keyboardController.getMainMode()

        when (val r = session().pickCandidate(
            cand,
            mainMode.useT9Layout,
            mainMode.isChinese
        )) {
            is ComposingSession.PickResult.Commit -> {
                commitRaw(r.text)
                clearComposing()
            }

            is ComposingSession.PickResult.Updated -> {
                updateComposingView()
                updateCandidates()
            }
        }
    }
}
