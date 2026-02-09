package com.example.myapp.ime.candidate

import android.content.pm.ApplicationInfo
import android.util.Log
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

    private fun isDebuggableApp(): Boolean {
        return (ui.rootView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
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

    private fun isChinese(key: ModeKey): Boolean =
        key == ModeKey.CN_QWERTY || key == ModeKey.CN_T9

    private fun useT9Layout(key: ModeKey): Boolean =
        key == ModeKey.CN_T9 || key == ModeKey.EN_T9

    private fun stateFor(key: ModeKey): ModeState =
        states.getOrPut(key) { ModeState() }

    private fun currentState(): ModeState = stateFor(currentModeKey())

    // Handler computed composing preview override (CN-Qwerty segmentation / CN-T9 preview line)
    fun getComposingPreviewOverride(): String? = currentState().composingPreviewOverride

    // Handler computed enter-commit override (CN-T9 preview letters commit)
    fun getEnterCommitTextOverride(): String? = currentState().enterCommitTextOverride

    private fun session(): ComposingSession = sessions.current()

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

    // --- UI render helpers (centralized) ---

    private fun renderFilterButton(st: ModeState) {
        ui.setFilterButton(st.isSingleCharMode)
    }

    private fun renderIdleUi() {
        ui.showIdleState()
        // Ensure expand UI is reset in idle; showIdleState() does not reset expand arrow rotation.
        ui.setExpanded(false, isComposing = false)
        keyboardController.updateSidebar(emptyList())
    }

    private fun renderComposingUi(st: ModeState, out: ImeModeHandler.Output) {
        renderFilterButton(st)

        ui.showComposingState(isExpanded = st.isExpanded)
        // Ensure expanded panel visibility + arrow rotation matches state for THIS mode.
        ui.setExpanded(st.isExpanded, isComposing = true)

        keyboardController.updateSidebar(out.pinyinSidebar)
        ui.setCandidates(st.currentCandidates)
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

    // --- Public behaviors ---

    fun syncFilterButton() {
        renderFilterButton(currentState())
    }

    private fun toggleSingleCharModeInternal() {
        val key = currentModeKey()
        val st = stateFor(key)

        st.isSingleCharMode = !st.isSingleCharMode
        renderFilterButton(st)
        updateCandidates()
    }

    fun toggleExpand() {
        val key = currentModeKey()
        val st = stateFor(key)

        st.isExpanded = !st.isExpanded
        ui.setExpanded(st.isExpanded, session().isComposing())
    }

    private fun updateCandidatesForSnapshot(key: ModeKey, st: ModeState, s: ComposingSession) {
        renderFilterButton(st)

        st.currentCandidates.clear()

        if (!s.isComposing()) {
            st.composingPreviewOverride = null
            st.enterCommitTextOverride = null
            st.pinyinSidebar = emptyList()

            if (st.isExpanded) {
                st.isExpanded = false
            }

            renderIdleUi()
            return
        }

        val out = buildOutputForMode(
            key = key,
            s = s,
            singleCharMode = st.isSingleCharMode
        )

        st.composingPreviewOverride = out.composingPreviewText
        st.enterCommitTextOverride = out.enterCommitText
        st.pinyinSidebar = out.pinyinSidebar

        st.currentCandidates = ArrayList(out.candidates)

        renderComposingUi(st, out)
    }

    fun updateCandidates() {
        val key = currentModeKey()
        val st = stateFor(key)
        val s = session()
        updateCandidatesForSnapshot(key, st, s)
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

        // Freeze everything needed for this commit path.
        val key = currentModeKey()
        val st = stateFor(key)
        val s = session()
        val useT9Layout = useT9Layout(key)
        val isChinese = isChinese(key)

        // Hard guard: candidate must come from current mode's list.
        if (!st.currentCandidates.contains(cand)) {
            val msg = "Candidate not in current mode list: mode=$key, cand=$cand, size=${st.currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("CandidateController", msg)
                throw AssertionError(msg)
            }
            return
        }

        when (val r = s.pickCandidate(
            cand,
            useT9Layout,
            isChinese
        )) {
            is ComposingSession.PickResult.Commit -> {
                commitRaw(r.text)
                clearComposing()
            }

            is ComposingSession.PickResult.Updated -> {
                updateCandidatesForSnapshot(key, st, s)
                updateComposingView()
            }
        }
    }
}
