package com.example.myapp.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.dict.DictionaryManager
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.prefs.LayoutController
import com.example.myapp.ime.router.ImeActionDispatcher
import com.example.myapp.ime.theme.ThemeController
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.ime.ui.ImeUiBinder
import com.example.myapp.ime.ui.ToolbarController
import com.example.myapp.keyboard.DefaultKeyboardRegistry

class ImeGraph(
    val ui: ImeUi,
    val sessions: ComposingSessionHub,
    val dispatcher: ImeActionDispatcher,
    val dictManager: DictionaryManager,
    val candidateComposer: CandidateComposer,
    val keyboardController: KeyboardController,
    val candidateController: CandidateController,
    val themeController: ThemeController,
    val layoutController: LayoutController,
    val toolbarController: ToolbarController,
    val uiBinder: ImeUiBinder
) {

    companion object {

        private class KeyboardModeHolder {
            @Volatile
            var mode: KeyboardMode = KeyboardMode(isChinese = true, useT9Layout = false)
        }

        fun build(
            context: Context,
            rootView: View,
            bodyFrame: FrameLayout,
            ui: ImeUi,
            host: KeyboardHost,
            inputConnectionProvider: () -> InputConnection?
        ): ImeGraph {

            // 0) Mode holder（用于让 sessions hub 在 keyboardController 创建前也能工作）
            val modeHolder = KeyboardModeHolder()

            // 1) Sessions hub：只依赖 modeProvider lambda（不直接依赖 keyboardController）
            val sessions = ComposingSessionHub(
                modeProvider = { modeHolder.mode }
            )

            // 2) Dispatcher：实现 ImeActions，供键盘/KeyboardController 使用
            val dispatcher = ImeActionDispatcher(
                sessions = sessions,
                inputConnectionProvider = inputConnectionProvider
            )

            // 3) Dictionary
            val dictManager = DictionaryManager(
                context = context,
                mainHandler = Handler(Looper.getMainLooper())
            )

            // 4) Candidate composer（你当前项目是 dictManager.dictionary）
            val candidateComposer = CandidateComposer(dictManager.dictionary)

            // 5) Keyboard registry + controller（改回两参构造）
            val keyboardRegistry = DefaultKeyboardRegistry(
                context,
                dispatcher as ImeActions
            )

            val keyboardController = KeyboardController(
                bodyFrame,
                host,
                keyboardRegistry
            )

            // 6) 把 modeHolder 与真实主模式绑定起来 + 订阅后续变化
            modeHolder.mode = keyboardController.getMainMode()
            keyboardController.onModeChanged = { modeHolder.mode = it }

            // 7) Candidate controller（依赖 sessions hub）
            val candidateController = CandidateController(
                ui = ui,
                keyboardController = keyboardController,
                candidateComposer = candidateComposer,
                sessions = sessions,
                commitRaw = { text -> inputConnectionProvider()?.commitText(text, 1) },
                clearComposing = { dispatcher.clearComposing() },
                updateComposingView = { dispatcher.refreshComposingView() }
            )

            // 8) Toolbar
            val toolbarController = ToolbarController(
                rootView = rootView,
                keyboardControllerProvider = { keyboardController }
            )

            // 9) Attach dispatcher
            dispatcher.attach(
                ui = ui,
                keyboardController = keyboardController,
                candidateController = candidateController,
                onToolbarUpdate = { host.onToolbarUpdate() }
            )

            // 10) Theme/Layout
            val themeController = ThemeController(
                context = context,
                uiProvider = { ui },
                keyboardControllerProvider = { keyboardController }
            )

            val layoutController = LayoutController(
                context = context,
                keyboardControllerProvider = { keyboardController }
            )

            // 11) UI binder（按你当前 ImeUiBinder 构造参数）
            val uiBinder = ImeUiBinder(
                rootView = rootView,
                ui = ui,
                imeActions = dispatcher as ImeActions,
                uiStateActions = candidateController,
                layoutController = layoutController
            )

            return ImeGraph(
                ui = ui,
                sessions = sessions,
                dispatcher = dispatcher,
                dictManager = dictManager,
                candidateComposer = candidateComposer,
                keyboardController = keyboardController,
                candidateController = candidateController,
                themeController = themeController,
                layoutController = layoutController,
                toolbarController = toolbarController,
                uiBinder = uiBinder
            )
        }
    }
}
