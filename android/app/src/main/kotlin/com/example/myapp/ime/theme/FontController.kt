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
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

class FontController(
    private val context: Context,
    private val uiProvider: () -> ImeUi?,
    private val keyboardControllerProvider: () -> KeyboardController?
) {
    var fontFamily: String = "sans-serif-light" // 现在既可能是 system family，也可能是 "asset:fonts/xxx.ttf"
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

    private fun isFontFile(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
    }

    private fun displayNameOf(spec: String): String {
        return if (spec.startsWith("asset:")) {
            spec.removePrefix("asset:").substringAfterLast('/')
        } else {
            spec
        }
    }

    fun showPickerDialog() {
        val ui = uiProvider()
        val ctx = ui?.rootView?.context ?: context
        val windowToken = ui?.rootView?.windowToken

        // 1) 内置 system families（保持原样）
        val builtinFamilies = listOf(
            "sans-serif-light",
            "sans-serif",
            "serif",
            "monospace"
        ).map { display -> display to display }

        // 2) 扫描 assets/fonts 下的字体文件（显示原文件名，存储为 asset:fonts/<文件名>）
        val assetFamilies: List<Pair<String, String>> = runCatching {
            val files = ctx.assets.list("fonts")?.toList().orEmpty()
            val collator = Collator.getInstance(Locale.getDefault())
            files.filter { isFontFile(it) }
                .sortedWith { a, b -> collator.compare(a, b) }
                .map { fileName -> fileName to "asset:fonts/$fileName" }
        }.getOrDefault(emptyList())

        // 最终可选项：displayName -> spec
        val allFamilies = builtinFamilies + assetFamilies
        val displayNames = allFamilies.map { it.first }.toTypedArray()
        val specs = allFamilies.map { it.second }

        val scales = listOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.3f)

        var selectedSpec = fontFamily
        var selectedScale = fontScale

        fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
        fun scaleLabel(s: Float): String = "${(s * 100f).roundToInt()}%"

        // cache for preview typeface
        val typefaceCache = HashMap<String, Typeface>()
        fun resolveTypeface(spec: String): Typeface {
            typefaceCache[spec]?.let { return it }
            val tf = runCatching {
                if (spec.startsWith("asset:")) {
                    val path = spec.removePrefix("asset:")
                    Typeface.createFromAsset(ctx.assets, path)
                } else {
                    Typeface.create(spec, Typeface.NORMAL)
                }
            }.getOrElse { Typeface.DEFAULT }
            typefaceCache[spec] = tf
            return tf
        }

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
            preview.typeface = resolveTypeface(selectedSpec)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * selectedScale)
        }

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

        // 字体行（显示：原文件名或 system family 名）
        root.addView(
            makeRow(title = "字体", initialValue = displayNameOf(selectedSpec)) { valueView ->
                val checked = specs.indexOf(selectedSpec).takeIf { it >= 0 } ?: 0
                showSingleChoiceDialog(
                    title = "选择字体",
                    items = displayNames,
                    checkedIndex = checked
                ) { idx ->
                    selectedSpec = specs[idx]
                    valueView.text = displayNameOf(selectedSpec)
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
                fontFamily = selectedSpec
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
