package com.example.myapp.ime.theme

import android.content.Context
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.prefs.KeyboardPrefs
import com.example.myapp.ime.ui.ImeUi

class ThemeController(
    private val context: Context,
    private val uiProvider: () -> ImeUi?,
    private val keyboardControllerProvider: () -> KeyboardController?
) {
    var themeMode: Int = 0
        private set

    fun load() {
        themeMode = KeyboardPrefs.loadThemeMode(context)
        uiProvider()?.setThemeMode(themeMode)
    }

    fun save() {
        KeyboardPrefs.saveThemeMode(context, themeMode)
    }

    fun apply() {
        uiProvider()?.applyTheme(themeMode)
        keyboardControllerProvider()?.applyTheme(themeMode)
    }

    fun toggle() {
        themeMode = if (themeMode == 0) 1 else 0
        save()
        uiProvider()?.setThemeMode(themeMode)
        apply()
    }
}
