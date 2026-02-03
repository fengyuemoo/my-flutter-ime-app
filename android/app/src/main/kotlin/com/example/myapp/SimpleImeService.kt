package com.example.myapp

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.FrameLayout
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.ImeGraph
import com.example.myapp.ime.bootstrap.ImeBootstrapper
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.prefs.KeyboardPrefs
import com.example.myapp.ime.ui.ImeUi

class SimpleImeService : InputMethodService(), KeyboardHost {

    private lateinit var ui: ImeUi
    private lateinit var mainView: View
    private lateinit var bodyFrame: FrameLayout

    private lateinit var graph: ImeGraph
    private lateinit var bootstrapper: ImeBootstrapper

    private var onCandidateClick: (Candidate) -> Unit = {}

    override fun onToolbarUpdate() {
        if (this::graph.isInitialized) graph.toolbarController.refresh()
    }

    override fun onClearComposingRequested() {
        if (this::graph.isInitialized) graph.dispatcher.clearComposing()
    }

    override fun onCreateInputView(): View {
        ui = ImeUi()
        mainView = ui.inflate(layoutInflater) { cand -> onCandidateClick(cand) }
        bodyFrame = ui.bodyFrame

        graph = ImeGraph.build(
            context = this,
            rootView = mainView,
            bodyFrame = bodyFrame,
            ui = ui,
            host = this,
            inputConnectionProvider = { currentInputConnection }
        )

        onCandidateClick = { cand -> graph.candidateController.commitCandidate(cand) }

        graph.uiBinder.bind()

        bootstrapper = ImeBootstrapper(graph)
        bootstrapper.initFromPrefsAndEnsureDict()

        bindToolbarButtons()

        // 初始应用一次主题（让候选条/面板颜色立即正确）
        val themeMode = KeyboardPrefs.loadThemeMode(this)
        applyThemeGlobally(themeMode)

        return mainView
    }

    private fun bindToolbarButtons() {
        // 🎨 Theme button
        ui.getThemeButton().setOnClickListener {
            val cur = KeyboardPrefs.loadThemeMode(this)
            val next = if (cur == KeyboardPrefs.THEME_DARK) KeyboardPrefs.THEME_LIGHT else KeyboardPrefs.THEME_DARK
            KeyboardPrefs.saveThemeMode(this, next)

            // Apply theme immediately to all UI components
            applyThemeGlobally(next)
        }

        // ⌨️ Layout button (Qwerty ↔ T9)
        ui.getLayoutButton().setOnClickListener {
            val cur = KeyboardPrefs.loadUseT9Layout(this)
            KeyboardPrefs.saveUseT9Layout(this, !cur)
            
            // Rebuild keyboard body immediately
            onToolbarUpdate()
        }
    }

    /**
     * Apply theme to all UI components: ImeUi + CandidateAdapter + current active keyboard
     */
    private fun applyThemeGlobally(themeMode: Int) {
        // 1) Apply to ImeUi (toolbar, candidate strip, expanded panel, etc.)
        ui.applyTheme(themeMode)
        ui.setThemeMode(themeMode)

        // 2) Apply to current keyboard (if ToolbarController has applyTheme, call it)
        if (this::graph.isInitialized) {
            graph.toolbarController.applyTheme(themeMode)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        if (!this::graph.isInitialized) return

        bootstrapper.resetUiForNewInput()
        bootstrapper.reloadPrefsAndEnsureDict()

        val themeMode = KeyboardPrefs.loadThemeMode(this)
        applyThemeGlobally(themeMode)
    }
}
