package com.example.myapp

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.myapp.ime.prefs.KeyboardPrefs

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activitymain)

        val btnQwerty = findViewById<Button>(R.id.btnlayoutqwerty)
        val btnT9 = findViewById<Button>(R.id.btnlayoutt9)
        val btnLight = findViewById<Button>(R.id.btnthemelight)
        val btnDark = findViewById<Button>(R.id.btnthemedark)
        val tvStatus = findViewById<TextView>(R.id.tvstatus)

        fun refreshStatus() {
            val themeMode = KeyboardPrefs.loadThemeMode(this)
            val useT9 = KeyboardPrefs.loadUseT9Layout(this)

            val themeText =
                if (themeMode == KeyboardPrefs.THEME_LIGHT) "明亮" else "暗黑"
            val layoutText =
                if (!useT9) "全键盘" else "九宫格"

            tvStatus.text = "当前：$layoutText + $themeText"
        }

        refreshStatus()

        btnQwerty.setOnClickListener {
            KeyboardPrefs.saveUseT9Layout(this, false)
            refreshStatus()
            Toast.makeText(this, "已切换为全键盘", Toast.LENGTH_SHORT).show()
        }

        btnT9.setOnClickListener {
            KeyboardPrefs.saveUseT9Layout(this, true)
            refreshStatus()
            Toast.makeText(this, "已切换为九宫格", Toast.LENGTH_SHORT).show()
        }

        btnLight.setOnClickListener {
            KeyboardPrefs.saveThemeMode(this, KeyboardPrefs.THEME_LIGHT)
            applyThemeMode()
            refreshStatus()
            recreate()
        }

        btnDark.setOnClickListener {
            KeyboardPrefs.saveThemeMode(this, KeyboardPrefs.THEME_DARK)
            applyThemeMode()
            refreshStatus()
            recreate()
        }
    }

    private fun applyThemeMode() {
        when (KeyboardPrefs.loadThemeMode(this)) {
            KeyboardPrefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            KeyboardPrefs.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }
}
