package com.example.myapp.ime.ui

import android.view.View
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.prefs.LayoutController
import com.example.myapp.ime.theme.FontController
import com.example.myapp.ime.ui.api.UiStateActions

class ImeUiBinder(
    private val rootView: View,
    private val ui: ImeUi,
    private val imeActions: ImeActions,
    private val uiStateActions: UiStateActions,
    private val layoutController: LayoutController,
    private val fontController: FontController,
    private val keyboardController: KeyboardController
) {
    companion object {
        // 主键盘的 Enter 特殊键 label（英文全键盘里就是 ⏎）
        private const val ENTER_LABEL = "⏎"
    }

    fun bind() {
        ui.getExpandButton().setOnClickListener { uiStateActions.toggleCandidatesExpanded() }

        rootView.findViewById<View>(R.id.expandpanelclose)
            .setOnClickListener { uiStateActions.toggleCandidatesExpanded() }

        // 回退键：始终是退格
        rootView.findViewById<View>(R.id.expandpaneldel)
            .setOnClickListener { imeActions.handleBackspace() }

        // 右侧两颗键（重输/过滤）需要随中英模式变化
        refreshExpandedPanelRightButtons(keyboardController.getMainMode())
        hookModeChangeRefresh()

        rootView.findViewById<View>(R.id.btntoollayout)
            .setOnClickListener { layoutController.toggle() }

        // 字体/字号按钮
        rootView.findViewById<View>(R.id.btntoolfont)
            .setOnClickListener { fontController.showPickerDialog() }
    }

    private fun hookModeChangeRefresh() {
        val old = keyboardController.onModeChanged
        keyboardController.onModeChanged = { newMode ->
            old?.invoke(newMode)
            refreshExpandedPanelRightButtons(newMode)
        }
    }

    private fun refreshExpandedPanelRightButtons(mode: KeyboardMode) {
        val btnReenter = rootView.findViewById<Button>(R.id.expandpanelreenter)
        val btnFilter = ui.getFilterButton()

        if (mode.isChinese) {
            // 中文模式：保持原有“重输 + 全部/单字切换”
            btnReenter.text = "重输"
            btnReenter.setOnClickListener { imeActions.clearComposing() }

            uiStateActions.syncFilterButtonState()
            btnFilter.setOnClickListener { uiStateActions.toggleSingleCharMode() }
        } else {
            // 英文模式（全键盘/T9 都一样）：
            // - “重输”显示 Clear，行为仍是 clearComposing
            // - “全部/单字”键改为换行键：显示 ⏎，点击走 handleSpecialKey(⏎)
            btnReenter.text = "Clear"
            btnReenter.setOnClickListener { imeActions.clearComposing() }

            btnFilter.text = ENTER_LABEL
            btnFilter.setOnClickListener { imeActions.handleSpecialKey(ENTER_LABEL) }
        }
    }
}
