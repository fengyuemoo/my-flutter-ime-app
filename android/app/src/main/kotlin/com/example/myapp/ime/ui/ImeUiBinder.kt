package com.example.myapp.ime.ui

import android.view.View
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.prefs.LayoutController
import com.example.myapp.ime.ui.api.UiStateActions

class ImeUiBinder(
    private val rootView: View,
    private val ui: ImeUi,
    private val imeActions: ImeActions,
    private val uiStateActions: UiStateActions,
    private val layoutController: LayoutController
) {
    fun bind() {
        ui.getExpandButton().setOnClickListener { uiStateActions.toggleCandidatesExpanded() }

        ui.rootView.findViewById<View>(R.id.expandpanelclose)
            .setOnClickListener { uiStateActions.toggleCandidatesExpanded() }

        ui.rootView.findViewById<View>(R.id.expandpaneldel)
            .setOnClickListener { imeActions.handleBackspace() }

        ui.rootView.findViewById<View>(R.id.expandpanelreenter)
            .setOnClickListener { imeActions.clearComposing() }

        uiStateActions.syncFilterButtonState()
        ui.getFilterButton().setOnClickListener { uiStateActions.toggleSingleCharMode() }

        ui.rootView.findViewById<View>(R.id.btntoollayout)
            .setOnClickListener { layoutController.toggle() }
    }
}
