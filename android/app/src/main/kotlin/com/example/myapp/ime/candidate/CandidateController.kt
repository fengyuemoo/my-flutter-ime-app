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

    private enum class ModeKey {
        CN_QWERTY,
        CN_T9,
        EN_QWERTY,
        EN_T9
    }

    private data class ModeState(
        var isExpanded: Boolean = false,
        var isSingleCharMode: Boolean = false,
        var currentCandidates: ArrayList<Candidate> = ArrayList(),
        var composingPreviewOverride: String? = null,
        var enterCommitTextOverride: String? = null,
        var pinyinSidebar: List<String> = emptyList()
    )

    private val states: MutableMap<ModeKey, ModeState> = mutableMapOf()

    private fun currentModeKey(): ModeKey {
        val mainMode = keyboardController.getMainMode()
        return when {
            mainMode.isChinese && mainMode.useT9Layout -> ModeKey.CN_T9
            mainMode.isChinese && !mainMode.useT9Layout -> ModeKey.CN_QWERTY
            !mainMode.isChinese && mainMode.useT9Layout -> ModeKey.EN_T9
            else -> ModeKey.EN_QWERTY
        }
    }

    private fun stateFor(key: ModeKey): ModeState {
        return states.getOrPut(key) { ModeState() }
    }

    private fun currentState(): ModeState = stateFor(currentModeKey())

    // Handler computed composing preview override (CN-Qwerty segmentation / CN-T9 preview line)
    fun getComposingPreviewOverride(): String? = currentState().composingPreviewOverride

    // Handler computed enter-commit override (CN-T9 preview letters commit)
    fun getEnterCommitTextOverride(): String? = currentState().enterCommitTextOverride

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
        ui.setFilterButton(currentState().isSingleCharMode)
    }

    private fun toggleSingleCharModeInternal() {
        val key = currentModeKey()
        val st = stateFor(key)

        st.isSingleCharMode = !st.isSingleCharMode
        ui.setFilterButton(st.isSingleCharMode)
        updateCandidates()
    }

    fun toggleExpand() {
        val key = currentModeKey()
        val st = stateFor(key)

        st.isExpanded = !st.isExpanded
        ui.setExpanded(st.isExpanded, session().isComposing())
    }

    // --- Per-mode build paths (split) ---

    private fun buildCnQwertyOutput(s: ComposingSession, singleCharMode: Boolean): ImeModeHandler.Output {
        return CnQwertyHandler.build(
            session = s,
            dictEngine = dictEngine,
            singleCharMode = singleCharMode
        )
    }

    private fun buildCnT9Output(s: ComposingSession, singleCharMode: Boolean): ImeModeHandler.Output {
        return CnT9Handler.build(
            session = s,
            dictEngine = dictEngine,
            singleCharMode = singleCharMode
        )
    }

    private fun buildEnQwertyOutput(s: ComposingSession, singleCharMode: Boolean): ImeModeHandler.Output {
        return EnQwertyHandler.build(
            session = s,
            dictEngine = dictEngine,
            singleCharMode = singleCharMode
        )
    }

    private fun buildEnT9Output(s: ComposingSession, singleCharMode: Boolean): ImeModeHandler.Output {
        return EnT9Handler.build(
            session = s,
            dictEngine = dictEngine,
            singleCharMode = singleCharMode
        )
    }

    private fun buildOutputForMode(key: ModeKey, s: ComposingSession, singleCharMode: Boolean): ImeModeHandler.Output {
        return when (key) {
            ModeKey.CN_QWERTY -> buildCnQwertyOutput(s, singleCharMode)
            ModeKey.CN_T9 -> buildCnT9Output(s, singleCharMode)
            ModeKey.EN_QWERTY -> buildEnQwertyOutput(s, singleCharMode)
            ModeKey.EN_T9 -> buildEnT9Output(s, singleCharMode)
        }
    }

    fun updateCandidates() {
        // Freeze mode key for this update pass to avoid cross-mode state pollution.
        val key = currentModeKey()
        val st = stateFor(key)
        val s = session()

        // Ensure UI toggles reflect current mode state.
        ui.setFilterButton(st.isSingleCharMode)

        st.currentCandidates.clear()

        if (!s.isComposing()) {
            st.composingPreviewOverride = null
            st.enterCommitTextOverride = null
            st.pinyinSidebar = emptyList()

            ui.showIdleState()
            keyboardController.updateSidebar(emptyList())

            if (st.isExpanded) {
                st.isExpanded = false
                ui.setExpanded(false, isComposing = false)
            }
            return
        }

        ui.showComposingState(st.isExpanded)

        val out = buildOutputForMode(
            key = key,
            s = s,
            singleCharMode = st.isSingleCharMode
        )

        st.composingPreviewOverride = out.composingPreviewText
        st.enterCommitTextOverride = out.enterCommitText
        st.pinyinSidebar = out.pinyinSidebar
        st.currentCandidates = ArrayList(out.candidates)

        keyboardController.updateSidebar(st.pinyinSidebar)
        ui.setCandidates(st.currentCandidates)
    }

    fun handleSpaceKey() {
        val st = currentState()
        if (st.currentCandidates.isNotEmpty()) {
            commitCandidate(st.currentCandidates[0])
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        val st = currentState()
        if (st.currentCandidates.isEmpty()) return false
        commitCandidate(st.currentCandidates[0])
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
                // 先更新候选/override，再刷新 composing preview，避免短暂显示旧 preview
                updateCandidates()
                updateComposingView()
            }
        }
    }
}
