package com.example.myapp.ime.theme

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
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

    private fun attachToImeWindow(dialog: AlertDialog, token: IBinder?) {
        if (token == null) return
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        dialog.window?.attributes = dialog.window?.attributes?.apply { this.token = token }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    fun showPickerDialog() {
        val ui = uiProvider()
        val ctx = ui?.rootView?.context ?: context
        val windowToken = ui?.rootView?.windowToken

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
        fun scaleLabel(s: Float): String = "${(s * 100f).roundToInt()}%"

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(10))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val preview = TextView(ctx).apply {
            text = "Aa 字 符号 123"
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(8), 0, ctx.dp(12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        fun applyPreview() {
            preview.typeface = Typeface.create(selectedFamily, Typeface.NORMAL)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * selectedScale)
        }

        // 可点击行（代替 Spinner）
        fun makeRow(title: String, initialValue: String, onClick: (valueView: TextView) -> Unit): View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            val tvTitle = TextView(ctx).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }

            val tvValue = TextView(ctx).apply {
                text = initialValue
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.END
                setPadding(ctx.dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            row.addView(tvTitle)
            row.addView(tvValue)

            row.setOnClickListener { onClick(tvValue) }
            return row
        }

        fun showSingleChoiceDialog(
            title: String,
            items: Array<String>,
            checkedIndex: Int,
            onPicked: (index: Int) -> Unit
        ) {
            val d = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                    onPicked(which)
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .create()

            attachToImeWindow(d, windowToken)

            try {
                d.show()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        root.addView(preview)

        // 字体行
        root.addView(
            makeRow(title = "字体", initialValue = selectedFamily) { valueView ->
                val items = families.toTypedArray()
                val checked = families.indexOf(selectedFamily).coerceAtLeast(0)
                showSingleChoiceDialog(
                    title = "选择字体",
                    items = items,
                    checkedIndex = checked
                ) { idx ->
                    selectedFamily = families[idx]
                    valueView.text = selectedFamily
                    applyPreview()
                }
            }
        )

        // 字号行
        root.addView(
            makeRow(title = "字号缩放", initialValue = scaleLabel(selectedScale)) { valueView ->
                val labels = scales.map { scaleLabel(it) }.toTypedArray()
                val checked = scales.indexOf(selectedScale).takeIf { it >= 0 } ?: scales.indexOf(1.0f).coerceAtLeast(0)
                showSingleChoiceDialog(
                    title = "选择字号缩放",
                    items = labels,
                    checkedIndex = checked
                ) { idx ->
                    selectedScale = scales[idx]
                    valueView.text = scaleLabel(selectedScale)
                    applyPreview()
                }
            }
        )

        applyPreview()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("字体与字号")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                fontFamily = selectedFamily
                fontScale = selectedScale.coerceIn(0.7f, 1.4f)
                save()
                apply()
            }
            .create()

        attachToImeWindow(dialog, windowToken)

        try {
            dialog.show()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
