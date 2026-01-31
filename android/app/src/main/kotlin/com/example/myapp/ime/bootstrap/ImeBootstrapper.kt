package com.example.myapp.ime.bootstrap

import com.example.myapp.ime.ImeGraph

class ImeBootstrapper(
    private val graph: ImeGraph
) {
    fun initFromPrefsAndEnsureDict() {
        graph.themeController.load()
        graph.layoutController.load()

        // 与 reload/update 行为保持一致：面板打开时不切主键盘，避免打断用户正在看的面板。
        if (!graph.keyboardController.isPanelOpen()) {
            graph.keyboardController.setMainMode(
                isChinese = graph.keyboardController.isChinese,
                useT9Layout = graph.keyboardController.useT9Layout
            )
        }

        graph.themeController.apply()
        ensureDictReadyIfNeeded()
    }

    fun resetUiForNewInput() {
        graph.dispatcher.clearComposing()
        graph.ui.setExpanded(false, isComposing = false)
    }

    fun reloadPrefsAndEnsureDict() {
        graph.themeController.load()
        graph.layoutController.load()

        ensureDictReadyIfNeeded()
        updateActiveKeyboardIfNeeded()

        graph.themeController.apply()
    }

    private fun updateActiveKeyboardIfNeeded() {
        // 面板模式下不切主键盘
        if (graph.keyboardController.isPanelOpen()) return

        graph.keyboardController.setMainMode(
            isChinese = graph.keyboardController.isChinese,
            useT9Layout = graph.keyboardController.useT9Layout
        )
    }

    private fun ensureDictReadyIfNeeded() {
        if (graph.dictManager.isReady) return

        graph.dictManager.ensureReadyAsync(force = false, onDone = { ok: Boolean ->
            if (!ok) return@ensureReadyAsync

            // ComposingSession 已被拆成 hub：取当前模式对应的 session
            val session = graph.sessions.current()
            if (session.isComposing()) {
                graph.dispatcher.refreshCandidates()
            }
        })
    }
}
