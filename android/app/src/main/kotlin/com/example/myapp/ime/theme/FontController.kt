package com.example.myapp.ime.theme

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.prefs.KeyboardPrefs
import com.example.myapp.ime.ui.ImeUi
import kotlin.math.roundToInt

class FontController(
    private val context: Context,
    private val uiProvider: () -> ImeUi?,
    private val keyboardControllerProvider: () -> KeyboardController?
) {
    var fontFamily: String = "sans-serif-light"
        private set

    var fontScale: Float = 1.0f
        private set

    fun load() {
        fontFamily = KeyboardPrefs.loadFontFamily(context)
        fontScale = KeyboardPrefs.loadFontScale(context)
    }

    fun save() {
        KeyboardPrefs.saveFontFamily(context, fontFamily)
        KeyboardPrefs.saveFontScale(context, fontScale)
    }

    fun apply() {
        uiProvider()?.applyFont(fontFamily, fontScale)
        keyboardControllerProvider()?.applyFont(fontFamily, fontScale)
    }

    fun showPickerDialog() {
        val families = listOf(
            "sans-serif-light",
            "sans-serif",
            "serif",
            "monospace"
        )
        val scales = listOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.3f)

        var selectedFamily = fontFamily
        var selectedScale = fontScale

        fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(10))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val preview = TextView(context).apply {
            text = "Aa 字 符号 123"
            gravity = Gravity.CENTER
            setPadding(0, context.dp(8), 0, context.dp(12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        val fontSpinner = Spinner(context)
        val scaleSpinner = Spinner(context)

        fun scaleLabel(s: Float): String = "${(s * 100f).roundToInt()}%"

        fontSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, families)
        scaleSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, scales.map { scaleLabel(it) })

        fun applyPreview() {
            preview.typeface = Typeface.create(selectedFamily, Typeface.NORMAL)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * selectedScale)
        }

        fontSpinner.setSelection(families.indexOf(selectedFamily).coerceAtLeast(0))
        scaleSpinner.setSelection(scales.indexOf(selectedScale).takeIf { it >= 0 } ?: scales.indexOf(1.0f).coerceAtLeast(0))

        fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                selectedFamily = families[position]
                applyPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        scaleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                selectedScale = scales[position]
                applyPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        root.addView(preview)
        root.addView(TextView(context).apply { text = "字体" })
        root.addView(fontSpinner)
        root.addView(TextView(context).apply {
            text = "字号缩放"
            setPadding(0, context.dp(10), 0, 0)
        })
        root.addView(scaleSpinner)

        applyPreview()

        AlertDialog.Builder(context)
            .setTitle("字体与字号")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                fontFamily = selectedFamily
                fontScale = selectedScale.coerceIn(0.7f, 1.4f)
                save()
                apply()
            }
            .show()
    }
}
