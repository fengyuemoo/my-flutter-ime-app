package com.example.myapp.ime.compose.cn.t9

import android.content.Context
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.CnBaseInputEngine
import com.example.myapp.ime.ui.ImeUi

class CnT9InputEngine(
    context: Context,
    ui: ImeUi,
    keyboardController: KeyboardController,
    private val candidateController: CandidateController,
    private val session: ComposingSession,
    inputConnectionProvider: () -> InputConnection?
) : CnBaseInputEngine(
    context = context,
    ui = ui,
    keyboardController = keyboardController,
    candidateController = candidateController,
    session = session,
    inputConnectionProvider = inputConnectionProvider,
    useT9Layout = true,
    logTag = "CnT9InputEngine",
    strategy = CnT9ComposeStrategy(
        sessionProvider = { session },
        enterCommitProvider = { candidateController.getEnterCommitTextOverride() }
    )
) {
    private val stateMachine = CnT9StateMachine()

    private var currentState: CnT9SessionState = CnT9SessionState()
    private var lastEvent: CnT9StateEvent? = null

    // 焦点索引独立保存：只有明确的焦点事件才改变，syncStateFromSession 不覆盖它
    private var pinnedFocusedIndex: Int? = null

    init {
        currentState = buildStateFromSession(focusOverride = null)
    }

    // ── 公开读取接口 ────────────────────────────────────────────────

    fun getStateSnapshot(): CnT9StateSnapshot = stateMachine.snapshot(currentState, lastEvent)

    /** 供键盘 UI 读取当前焦点音节下标（-1 表示无焦点）*/
    fun getFocusedSegmentIndex(): Int = pinnedFocusedIndex ?: -1

    // ── 核心状态同步 ────────────────────────────────────────────────

    /**
     * 从 session 重建 CnT9SessionState。
     * - focusOverride: 明确指定新焦点；null 表示沿用 pinnedFocusedIndex
     * - clearFocus:    true 强制清除焦点（clearComposing / 新增数字输入时）
     */
    private fun buildStateFromSession(
        focusOverride: Int? = null,
        clearFocus: Boolean = false
    ): CnT9SessionState {
        val segments = session.t9MaterializedSegments.mapIndexed { _, snap ->
            CnT9MaterializedSegment(
                syllable = snap.syllable,
                digitChunk = snap.digitChunk,
                locked = snap.locked,
                localCuts = snap.localCuts.toSet()
            )
        }

        val resolvedFocus = when {
            clearFocus -> null
            focusOverride != null -> focusOverride.takeIf { it in segments.indices }
            else -> pinnedFocusedIndex?.takeIf { it in segments.indices }
        }

        pinnedFocusedIndex = resolvedFocus

        return CnT9SessionState(
            rawDigits = session.rawT9Digits,
            committedPrefix = session.committedPrefix,
            materializedSegments = segments,
            manualCuts = session.t9ManualCuts.toSet(),
            focusedSegmentIndex = resolvedFocus,
            selectedCandidateIndex = currentState.selectedCandidateIndex,
            isCandidatesExpanded = currentState.isCandidatesExpanded,
            revision = currentState.revision + 1
        )
    }

    private fun applyEvent(
        event: CnT9StateEvent? = null,
        focusOverride: Int? = null,
        clearFocus: Boolean = false
    ) {
        lastEvent = event
        currentState = buildStateFromSession(
            focusOverride = focusOverride,
            clearFocus = clearFocus
        )
    }

    private fun lastMaterializedIndexOrNull(): Int? =
        session.pinyinStack.lastIndex.takeIf { it >= 0 }

    // ── 音节栏：焦点 + 锁定 ─────────────────────────────────────────

    /**
     * 点击音节栏某个音节：只移动焦点，不 rollback（保持锁定段不被重新解析）。
     */
    fun focusMaterializedSegment(index: Int) {
        if (index !in session.pinyinStack.indices) return
        applyEvent(
            event = CnT9StateEvent.SidebarSegmentFocused(index),
            focusOverride = index
        )
        super.refreshCandidates()
    }

    /**
     * 替换焦点音节为指定拼音（音节消歧）。
     * 只更新 pinyinStack 对应项，不改变 rawDigits / cuts。
     */
    fun replaceFocusedSegmentWith(pinyin: String) {
        val focusedIdx = pinnedFocusedIndex ?: return
        val changed = session.replaceMaterializedSegmentAt(focusedIdx, pinyin)
        if (!changed) return

        applyEvent(
            event = CnT9StateEvent.SidebarSegmentFocused(focusedIdx),
            focusOverride = focusedIdx
        )
        super.refreshCandidates()
    }

    /**
     * 分词切换手势：在 rawDigits 首位插入/移除手动切分点。
     * 注意：实际的"数字1键分词"触发入口在 ImeActionDispatcher.handleSpecialKey("分词")，
     * 本方法供需要精细控制切分点位置的内部调用。
     */
    fun cycleManualCut() {
        if (session.rawT9Digits.isEmpty()) return

        val cuts = session.t9ManualCuts
        val cutPos = 1

        if (cuts.contains(cutPos)) {
            session.removeT9ManualCut(cutPos)
        } else {
            session.insertT9ManualCut(cutPos)
        }

        applyEvent(
            event = CnT9StateEvent.DigitsAppended(""),
            clearFocus = false
        )
        super.refreshCandidates()
    }

    // ── 覆写父类行为 ────────────────────────────────────────────────

    override fun refreshCandidates() {
        super.refreshCandidates()
        applyEvent()
    }

    override fun clearComposing() {
        super.clearComposing()
        applyEvent(
            event = CnT9StateEvent.Cleared,
            clearFocus = true
        )
    }

    override fun handleT9Input(digit: String) {
        super.handleT9Input(digit)
        val normalized = digit.filter { it in '0'..'9' }
        // 新输入数字时焦点退回末尾（规则：继续输入时不保持音节栏焦点）
        applyEvent(
            event = if (normalized.isNotEmpty()) CnT9StateEvent.DigitsAppended(normalized) else null,
            clearFocus = true
        )
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        super.onPinyinSidebarClick(pinyin)
        // 点击音节栏确认一个音节后，焦点跟随到刚锁定的那个音节
        applyEvent(
            event = CnT9StateEvent.SidebarSegmentFocused(lastMaterializedIndexOrNull() ?: 0),
            focusOverride = lastMaterializedIndexOrNull()
        )
    }

    override fun handleBackspace() {
        val focusedIndex = pinnedFocusedIndex
            ?.takeIf { it in session.pinyinStack.indices }

        if (focusedIndex != null) {
            // 规则：焦点在某音节时，优先删该音节尾部最后一个 digit
            val consumed = session.backspaceMaterializedSegmentTailDigit(focusedIndex)
            if (consumed) {
                super.refreshCandidates()
                // 删完后焦点留在同一位；若该段被完全删空则退到前一段
                val newFocus = when {
                    focusedIndex in session.pinyinStack.indices -> focusedIndex
                    session.pinyinStack.isNotEmpty() -> session.pinyinStack.lastIndex
                    else -> null
                }
                applyEvent(
                    event = CnT9StateEvent.BackspacePressed,
                    focusOverride = newFocus,
                    clearFocus = newFocus == null
                )
                return
            }
        }

        // 无焦点或焦点段 digits 已空：走普通退格逻辑
        super.handleBackspace()
        applyEvent(
            event = CnT9StateEvent.BackspacePressed,
            clearFocus = session.pinyinStack.isEmpty()
        )
    }

    override fun handleSpaceKey() {
        val wasComposing = session.isComposing()
        super.handleSpaceKey()
        applyEvent(
            event = if (wasComposing && !session.isComposing())
                CnT9StateEvent.CandidateCommitted("")
            else
                CnT9StateEvent.CandidateSelectionStarted,
            clearFocus = true
        )
    }

    override fun handleEnter(ic: InputConnection?): Boolean {
        val wasComposing = session.isComposing()
        val consumed = super.handleEnter(ic)
        if (consumed) {
            applyEvent(
                event = if (wasComposing && !session.isComposing())
                    CnT9StateEvent.CandidateCommitted("")
                else
                    CnT9StateEvent.CandidateSelectionStarted,
                clearFocus = true
            )
        }
        return consumed
    }

    override fun beforeModeSwitch() {
        super.beforeModeSwitch()
        applyEvent(clearFocus = true)
    }

    override fun afterModeSwitch() {
        super.afterModeSwitch()
        applyEvent(clearFocus = true)
    }
}
