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

    private fun state(): ModeState {
        val key = currentModeKey()
        return states.getOrPut(key) { ModeState() }
    }

    // Handler computed composing preview override (CN-Qwerty segmentation / CN-T9 preview line)
    fun getComposingPreviewOverride(): String? = state().composingPreviewOverride

    // Handler computed enter-commit override (CN-T9 preview letters commit)
    fun getEnterCommitTextOverride(): String? = state().enterCommitTextOverride

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
        ui.setFilterButton(state().isSingleCharMode)
    }

    private fun toggleSingleCharModeInternal() {
        val st = state()
        st.isSingleCharMode = !st.isSingleCharMode
        ui.setFilterButton(st.isSingleCharMode)
        updateCandidates()
    }

    fun toggleExpand() {
        val st = state()
        st.isExpanded = !st.isExpanded
        ui.setExpanded(st.isExpanded, session().isComposing())
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
        val st = state()
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

        val handler = resolveHandler()
        val out = handler.build(
            session = s,
            dictEngine = dictEngine,
            singleCharMode = st.isSingleCharMode
        )

        st.composingPreviewOverride = out.composingPreviewText
        st.enterCommitTextOverride = out.enterCommitText
        st.pinyinSidebar = out.pinyinSidebar
        st.currentCandidates = out.candidates

        keyboardController.updateSidebar(st.pinyinSidebar)
        ui.setCandidates(st.currentCandidates)
    }

    fun handleSpaceKey() {
        val st = state()
        if (st.currentCandidates.isNotEmpty()) {
            commitCandidate(st.currentCandidates[0])
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        val st = state()
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
