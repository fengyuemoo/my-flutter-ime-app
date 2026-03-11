package com.example.myapp.ime.bootstrap

import com.example.myapp.ime.ImeGraph

class ImeBootstrapper(
    private val graph: ImeGraph
) {
    fun initFromPrefsAndEnsureDict() {
        graph.themeController.load()
        graph.layoutController.load()

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
        graph.clearContextWindow()          // ← 新增：切换输入框时清空跨 field 上下文
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

            val session = graph.sessions.current()
            if (session.isComposing()) {
                graph.dispatcher.refreshCandidates()
            }
        })
    }
}
