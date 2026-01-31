package com.example.myapp

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.myapp.ime.prefs.KeyboardPrefs

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 如果你要“全无下划线版”，请把布局文件改名为：
        // app/src/main/res/layout/activitymain.xml
        // 否则这里就必须继续用 R.layout.activity_main。 [file:10]
        setContentView(R.layout.activitymain) // [file:10]

        // 这些 id 目前在你给我的 activity_main.xml 里就是无下划线版：
        // btnlayoutqwerty, btnlayoutt9, btnthemelight, btnthemedark, tvstatus [file:10]
        val btnQwerty = findViewById<Button>(R.id.btnlayoutqwerty) // [file:10]
        val btnT9 = findViewById<Button>(R.id.btnlayoutt9) // [file:10]
        val btnLight = findViewById<Button>(R.id.btnthemelight) // [file:10]
        val btnDark = findViewById<Button>(R.id.btnthemedark) // [file:10]
        val tvStatus = findViewById<TextView>(R.id.tvstatus) // [file:10]

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

        // 布局切换
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

        // 皮肤切换
        btnLight.setOnClickListener {
            KeyboardPrefs.saveThemeMode(this, KeyboardPrefs.THEME_LIGHT)
            refreshStatus()
        }

        btnDark.setOnClickListener {
            KeyboardPrefs.saveThemeMode(this, KeyboardPrefs.THEME_DARK)
            refreshStatus()
        }
    }
}
