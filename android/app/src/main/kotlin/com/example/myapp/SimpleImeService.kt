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

        // 初始应用一次（让候选条/面板颜色立即正确）
        ui.applyTheme(KeyboardPrefs.loadThemeMode(this))
        ui.setThemeMode(KeyboardPrefs.loadThemeMode(this))

        return mainView
    }

    private fun bindToolbarButtons() {
        ui.getThemeButton().setOnClickListener {
            val cur = KeyboardPrefs.loadThemeMode(this)
            val next = if (cur == KeyboardPrefs.THEME_DARK) KeyboardPrefs.THEME_LIGHT else KeyboardPrefs.THEME_DARK
            KeyboardPrefs.saveThemeMode(this, next)

            // 重新创建输入视图，保证资源夜间目录/颜色立即生效
            setInputView(onCreateInputView())
        }

        ui.getLayoutButton().setOnClickListener {
            val cur = KeyboardPrefs.loadUseT9Layout(this)
            KeyboardPrefs.saveUseT9Layout(this, !cur)
            // 让键盘控制器刷新布局（你已有 refresh）
            onToolbarUpdate()
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        if (!this::graph.isInitialized) return

        bootstrapper.resetUiForNewInput()
        bootstrapper.reloadPrefsAndEnsureDict()

        ui.applyTheme(KeyboardPrefs.loadThemeMode(this))
        ui.setThemeMode(KeyboardPrefs.loadThemeMode(this))
    }
}
