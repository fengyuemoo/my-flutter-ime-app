package com.example.myapp.ime.candidate

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.cn.CnQwertyCandidateEngine
import com.example.myapp.ime.mode.cn.CnT9CandidateEngine
import com.example.myapp.ime.mode.en.EnQwertyCandidateEngine
import com.example.myapp.ime.mode.en.EnT9CandidateEngine
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

    private val cnQwertyEngine = CnQwertyCandidateEngine(
        ui = ui,
        keyboardController = keyboardController,
        dictEngine = dictEngine,
        session = sessions.cnQwerty,
        commitRaw = commitRaw,
        clearComposing = clearComposing,
        updateComposingView = updateComposingView,
        isRawCommitMode = { keyboardController.isRawCommitMode() }
    )

    private val cnT9Engine = CnT9CandidateEngine(
        ui = ui,
        keyboardController = keyboardController,
        dictEngine = dictEngine,
        session = sessions.cnT9,
        commitRaw = commitRaw,
        clearComposing = clearComposing,
        updateComposingView = updateComposingView,
        isRawCommitMode = { keyboardController.isRawCommitMode() }
    )

    private val enQwertyEngine = EnQwertyCandidateEngine(
        ui = ui,
        keyboardController = keyboardController,
        dictEngine = dictEngine,
        session = sessions.enQwerty,
        commitRaw = commitRaw,
        clearComposing = clearComposing,
        updateComposingView = updateComposingView,
        isRawCommitMode = { keyboardController.isRawCommitMode() }
    )

    private val enT9Engine = EnT9CandidateEngine(
        ui = ui,
        keyboardController = keyboardController,
        dictEngine = dictEngine,
        session = sessions.enT9,
        commitRaw = commitRaw,
        clearComposing = clearComposing,
        updateComposingView = updateComposingView,
        isRawCommitMode = { keyboardController.isRawCommitMode() }
    )

    private enum class ModeKey {
        CN_QWERTY,
        CN_T9,
        EN_QWERTY,
        EN_T9
    }

    private fun currentModeKey(): ModeKey {
        val mainMode = keyboardController.getMainMode()
        return when {
            mainMode.isChinese && mainMode.useT9Layout -> ModeKey.CN_T9
            mainMode.isChinese && !mainMode.useT9Layout -> ModeKey.CN_QWERTY
            !mainMode.isChinese && mainMode.useT9Layout -> ModeKey.EN_T9
            else -> ModeKey.EN_QWERTY
        }
    }

    // Handler computed composing preview override (CN-Qwerty segmentation / CN-T9 preview line)
    fun getComposingPreviewOverride(): String? {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.getComposingPreviewOverride()
            ModeKey.CN_T9 -> cnT9Engine.getComposingPreviewOverride()
            ModeKey.EN_QWERTY -> enQwertyEngine.getComposingPreviewOverride()
            ModeKey.EN_T9 -> enT9Engine.getComposingPreviewOverride()
        }
    }

    // Handler computed enter-commit override (CN-T9 preview letters commit)
    fun getEnterCommitTextOverride(): String? {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.getEnterCommitTextOverride()
            ModeKey.CN_T9 -> cnT9Engine.getEnterCommitTextOverride()
            ModeKey.EN_QWERTY -> enQwertyEngine.getEnterCommitTextOverride()
            ModeKey.EN_T9 -> enT9Engine.getEnterCommitTextOverride()
        }
    }

    // --- UiStateActions ---

    override fun toggleCandidatesExpanded() {
        toggleExpand()
    }

    override fun syncFilterButtonState() {
        syncFilterButton()
    }

    override fun toggleSingleCharMode() {
        toggleSingleCharModeInternal()
    }

    // --- Public behaviors ---

    fun syncFilterButton() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.syncFilterButton()
            ModeKey.CN_T9 -> cnT9Engine.syncFilterButton()
            ModeKey.EN_QWERTY -> enQwertyEngine.syncFilterButton()
            ModeKey.EN_T9 -> enT9Engine.syncFilterButton()
        }
    }

    private fun toggleSingleCharModeInternal() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.toggleSingleCharMode()
            ModeKey.CN_T9 -> cnT9Engine.toggleSingleCharMode()
            ModeKey.EN_QWERTY -> enQwertyEngine.toggleSingleCharMode()
            ModeKey.EN_T9 -> enT9Engine.toggleSingleCharMode()
        }
    }

    fun toggleExpand() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.toggleExpand()
            ModeKey.CN_T9 -> cnT9Engine.toggleExpand()
            ModeKey.EN_QWERTY -> enQwertyEngine.toggleExpand()
            ModeKey.EN_T9 -> enT9Engine.toggleExpand()
        }
    }

    fun updateCandidates() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.updateCandidates()
            ModeKey.CN_T9 -> cnT9Engine.updateCandidates()
            ModeKey.EN_QWERTY -> enQwertyEngine.updateCandidates()
            ModeKey.EN_T9 -> enT9Engine.updateCandidates()
        }
    }

    fun handleSpaceKey() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.handleSpaceKey()
            ModeKey.CN_T9 -> cnT9Engine.handleSpaceKey()
            ModeKey.EN_QWERTY -> enQwertyEngine.handleSpaceKey()
            ModeKey.EN_T9 -> enT9Engine.handleSpaceKey()
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.commitFirstCandidateOnEnter()
            ModeKey.CN_T9 -> cnT9Engine.commitFirstCandidateOnEnter()
            ModeKey.EN_QWERTY -> enQwertyEngine.commitFirstCandidateOnEnter()
            ModeKey.EN_T9 -> enT9Engine.commitFirstCandidateOnEnter()
        }
    }

    /**
     * Index-driven commit (preferred).
     */
    fun commitCandidateAt(index: Int) {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.commitCandidateAt(index)
            ModeKey.CN_T9 -> cnT9Engine.commitCandidateAt(index)
            ModeKey.EN_QWERTY -> enQwertyEngine.commitCandidateAt(index)
            ModeKey.EN_T9 -> enT9Engine.commitCandidateAt(index)
        }
    }

    /**
     * Backward-compatible: Candidate payload commit.
     * (UI 已改为 index 驱动后，这个方法应尽量少用。)
     */
    fun commitCandidate(cand: Candidate) {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.commitCandidate(cand)
            ModeKey.CN_T9 -> cnT9Engine.commitCandidate(cand)
            ModeKey.EN_QWERTY -> enQwertyEngine.commitCandidate(cand)
            ModeKey.EN_T9 -> enT9Engine.commitCandidate(cand)
        }
    }
}
