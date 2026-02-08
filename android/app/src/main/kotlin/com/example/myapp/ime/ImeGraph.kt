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
import com.example.myapp.ime.theme.FontController
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

            // 0) Mode holder
            val modeHolder = KeyboardModeHolder()

            // 1) Sessions hub
            val sessions = ComposingSessionHub(
                modeProvider = { modeHolder.mode }
            )

            // 2) Dispatcher
            val dispatcher = ImeActionDispatcher(
                context = context,
                sessions = sessions,
                inputConnectionProvider = inputConnectionProvider
            )

            // 3) Dictionary
            val dictManager = DictionaryManager(
                context = context,
                mainHandler = Handler(Looper.getMainLooper())
            )

            // 4) Candidate composer (kept for compatibility; handlers will use dict directly)
            val candidateComposer = CandidateComposer(dictManager.dictionary)

            // 5) Keyboard registry + controller
            val keyboardRegistry = DefaultKeyboardRegistry(
                context,
                dispatcher as ImeActions
            )

            val keyboardController = KeyboardController(
                bodyFrame,
                host,
                keyboardRegistry
            )

            // 6) Mode binding
            modeHolder.mode = keyboardController.getMainMode()
            keyboardController.onModeChanged = { modeHolder.mode = it }

            // 7) Candidate controller (inject dictEngine)
            val candidateController = CandidateController(
                ui = ui,
                keyboardController = keyboardController,
                dictEngine = dictManager.dictionary,
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

            keyboardController.themeModeProvider = { themeController.themeMode }

            val layoutController = LayoutController(
                context = context,
                keyboardControllerProvider = { keyboardController }
            )

            // NEW: Font controller（ImeUiBinder 需要这个参数）
            val fontController = FontController(
                context = context,
                uiProvider = { ui },
                keyboardControllerProvider = { keyboardController }
            )
            fontController.load()
            fontController.apply()

            // NEW: 让 KeyboardController 在切键盘/主题后能自动保持字体/字号
            keyboardController.fontConfigProvider = { fontController.fontFamily to fontController.fontScale }

            // 每次键盘 view 切换/重建后，UI 再应用一次（覆盖新 view）
            val prevOnKeyboardChanged = keyboardController.onKeyboardChanged
            keyboardController.onKeyboardChanged = {
                prevOnKeyboardChanged?.invoke()
                ui.applySavedFontNow()
            }

            // 11) UI binder（注意：这里必须传 fontController）
            val uiBinder = ImeUiBinder(
                rootView = rootView,
                ui = ui,
                imeActions = dispatcher as ImeActions,
                uiStateActions = candidateController,
                layoutController = layoutController,
                fontController = fontController
            )

            // 初始化时也应用一次已保存字体/字号
            ui.applySavedFontNow()

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
