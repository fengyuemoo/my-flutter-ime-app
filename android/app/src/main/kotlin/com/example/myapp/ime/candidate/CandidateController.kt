package com.example.myapp.ime.candidate

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.cn.t9.CnT9PreeditFormatter
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.mode.cn.CnQwertyCandidateEngine
import com.example.myapp.ime.mode.cn.CnT9CandidateEngine
import com.example.myapp.ime.mode.cn.CnT9ContextWindow
import com.example.myapp.ime.mode.cn.CnT9UserChoiceStore
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
    private val userChoiceStore: CnT9UserChoiceStore? = null,
    private val contextWindow: CnT9ContextWindow? = null
) : UiStateActions {

    private val cnQwertyEngine = CnQwertyCandidateEngine(
        ui                = ui,
        keyboardController = keyboardController,
        dictEngine        = dictEngine,
        session           = sessions.cnQwerty,
        commitRaw         = commitRaw,
        clearComposing    = clearComposing,
        isRawCommitMode   = { keyboardController.isRawCommitMode() }
    )

    // 修复：删除不存在的 focusedSegmentIndexProvider 参数
    private val cnT9Engine = CnT9CandidateEngine(
        ui                = ui,
        keyboardController = keyboardController,
        dictEngine        = dictEngine,
        session           = sessions.cnT9,
        commitRaw         = commitRaw,
        clearComposing    = clearComposing,
        isRawCommitMode   = { keyboardController.isRawCommitMode() },
        userChoiceStore   = userChoiceStore,
        contextWindow     = contextWindow
    )

    private val enQwertyEngine = EnQwertyCandidateEngine(
        ui                = ui,
        keyboardController = keyboardController,
        dictEngine        = dictEngine,
        session           = sessions.enQwerty,
        commitRaw         = commitRaw,
        clearComposing    = clearComposing,
        isRawCommitMode   = { keyboardController.isRawCommitMode() }
    )

    private val enT9Engine = EnT9CandidateEngine(
        ui                = ui,
        keyboardController = keyboardController,
        dictEngine        = dictEngine,
        session           = sessions.enT9,
        commitRaw         = commitRaw,
        clearComposing    = clearComposing,
        isRawCommitMode   = { keyboardController.isRawCommitMode() }
    )

    private enum class ModeKey {
        CN_QWERTY, CN_T9, EN_QWERTY, EN_T9
    }

    private fun currentModeKey(): ModeKey {
        val mainMode = keyboardController.getMainMode()
        return when {
            mainMode.isChinese &&  mainMode.useT9Layout  -> ModeKey.CN_T9
            mainMode.isChinese && !mainMode.useT9Layout  -> ModeKey.CN_QWERTY
            !mainMode.isChinese && mainMode.useT9Layout  -> ModeKey.EN_T9
            else                                         -> ModeKey.EN_QWERTY
        }
    }

    private fun currentSession(): ComposingSession {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> sessions.cnQwerty
            ModeKey.CN_T9     -> sessions.cnT9
            ModeKey.EN_QWERTY -> sessions.enQwerty
            ModeKey.EN_T9     -> sessions.enT9
        }
    }

    private fun currentUseT9Layout(): Boolean {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> false
            ModeKey.CN_T9     -> true
            ModeKey.EN_QWERTY -> false
            ModeKey.EN_T9     -> true
        }
    }

    fun getComposingPreviewOverride(): String? {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.getComposingPreviewOverride()
            ModeKey.CN_T9     -> cnT9Engine.getComposingPreviewOverride()
            ModeKey.EN_QWERTY -> enQwertyEngine.getComposingPreviewOverride()
            ModeKey.EN_T9     -> enT9Engine.getComposingPreviewOverride()
        }
    }

    fun getEnterCommitTextOverride(): String? {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.getEnterCommitTextOverride()
            ModeKey.CN_T9     -> cnT9Engine.getEnterCommitTextOverride()
            ModeKey.EN_QWERTY -> enQwertyEngine.getEnterCommitTextOverride()
            ModeKey.EN_T9     -> enT9Engine.getEnterCommitTextOverride()
        }
    }

    fun resolveComposingPreviewText(): String? {
        return when (currentModeKey()) {
            ModeKey.CN_T9 -> CnT9PreeditFormatter.format(
                session        = sessions.cnT9,
                dict           = dictEngine,
                engineOverride = cnT9Engine.getComposingPreviewOverride()
            )
            else -> {
                val override = getComposingPreviewOverride()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (override != null) return override
                currentSession()
                    .displayText(useT9Layout = currentUseT9Layout())
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
    }

    fun resolveEnterCommitText(): String? {
        return getEnterCommitTextOverride()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun toggleCandidatesExpanded()  { toggleExpand() }
    override fun syncFilterButtonState()     { syncFilterButton() }
    override fun toggleSingleCharMode()      { toggleSingleCharModeInternal() }

    fun syncFilterButton() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.syncFilterButton()
            ModeKey.CN_T9     -> cnT9Engine.syncFilterButton()
            ModeKey.EN_QWERTY -> enQwertyEngine.syncFilterButton()
            ModeKey.EN_T9     -> enT9Engine.syncFilterButton()
        }
    }

    private fun toggleSingleCharModeInternal() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.toggleSingleCharMode()
            ModeKey.CN_T9     -> cnT9Engine.toggleSingleCharMode()
            ModeKey.EN_QWERTY -> enQwertyEngine.toggleSingleCharMode()
            ModeKey.EN_T9     -> enT9Engine.toggleSingleCharMode()
        }
    }

    fun toggleExpand() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.toggleExpand()
            ModeKey.CN_T9     -> cnT9Engine.toggleExpand()
            ModeKey.EN_QWERTY -> enQwertyEngine.toggleExpand()
            ModeKey.EN_T9     -> enT9Engine.toggleExpand()
        }
    }

    fun updateCandidates() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.updateCandidates()
            ModeKey.CN_T9     -> cnT9Engine.updateCandidates()
            ModeKey.EN_QWERTY -> enQwertyEngine.updateCandidates()
            ModeKey.EN_T9     -> enT9Engine.updateCandidates()
        }
    }

    fun handleSpaceKey() {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.handleSpaceKey()
            ModeKey.CN_T9     -> cnT9Engine.handleSpaceKey()
            ModeKey.EN_QWERTY -> enQwertyEngine.handleSpaceKey()
            ModeKey.EN_T9     -> enT9Engine.handleSpaceKey()
        }
    }

    fun commitFirstCandidateOnEnter(): Boolean {
        return when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.commitFirstCandidateOnEnter()
            ModeKey.CN_T9     -> cnT9Engine.commitFirstCandidateOnEnter()
            ModeKey.EN_QWERTY -> enQwertyEngine.commitFirstCandidateOnEnter()
            ModeKey.EN_T9     -> enT9Engine.commitFirstCandidateOnEnter()
        }
    }

    fun commitCandidateAt(index: Int) {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.commitCandidateAt(index)
            ModeKey.CN_T9     -> cnT9Engine.commitCandidateAt(index)
            ModeKey.EN_QWERTY -> enQwertyEngine.commitCandidateAt(index)
            ModeKey.EN_T9     -> enT9Engine.commitCandidateAt(index)
        }
    }

    fun commitCandidate(cand: Candidate) {
        when (currentModeKey()) {
            ModeKey.CN_QWERTY -> cnQwertyEngine.commitCandidate(cand)
            ModeKey.CN_T9     -> cnT9Engine.commitCandidate(cand)
            ModeKey.EN_QWERTY -> enQwertyEngine.commitCandidate(cand)
            ModeKey.EN_T9     -> enT9Engine.commitCandidate(cand)
        }
    }
}
