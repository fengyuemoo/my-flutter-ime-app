package com.example.myapp.ime.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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

/**
 * 供 KeyboardController 直接 import 的全局 FontApplier（不要放在 ImeUi class 内部）。
 */
object FontApplier {
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
    private val prefsName = "KeyboardPrefs"
    private val keyFontFamily = "font_family"
    private val keyFontScale = "font_scale"

    private var currentFontFamily: String = "sans-serif-light"
    private var currentFontScale: Float = 1.0f

    fun getFontConfig(): Pair<String, Float> = currentFontFamily to currentFontScale

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

        val families = listOf("sans-serif-light", "sans-serif", "serif", "monospace")
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
        scaleSpinner.setSelection(
            scales.indexOf(selectedScale).takeIf { it >= 0 } ?: scales.indexOf(1.0f).coerceAtLeast(0)
        )

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

        // 再设置一次资源兜底（即使 XML 已经 src 了也没坏处）
        btnToolFont.setImageResource(R.drawable.ic_tool_font)

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

        installRecyclerAutoFont(recyclerHorizontal)
        installRecyclerAutoFont(recyclerVertical)

        btnExpandedClose.setOnClickListener { btnExpand.performClick() }

        tvComposingPreview.text = ""
        tvComposingPreview.visibility = View.GONE

        btnToolFont.setOnClickListener { showFontPickerDialog() }

        setComposingPreview(null)
        showIdleState()

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
            spannable.setSpan(ForegroundColorSpan(Color.BLACK), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(0.8f), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.GRAY), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            spannable.setSpan(RelativeSizeSpan(0.8f), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.GRAY), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(1.2f), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.BLACK), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        tvComposingPreview.setBackgroundColor(if (themeMode == 1) panelDark else Color.parseColor("#F5F5F5"))
        tvComposingPreview.setTextColor(if (themeMode == 1) textDark else textLight)

        applyFont(currentFontFamily, currentFontScale)
    }
}
