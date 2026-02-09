package com.example.myapp.ime.mode.en

import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.ImeModeHandler
import com.example.myapp.ime.ui.ImeUi

object EnT9Handler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {
        @Suppress("UNUSED_PARAMETER")
        val ignored = singleCharMode

        val input = session.rawT9Digits

        val candidates = ArrayList<Candidate>()
        if (dictEngine.isLoaded && input.isNotEmpty()) {
            candidates.addAll(dictEngine.getSuggestions(input, isT9 = true, isChineseMode = false))
        }

        val finalList =
            if (candidates.isEmpty() && input.isNotEmpty()) {
                arrayListOf(Candidate(input, input, 0, 0, 0))
            } else {
                candidates
            }

        return ImeModeHandler.Output(
            candidates = finalList,
            pinyinSidebar = emptyList()
        )
    }
}

/**
 * Strong-isolated candidate engine for EN-T9.
 */
class EnT9CandidateEngine(
    private val ui: ImeUi,
    private val keyboardController: KeyboardController,
    private val dictEngine: Dictionary,
    private val session: ComposingSession,
    private val commitRaw: (String) -> Unit,
    private val clearComposing: () -> Unit,
    private val updateComposingView: () -> Unit,
    private val isRawCommitMode: () -> Boolean
) {
    private var isExpanded: Boolean = false
    private var isSingleCharMode: Boolean = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()
    private var composingPreviewOverride: String? = null
    private var enterCommitTextOverride: String? = null

    fun getComposingPreviewOverride(): String? = composingPreviewOverride
    fun getEnterCommitTextOverride(): String? = enterCommitTextOverride

    private fun isDebuggableApp(): Boolean {
        return (ui.rootView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun syncFilterButton() {
        ui.setFilterButton(isSingleCharMode)
    }

    fun toggleSingleCharMode() {
        isSingleCharMode = !isSingleCharMode
        syncFilterButton()
        updateCandidates()
    }

    fun toggleExpand() {
        isExpanded = !isExpanded
        ui.setExpanded(isExpanded, session.isComposing())
    }

    private fun renderIdleUi() {
        ui.showIdleState()
        ui.setExpanded(false, isComposing = false)
        keyboardController.updateSidebar(emptyList())
    }

    private fun renderComposingUi(out: ImeModeHandler.Output) {
        syncFilterButton()

        ui.showComposingState(isExpanded = isExpanded)
        ui.setExpanded(isExpanded, isComposing = true)

        keyboardController.updateSidebar(out.pinyinSidebar)
        ui.setCandidates(currentCandidates)
    }

    fun updateCandidates() {
        syncFilterButton()
        currentCandidates.clear()

        if (!session.isComposing()) {
            composingPreviewOverride = null
            enterCommitTextOverride = null

            if (isExpanded) isExpanded = false
            renderIdleUi()
            return
        }

        val out = EnT9Handler.build(
            session = session,
            dictEngine = dictEngine,
            singleCharMode = isSingleCharMode
        )

        composingPreviewOverride = out.composingPreviewText
        enterCommitTextOverride = out.enterCommitText

        currentCandidates = ArrayList(out.candidates)
        renderComposingUi(out)
    }

    fun handleSpaceKey() {
        if (currentCandidates.isNotEmpty()) {
            commitCandidateAt(0)
        } else {
            commitRaw(" ")
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        if (currentCandidates.isEmpty()) return false
        commitCandidateAt(0)
        return true
    }

    fun commitCandidateAt(index: Int) {
        if (index !in 0 until currentCandidates.size) {
            val msg = "Candidate index out of range: EN_T9 index=$index size=${currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("EnT9CandidateEngine", msg)
                throw AssertionError(msg)
            }
            return
        }

        val cand = currentCandidates[index]

        if (isRawCommitMode()) {
            commitRaw(cand.word)
            clearComposing()
            return
        }

        when (val r = session.pickCandidate(
            cand = cand,
            useT9Layout = true,
            isChinese = false
        )) {
            is ComposingSession.PickResult.Commit -> {
                commitRaw(r.text)
                clearComposing()
            }

            is ComposingSession.PickResult.Updated -> {
                updateCandidates()
                updateComposingView()
            }
        }
    }

    fun commitCandidate(cand: Candidate) {
        val idx = currentCandidates.indexOf(cand)
        if (idx < 0) {
            val msg = "Candidate not in current EN_T9 list: cand=$cand size=${currentCandidates.size}"
            if (isDebuggableApp()) {
                Log.wtf("EnT9CandidateEngine", msg)
                throw AssertionError(msg)
            }
            return
        }
        commitCandidateAt(idx)
    }
}
