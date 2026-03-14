package com.example.myapp.ime.candidate

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.cn.t9.CnT9PreeditFormatter
import com.example.myapp.ime.compose.cn.t9.PreeditDisplay
import com.example.myapp.ime.compose.cn.t9.PreeditSegment
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
    private val contextWindow: CnT9ContextWindow? = null,
    /**
     * R-P04：由外部（ImeActionDispatcher）注入的焦点段下标查询函数。
     * 每次 resolveComposingPreviewDisplay() 时调用，获取 CnT9InputEngine 当前焦点。
     * 为 null 时退化为 -1（无焦点高亮）。
     */
    private val focusedSegmentIndexProvider: (() -> Int)? = null
) : UiStateActions {

    // P1 修复：持有 CnT9PreeditFormatter 实例，启用缓存与 invalidate() 能力
    private val preeditFormatter = CnT9PreeditFormatter()

    private val cnQwertyEngine = CnQwertyCandidateEngine(
        ui                 = ui,
        keyboardController = keyboardController,
        dictEngine         = dictEngine,
        session            = sessions.cnQwerty,
        commitRaw          = commitRaw,
        clearComposing     = clearComposing,
        isRawCommitMode    = { keyboardController.isRawCommitMode() }
    )

    private val cnT9Engine = CnT9CandidateEngine(
        ui                  = ui,
        keyboardController  = keyboardController,
        dictEngine          = dictEngine,
        session             = sessions.cnT9,
        commitRaw           = commitRaw,
        clearComposing      = clearComposing,
        isRawCommitMode     = { keyboardController.isRawCommitMode() },
        userChoiceStore     = userChoiceStore,
        contextWindow       = contextWindow,
        onPreeditInvalidate = { preeditFormatter.invalidate() }   // P1 修复
    )

    private val enQwertyEngine = EnQwertyCandidateEngine(
        ui                 = ui,
        keyboardController = keyboardController,
        dictEngine         = dictEngine,
        session            = sessions.enQwerty,
        commitRaw          = commitRaw,
        clearComposing     = clearComposing,
        isRawCommitMode    = { keyboardController.isRawCommitMode() }
    )

    private val enT9Engine = EnT9CandidateEngine(
        ui                 = ui,
        keyboardController = keyboardController,
        dictEngine         = dictEngine,
        session            = sessions.enT9,
        commitRaw          = commitRaw,
        clearComposing     = clearComposing,
        isRawCommitMode    = { keyboardController.isRawCommitMode() }
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

    // ── 生命周期 ───────────────────────────────────────────────────

    /**
     * 输入焦点切换到新输入框时由 ImeBootstrapper.resetUiForNewInput() 调用。
     *
     * ContextWindow 跨会话污染修复：
     *  转发给 cnT9Engine.onStartInput()，同时重置 pendingPenaltyOnBackspace。
     *  其他 engine 目前无焦点切换状态，无需转发。
     */
    fun onStartInput() {
        cnT9Engine.onStartInput()
    }

    // ── 其余方法（与修复前完全相同）──────────────────────────────────

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

    /**
     * R-P04：返回带段样式的 PreeditDisplay（供 ImeActionDispatcher 渲染）。
     *
     * CN-T9 模式：通过 focusedSegmentIndexProvider 获取当前焦点段，
     *             传入 preeditFormatter.format() 生成带高亮的分段结果。
     * 其他模式：  包装为单段 NORMAL 的 PreeditDisplay，保持兼容。
     */
    fun resolveComposingPreviewDisplay(): PreeditDisplay {
        return when (currentModeKey()) {
            ModeKey.CN_T9 -> preeditFormatter.format(
                session             = sessions.cnT9,
                dict                = dictEngine,
                engineOverride      = cnT9Engine.getComposingPreviewOverride(),
                focusedSegmentIndex = focusedSegmentIndexProvider?.invoke() ?: -1
            )
            else -> {
                val override = getComposingPreviewOverride()
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val plain = override
                    ?: currentSession()
                        .displayText(useT9Layout = currentUseT9Layout())
                        ?.trim()?.takeIf { it.isNotEmpty() }
                if (plain.isNullOrEmpty()) return PreeditDisplay.EMPTY
                PreeditDisplay(
                    plainText = plain,
                    segments  = listOf(PreeditSegment(plain, PreeditSegment.Style.NORMAL))
                )
            }
        }
    }

    /**
     * 兼容旧调用方，仅返回纯文字。
     * 新代码应调用 resolveComposingPreviewDisplay()。
     */
    fun resolveComposingPreviewText(): String? {
        return resolveComposingPreviewDisplay().plainText.takeIf { it.isNotEmpty() }
    }

    fun resolveEnterCommitText(): String? {
        return getEnterCommitTextOverride()?.trim()?.takeIf { it.isNotEmpty() }
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
