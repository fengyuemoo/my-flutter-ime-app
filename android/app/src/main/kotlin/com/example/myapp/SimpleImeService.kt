package com.example.myapp

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.FrameLayout
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.ImeGraph
import com.example.myapp.ime.bootstrap.ImeBootstrapper
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.ui.ImeUi

class SimpleImeService : InputMethodService(), KeyboardHost {

    private lateinit var ui: ImeUi
    private lateinit var mainView: View
    private lateinit var bodyFrame: FrameLayout

    private lateinit var graph: ImeGraph
    private lateinit var bootstrapper: ImeBootstrapper

    // 先占位，graph build 后再把它指向真正的 commit 方法
    private var onCandidateClick: (Candidate) -> Unit = {}

    override fun onToolbarUpdate() {
        if (this::graph.isInitialized) graph.toolbarController.refresh()
    }

    override fun onClearComposingRequested() {
        if (this::graph.isInitialized) graph.dispatcher.clearComposing()
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        ui = ImeUi()
        mainView = ui.inflate(layoutInflater) { cand ->
            onCandidateClick(cand)
        }
        bodyFrame = ui.bodyFrame

        // 注意：ImeGraph.build 不再接收 session 参数；session 由 ComposingSessionHub 管理
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

        return mainView
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        if (!this::graph.isInitialized) return

        bootstrapper.resetUiForNewInput()
        bootstrapper.reloadPrefsAndEnsureDict()
    }
}
