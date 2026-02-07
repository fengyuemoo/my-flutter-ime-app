package com.example.myapp.ime.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.CandidatePanelAdapter
import com.example.myapp.CandidateStripAdapter
import com.example.myapp.R
import com.example.myapp.dict.model.Candidate
import java.util.WeakHashMap
import kotlin.math.roundToInt

class ImeUi {

    lateinit var rootView: View
        private set

    lateinit var bodyFrame: FrameLayout
        private set

    private lateinit var topBarFrame: View
    private lateinit var toolbarContainer: LinearLayout
    private lateinit var candidateStrip: LinearLayout
    private lateinit var expandedPanel: LinearLayout
    private lateinit var btnExpand: ImageButton
    private lateinit var btnExpandedClose: ImageButton
    private lateinit var btnFilter: Button
    private lateinit var tvComposingPreview: TextView

    private lateinit var recyclerHorizontal: RecyclerView
    private lateinit var recyclerVertical: RecyclerView
    private lateinit var adapterHorizontal: CandidateStripAdapter
    private lateinit var adapterVertical: CandidatePanelAdapter

    private lateinit var btnToolFont: ImageButton

    // NEW: 将预览文本交给 service 去“浮层显示”
    private var composingPreviewListener: ((String?) -> Unit)? = null

    fun setComposingPreviewListener(listener: ((String?) -> Unit)?) {
        composingPreviewListener = listener
    }

    fun getExpandButton(): ImageButton = btnExpand
    fun getFilterButton(): Button = btnFilter
    fun getThemeButton(): Button = rootView.findViewById(R.id.btntooltheme)
    fun getLayoutButton(): Button = rootView.findViewById(R.id.btntoollayout)
    fun getFontButton(): ImageButton = btnToolFont

    // ===== Font prefs & state =====
    // 这里直接读写 SharedPreferences（与你的 KeyboardPrefs.PREFS_NAME 同名即可）
    private val prefsName = "KeyboardPrefs"
    private val keyFontFamily = "font_family"
    private val keyFontScale = "font_scale"

    private var currentFontFamily: String = "sans-serif-light"
    private var currentFontScale: Float = 1.0f

    private object FontApplier {
        private val baseTextSizePx = WeakHashMap<TextView, Float>()

        fun apply(root: View, fontFamily: String, scale: Float) {
            val s = scale.coerceIn(0.7f, 1.4f)
            walk(root) { tv ->
                val base = baseTextSizePx[tv] ?: tv.textSize.also { baseTextSizePx[tv] = it }
                tv.typeface = Typeface.create(fontFamily, Typeface.NORMAL)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * s)
            }
        }

