package com.example.myapp.keyboard.core

import android.view.View

interface IKeyboardMode {
    fun getView(): View
    fun onActivate()
    fun applyTheme(themeMode: Int)
}
