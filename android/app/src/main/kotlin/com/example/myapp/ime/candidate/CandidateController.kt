package com.example.myapp.ime.candidate

import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.ime.ui.api.UiStateActions

class CandidateController(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val candidateComposer: CandidateComposer,
    private val sessions: ComposingSessionHub,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val updateComposingView: () -> Unit,
) : UiStateActions {

    private var isExpanded = false
    private var isSingleCharMode = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()

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

    fun updateCandidates() {
        currentCandidates.clear()

        if (!session().isComposing()) {
            ui.showIdleState()
            keyboardController.updateSidebar(emptyList())

            if (isExpanded) {
                isExpanded = false
                ui.setExpanded(false, isComposing = false)
            }
            return
        }

        ui.showComposingState(isExpanded)

        val mainMode = keyboardController.getMainMode()

        val r = candidateComposer.compose(
            session = session(),
            isChinese = mainMode.isChinese,
            useT9Layout = mainMode.useT9Layout,
            isT9Keyboard = mainMode.useT9Layout,
            singleCharMode = isSingleCharMode
        )

        keyboardController.updateSidebar(r.pinyinSidebar)

        currentCandidates = ArrayList(r.candidates)
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
