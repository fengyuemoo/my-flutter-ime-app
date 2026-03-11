package com.example.myapp.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSessionHub
import com.example.myapp.ime.dict.DictionaryManager
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.ime.mode.cn.CnT9ContextWindow
import com.example.myapp.ime.mode.cn.CnT9UserChoiceStore
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

            val modeHolder = KeyboardModeHolder()

            val sessions = ComposingSessionHub(
                modeProvider = { modeHolder.mode }
            )

            val dispatcher = ImeActionDispatcher(
                context = context,
                sessions = sessions,
                inputConnectionProvider = inputConnectionProvider
            )

            val dictManager = DictionaryManager(
                context = context,
                mainHandler = Handler(Looper.getMainLooper())
            )

            val keyboardRegistry = DefaultKeyboardRegistry(
                context,
                dispatcher as ImeActions
            )

            val keyboardController = KeyboardController(
                bodyFrame,
                host,
                keyboardRegistry
            )

            modeHolder.mode = keyboardController.getMainMode()
            keyboardController.onModeChanged = { modeHolder.mode = it }

            // 用户选词学习存储：生命周期与 ImeGraph 一致，持久化
            val userChoiceStore = CnT9UserChoiceStore(context)

            // 上下文窗口：会话级内存，不持久化，换行/焦点切换时由引擎主动 clear
            val contextWindow = CnT9ContextWindow()             // ← 新增

            val candidateController = CandidateController(
                ui = ui,
                keyboardController = keyboardController,
                dictEngine = dictManager.dictionary,
                sessions = sessions,
                commitRaw = { text -> inputConnectionProvider()?.commitText(text, 1) },
                clearComposing = { dispatcher.clearComposing() },
                userChoiceStore = userChoiceStore,
                contextWindow = contextWindow                    // ← 新增
            )

            val toolbarController = ToolbarController(
                rootView = rootView,
                keyboardControllerProvider = { keyboardController }
            )

            dispatcher.attach(
                ui = ui,
                keyboardController = keyboardController,
                candidateController = candidateController,
                onToolbarUpdate = { host.onToolbarUpdate() }
            )

            val themeController = ThemeController(
                context = context,
                uiProvider = { ui },
                keyboardControllerProvider = { keyboardController }
            )

            keyboardController.themeModeProvider = { themeController.themeMode }

            keyboardController.englishPredictEnabledProvider = { dispatcher.getEnglishPredictEnabled() }

            val layoutController = LayoutController(
                context = context,
                keyboardControllerProvider = { keyboardController }
            )

            val fontController = FontController(
                context = context,
                uiProvider = { ui },
                keyboardControllerProvider = { keyboardController }
            )
            fontController.load()
            fontController.apply()

            keyboardController.fontConfigProvider = { fontController.fontFamily to fontController.fontScale }

            val prevOnKeyboardChanged = keyboardController.onKeyboardChanged
            keyboardController.onKeyboardChanged = {
                prevOnKeyboardChanged?.invoke()
                ui.applySavedFontNow()
            }

            val uiBinder = ImeUiBinder(
                rootView = rootView,
                ui = ui,
                imeActions = dispatcher as ImeActions,
                uiStateActions = candidateController,
                layoutController = layoutController,
                fontController = fontController,
                keyboardController = keyboardController
            )

            ui.applySavedFontNow()

            return ImeGraph(
                ui = ui,
                sessions = sessions,
                dispatcher = dispatcher,
                dictManager = dictManager,
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
