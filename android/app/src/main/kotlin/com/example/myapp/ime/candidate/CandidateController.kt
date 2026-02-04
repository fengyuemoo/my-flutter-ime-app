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
    private val commitRaw: (String) -> Unit, // commit raw text
    private val clearComposing: () -> Unit, // SimpleImeService/dispatcher.clearComposing
    private val updateComposingView: () -> Unit, // dispatcher.refreshComposingView
) : UiStateActions {

    private var isExpanded = false
    private var isSingleCharMode = false
    private var currentCandidates: ArrayList<Candidate> = ArrayList()

    private fun session(): ComposingSession = sessions.current()

    // -------- UiStateActions (给 UI 层调用的最小接口) --------
    override fun toggleCandidatesExpanded() {
        toggleExpand()
    }

    override fun syncFilterButtonState() {
        syncFilterButton()
    }

    override fun toggleSingleCharMode() {
        toggleSingleCharModeInternal()
    }

    // -------- 原有对外/内部逻辑（保持功能不变） --------
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

        // 正在 composing：确保 UI 在 composing 状态（展开/不展开由 isExpanded 决定）
        ui.showComposingState(isExpanded)

        // 候选/侧栏逻辑只看“主模式”，不看当前键盘实例类型（避免数字/符号面板干扰）
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

    /**
     * 给 Enter 用：提交当前候选第 1 个（如果有）。
     * 返回 true 表示已消费 Enter（已提交并清空 composing）；false 表示没有候选可提交。
     */
    fun commitFirstCandidateOnEnter(): Boolean {
        if (currentCandidates.isEmpty()) return false
        commitCandidate(currentCandidates[0])
        return true
    }

    fun commitCandidate(cand: Candidate) {
        // 数字/符号等面板模式：候选点击应直接提交 raw，不走 composing pick 逻辑
        if (keyboardController.isRawCommitMode()) {
            commitRaw(cand.word)
            clearComposing()
            return
        }

        // pick 分支也只看“主模式”，避免面板打开导致 isT9KeyboardActive() 变化
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
