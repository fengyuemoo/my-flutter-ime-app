package com.example.myapp.ime.prefs

import android.content.Context
import android.content.SharedPreferences

object KeyboardPrefs {
    const val PREFS_NAME = "KeyboardPrefs"

    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_LAYOUT_MODE = "layout_mode" // 0=Qwerty, 1=T9

    // NEW: Font prefs
    const val KEY_FONT_FAMILY = "font_family"
    const val KEY_FONT_SCALE = "font_scale" // float

    const val THEME_LIGHT = 0
    const val THEME_DARK = 1

    const val LAYOUT_QWERTY = 0
    const val LAYOUT_T9 = 1

    private const val DEFAULT_FONT_FAMILY = "sans-serif-light"
    private const val DEFAULT_FONT_SCALE = 1.0f

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadThemeMode(context: Context): Int {
        return prefs(context).getInt(KEY_THEME_MODE, THEME_LIGHT)
    }

    fun loadUseT9Layout(context: Context): Boolean {
        return prefs(context).getInt(KEY_LAYOUT_MODE, LAYOUT_QWERTY) == LAYOUT_T9
    }

    fun saveThemeMode(context: Context, themeMode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, themeMode).apply()
    }

    fun saveUseT9Layout(context: Context, useT9Layout: Boolean) {
        prefs(context).edit()
            .putInt(KEY_LAYOUT_MODE, if (useT9Layout) LAYOUT_T9 else LAYOUT_QWERTY)
            .apply()
    }

    // NEW: Font family + scale
    fun loadFontFamily(context: Context): String {
        return prefs(context).getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY) ?: DEFAULT_FONT_FAMILY
    }

    fun saveFontFamily(context: Context, family: String) {
        prefs(context).edit().putString(KEY_FONT_FAMILY, family).apply()
    }

    fun loadFontScale(context: Context): Float {
        return prefs(context).getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)
    }

    fun saveFontScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }
}
