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
        private const val ENTER_LABEL = "⏎"
    }

    fun bind() {
        ui.getExpandButton().setOnClickListener { uiStateActions.toggleCandidatesExpanded() }

        ui.rootView.findViewById<View>(R.id.expandpanelclose)
            .setOnClickListener { uiStateActions.toggleCandidatesExpanded() }

        ui.rootView.findViewById<View>(R.id.expandpaneldel)
            .setOnClickListener { imeActions.handleBackspace() }

        // First time bind + keep refreshed on mode changes.
        refreshExpandedPanelRightButtons(keyboardController.getMainMode())
        hookModeChangeRefresh()

        ui.rootView.findViewById<View>(R.id.btntoollayout)
            .setOnClickListener { layoutController.toggle() }

        ui.rootView.findViewById<View>(R.id.btntoolfont)
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
            ui.setExpandedPanelFilterOverride(null)

            btnReenter.text = "重输"
            btnReenter.setOnClickListener { imeActions.clearComposing() }

            uiStateActions.syncFilterButtonState()
            btnFilter.setOnClickListener { uiStateActions.toggleSingleCharMode() }
        } else {
            btnReenter.text = "Clear"
            btnReenter.setOnClickListener { imeActions.clearComposing() }

            ui.setExpandedPanelFilterOverride(ENTER_LABEL)
            btnFilter.setOnClickListener { imeActions.handleSpecialKey(ENTER_LABEL) }
        }
    }
}
