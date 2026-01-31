package com.example.myapp.ime.prefs

import android.content.Context
import com.example.myapp.ime.keyboard.KeyboardController

class LayoutController(
    private val context: Context,
    private val keyboardControllerProvider: () -> KeyboardController?
) {
    var useT9Layout: Boolean = false
        private set

    fun load() {
        useT9Layout = KeyboardPrefs.loadUseT9Layout(context)
        keyboardControllerProvider()?.setLayout(useT9Layout)
    }

    fun save() {
        KeyboardPrefs.saveUseT9Layout(context, useT9Layout)
    }

    fun toggle() {
        useT9Layout = !useT9Layout
        save()
        keyboardControllerProvider()?.setLayout(useT9Layout)
    }
}