        private fun walk(v: View, onText: (TextView) -> Unit) {
            if (v is TextView) onText(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i), onText)
                }
            }
        }
    }

    private fun loadFontPrefs(context: Context) {
        val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        currentFontFamily = sp.getString(keyFontFamily, "sans-serif-light") ?: "sans-serif-light"
        currentFontScale = sp.getFloat(keyFontScale, 1.0f).coerceIn(0.7f, 1.4f)
    }

    private fun saveFontPrefs(context: Context) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyFontFamily, currentFontFamily)
            .putFloat(keyFontScale, currentFontScale)
            .apply()
    }

    fun applyFont(fontFamily: String, fontScale: Float) {
        currentFontFamily = fontFamily
        currentFontScale = fontScale.coerceIn(0.7f, 1.4f)
        FontApplier.apply(rootView, currentFontFamily, currentFontScale)
    }

    fun applySavedFontNow() {
        loadFontPrefs(rootView.context)
        applyFont(currentFontFamily, currentFontScale)
    }

    private fun installRecyclerAutoFont(rv: RecyclerView) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                FontApplier.apply(view, currentFontFamily, currentFontScale)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                // no-op
            }
        })
    }

    private fun showFontPickerDialog() {
        val ctx = rootView.context

        val families = listOf(
            "sans-serif-light",
            "sans-serif",
            "serif",
            "monospace"
        )
        val scales = listOf(0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.3f)

        var selectedFamily = currentFontFamily
        var selectedScale = currentFontScale

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
            setPadding(0, ctx.dp(10), 0, ctx.dp(12))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        val fontSpinner = Spinner(ctx)
        val scaleSpinner = Spinner(ctx)

        fontSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, families)
        scaleSpinner.adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_dropdown_item,
            scales.map { scaleLabel(it) }
        )

        fun applyPreview() {
            preview.typeface = Typeface.create(selectedFamily, Typeface.NORMAL)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * selectedScale)
        }

        fontSpinner.setSelection(families.indexOf(selectedFamily).coerceAtLeast(0))
        scaleSpinner.setSelection(scales.indexOf(selectedScale).takeIf { it >= 0 } ?: scales.indexOf(1.0f).coerceAtLeast(0))

        fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedFamily = families[position]
                applyPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        scaleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedScale = scales[position]
                applyPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        root.addView(preview)
        root.addView(TextView(ctx).apply { text = "字体" })
        root.addView(fontSpinner)
        root.addView(TextView(ctx).apply {
            text = "字号缩放"
            setPadding(0, ctx.dp(10), 0, 0)
        })
        root.addView(scaleSpinner)

        applyPreview()

        AlertDialog.Builder(ctx)
            .setTitle("字体与字号")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                currentFontFamily = selectedFamily
                currentFontScale = selectedScale.coerceIn(0.7f, 1.4f)
                saveFontPrefs(ctx)
                applyFont(currentFontFamily, currentFontScale)
            }
            .show()
    }

    // === 你的图标：已补全 base64（96x96 PNG）===
    private val fontToolIconBase64Png: String =
        "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"

    private fun tryApplyFontToolIcon() {
        try {
            val bytes = Base64.decode(fontToolIconBase64Png, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            btnToolFont.setImageBitmap(bmp)
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun inflate(
        inflater: LayoutInflater,
        onCandidateClick: (Candidate) -> Unit
    ): View {
        rootView = inflater.inflate(R.layout.imecontainer, null)

        topBarFrame = rootView.findViewById(R.id.topbarframe)
        bodyFrame = rootView.findViewById(R.id.keyboardbodyframe)
        toolbarContainer = rootView.findViewById(R.id.toolbarcontainer)
        candidateStrip = rootView.findViewById(R.id.candidatestrip)
        expandedPanel = rootView.findViewById(R.id.expandedcandidatespanel)
        btnExpand = rootView.findViewById(R.id.btnexpandcandidates)
        btnExpandedClose = rootView.findViewById(R.id.expandpanelclose)
        tvComposingPreview = rootView.findViewById(R.id.tvcomposingpreview)
        btnFilter = rootView.findViewById(R.id.expandpanelfilter)
        btnToolFont = rootView.findViewById(R.id.btntoolfont)

        recyclerHorizontal = rootView.findViewById(R.id.recyclercandidateshorizontal)
        recyclerVertical = rootView.findViewById(R.id.recyclercandidatesvertical)

        adapterHorizontal = CandidateStripAdapter { onCandidateClick(it) }
        adapterVertical = CandidatePanelAdapter { onCandidateClick(it) }

        recyclerHorizontal.layoutManager =
            LinearLayoutManager(rootView.context, LinearLayoutManager.HORIZONTAL, false)

        val spanCount = 4
        val gridLm = GridLayoutManager(rootView.context, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val totalWidthPx =
                        recyclerVertical.width - recyclerVertical.paddingLeft - recyclerVertical.paddingRight
                    return adapterVertical.getSpanSize(
                        position = position,
                        spanCount = spanCount,
                        totalWidthPx = totalWidthPx,
                        context = recyclerVertical.context
                    )
                }
            }
        }
        recyclerVertical.layoutManager = gridLm

        recyclerHorizontal.adapter = adapterHorizontal
        recyclerVertical.adapter = adapterVertical

        // NEW: 候选 item 动态 attach：每次 attach 都重新应用字体/字号
        installRecyclerAutoFont(recyclerHorizontal)
        installRecyclerAutoFont(recyclerVertical)

        btnExpandedClose.setOnClickListener { btnExpand.performClick() }

        // IMPORTANT：不在 inputView 内显示预览，避免遮挡首候选
        tvComposingPreview.text = ""
        tvComposingPreview.visibility = View.GONE

        // NEW: 字体/字号按钮（弹窗 + 图标）
        btnToolFont.setOnClickListener { showFontPickerDialog() }
        tryApplyFontToolIcon()

        setComposingPreview(null)
        showIdleState()

        // 启动时先加载并应用保存的字体/字号
        applySavedFontNow()

        return rootView
    }

    fun showIdleState() {
        topBarFrame.visibility = View.VISIBLE
        toolbarContainer.visibility = View.VISIBLE
        candidateStrip.visibility = View.GONE
        expandedPanel.visibility = View.GONE
        setCandidates(emptyList())
        setComposingPreview(null)
    }

    fun showComposingState(isExpanded: Boolean) {
        if (isExpanded) {
            toolbarContainer.visibility = View.GONE
            candidateStrip.visibility = View.GONE
        } else {
            toolbarContainer.visibility = View.GONE
            candidateStrip.visibility = View.VISIBLE
        }
    }

    fun setComposingPreview(text: String?) {
        tvComposingPreview.text = ""
        tvComposingPreview.visibility = View.GONE
        composingPreviewListener?.invoke(text)
    }

    fun setCandidates(list: List<Candidate>) {
        adapterHorizontal.submitList(list)
        adapterVertical.submitList(list)
        recyclerHorizontal.scrollToPosition(0)
        recyclerVertical.post { adapterVertical.notifyDataSetChanged() }
    }

    fun setExpanded(expanded: Boolean, isComposing: Boolean) {
        if (expanded) {
            btnExpand.animate().rotation(180f).setDuration(200).start()
            expandedPanel.visibility = View.VISIBLE
            recyclerVertical.post { adapterVertical.notifyDataSetChanged() }
        } else {
            btnExpand.animate().rotation(0f).setDuration(200).start()
            expandedPanel.visibility = View.GONE

            if (isComposing) {
                candidateStrip.visibility = View.VISIBLE
                toolbarContainer.visibility = View.GONE
            } else {
                toolbarContainer.visibility = View.VISIBLE
                candidateStrip.visibility = View.GONE
            }
        }
    }

    fun setFilterButton(singleCharMode: Boolean) {
        val text = "全部/单字"
        val spannable = SpannableString(text)

        if (!singleCharMode) {
            spannable.setSpan(RelativeSizeSpan(1.2f), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(
                ForegroundColorSpan(Color.BLACK),
                0,
                2,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(RelativeSizeSpan(0.8f), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(
                ForegroundColorSpan(Color.GRAY),
                3,
                5,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            spannable.setSpan(RelativeSizeSpan(0.8f), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(
                ForegroundColorSpan(Color.GRAY),
                0,
                2,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(RelativeSizeSpan(1.2f), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(
                ForegroundColorSpan(Color.BLACK),
                3,
                5,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        btnFilter.text = spannable
        FontApplier.apply(btnFilter, currentFontFamily, currentFontScale)
    }

    fun setThemeMode(themeMode: Int) {
        adapterHorizontal.themeMode = themeMode
        adapterVertical.themeMode = themeMode
        adapterHorizontal.notifyDataSetChanged()
        adapterVertical.notifyDataSetChanged()
    }

    fun applyTheme(themeMode: Int) {
        val bgLight = Color.parseColor("#DDDDDD")
        val bgDark = Color.parseColor("#222222")
        val panelLight = Color.parseColor("#EEEEEE")
        val panelDark = Color.parseColor("#333333")
        val textLight = Color.BLACK
        val textDark = Color.WHITE

        rootView.setBackgroundColor(if (themeMode == 1) bgDark else bgLight)
        expandedPanel.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)
        toolbarContainer.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)
        candidateStrip.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)

        tvComposingPreview.visibility = View.GONE
        tvComposingPreview.setBackgroundColor(
            if (themeMode == 1) panelDark else Color.parseColor("#F5F5F5")
        )
        tvComposingPreview.setTextColor(if (themeMode == 1) textDark else textLight)

        // 主题切换后再应用一次字体/字号（避免被某些 view 重置）
        applyFont(currentFontFamily, currentFontScale)
    }
}
