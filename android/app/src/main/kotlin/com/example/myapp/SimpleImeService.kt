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

        // 初始同步一次主题：候选条/面板/键盘本体都立即正确
        graph.themeController.load()
        graph.themeController.apply()

        return mainView
    }

    private fun bindToolbarButtons() {
        // 🎨 Theme button：切一次主题就全局生效（当前键盘立即 apply；未来切到的新键盘也会自动 apply）
        ui.getThemeButton().setOnClickListener {
            if (this::graph.isInitialized) {
                graph.themeController.toggle()
            }
        }

        // ⌨️ Layout button (Qwerty ↔ T9)
        ui.getLayoutButton().setOnClickListener {
            val cur = KeyboardPrefs.loadUseT9Layout(this)
            val next = !cur
            KeyboardPrefs.saveUseT9Layout(this, next)

            // 真正重建 keyboard body：让 KeyboardController 切换主键盘并重建 bodyFrame 内容
            if (this::graph.isInitialized) {
                graph.keyboardController.setLayout(next)
            } else {
                onToolbarUpdate()
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        if (!this::graph.isInitialized) return

        bootstrapper.resetUiForNewInput()
        bootstrapper.reloadPrefsAndEnsureDict()

        // 每次显示输入法视图时再同步一次主题，确保与 prefs 一致
        graph.themeController.load()
        graph.themeController.apply()
    }
}
