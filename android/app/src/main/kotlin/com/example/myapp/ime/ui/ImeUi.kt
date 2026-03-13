package com.example.myapp.ime.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.CandidatePanelAdapter
import com.example.myapp.CandidateStripAdapter
import com.example.myapp.R
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.cn.t9.PreeditDisplay
import com.example.myapp.ime.compose.cn.t9.PreeditSegment
import com.example.myapp.ime.prefs.KeyboardPrefs
import java.util.WeakHashMap

object FontApplier {
    private val baseTextSizePx = WeakHashMap<android.widget.TextView, Float>()
    private val typefaceCache = HashMap<String, Typeface>()

    private fun resolveTypeface(view: View, spec: String): Typeface {
        synchronized(typefaceCache) {
            typefaceCache[spec]?.let { return it }

            val tf = runCatching {
                if (spec.startsWith("asset:")) {
                    val path = spec.removePrefix("asset:")
                    Typeface.createFromAsset(view.context.assets, path)
                } else {
                    Typeface.create(spec, Typeface.NORMAL)
                }
            }.getOrElse {
                Typeface.DEFAULT
            }

            typefaceCache[spec] = tf
            return tf
        }
    }

    fun apply(root: View, fontFamily: String, scale: Float) {
        val s = scale.coerceIn(0.7f, 1.4f)
        walk(root) { tv ->
            val base = baseTextSizePx[tv] ?: tv.textSize.also { baseTextSizePx[tv] = it }
            tv.typeface = resolveTypeface(tv, fontFamily)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * s)
        }
    }

    private fun walk(v: View, onText: (android.widget.TextView) -> Unit) {
        if (v is android.widget.TextView) onText(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), onText)
            }
        }
    }
}

// ── Preedit 段样式渲染工具 ────────────────────────────────────────────
//
// R-P04 修复：将 PreeditDisplay 的各段按 Style 转为 SpannableString，
// 供有 Android TextView 直接渲染 preedit 的场景（如 Wear OS、辅助测试视图）使用。
// Flutter 主路径下 listener 直接拿到 PreeditDisplay，自行用 Flutter Widget 渲染样式。
//
// 颜色常量（Light 主题；Dark 主题下可由调用方覆盖）：
//   LOCKED   → 深蓝高亮色 + 粗体
//   FOCUSED  → 橙色 + 下划线 + 粗体
//   NORMAL   → 默认文字色（不施加 Span）
//   FALLBACK → 灰色斜体
//   COMMITTED_PREFIX → 绿色
//
object PreeditSpanBuilder {

    private val COLOR_LOCKED   = Color.parseColor("#1565C0")   // 深蓝
    private val COLOR_FOCUSED  = Color.parseColor("#E65100")   // 深橙
    private val COLOR_FALLBACK = Color.parseColor("#9E9E9E")   // 灰
    private val COLOR_PREFIX   = Color.parseColor("#2E7D32")   // 深绿

    /**
     * 将 PreeditDisplay 转为带 Span 样式的 SpannableString。
     * 音节段间自动插入 ' 分隔符（committedPrefix 直接连接，不加分隔符）。
     *
     * @param darkTheme 为 true 时使用适合深色背景的颜色变体
     */
    fun build(display: PreeditDisplay, darkTheme: Boolean = false): SpannableString {
        if (display.segments.isEmpty()) return SpannableString("")

        // ── 1. 拼接带分隔符的完整文本 ──────────────────────────────────
        //    committedPrefix 直接连接（无分隔符），其后拼音段间用 '
        val sb = StringBuilder()
        val spanRanges = mutableListOf<Triple<Int, Int, PreeditSegment.Style>>()

        var isFirstPinyin = true
        for (seg in display.segments) {
            val start = sb.length
            if (seg.style == PreeditSegment.Style.COMMITTED_PREFIX) {
                sb.append(seg.text)
            } else {
                if (!isFirstPinyin) sb.append("'")
                sb.append(seg.text)
                isFirstPinyin = false
            }
            val end = sb.length
            if (start < end) {
                spanRanges.add(Triple(start, end, seg.style))
            }
        }

        val spannable = SpannableString(sb.toString())

        // ── 2. 按 Style 施加 Span ──────────────────────────────────────
        for ((start, end, style) in spanRanges) {
            applyStyle(spannable, start, end, style, darkTheme)
        }

        return spannable
    }

    private fun applyStyle(
        spannable: SpannableString,
        start: Int,
        end: Int,
        style: PreeditSegment.Style,
        darkTheme: Boolean
    ) {
        val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        when (style) {
            PreeditSegment.Style.LOCKED -> {
                val color = if (darkTheme) Color.parseColor("#90CAF9") else COLOR_LOCKED
                spannable.setSpan(ForegroundColorSpan(color), start, end, flags)
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
            }
            PreeditSegment.Style.FOCUSED -> {
                val color = if (darkTheme) Color.parseColor("#FFCC80") else COLOR_FOCUSED
                spannable.setSpan(ForegroundColorSpan(color), start, end, flags)
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                spannable.setSpan(UnderlineSpan(), start, end, flags)
            }
            PreeditSegment.Style.FALLBACK -> {
                spannable.setSpan(ForegroundColorSpan(COLOR_FALLBACK), start, end, flags)
                spannable.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
            }
            PreeditSegment.Style.COMMITTED_PREFIX -> {
                val color = if (darkTheme) Color.parseColor("#A5D6A7") else COLOR_PREFIX
                spannable.setSpan(ForegroundColorSpan(color), start, end, flags)
            }
            PreeditSegment.Style.NORMAL -> {
                // 不施加额外 Span，使用 TextView 默认文字颜色
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────

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

    private lateinit var recyclerHorizontal: RecyclerView
    private lateinit var recyclerVertical: RecyclerView
    private lateinit var adapterHorizontal: CandidateStripAdapter
    private lateinit var adapterVertical: CandidatePanelAdapter

    private lateinit var btnToolFont: ImageButton

    private var currentFontFamily: String = "sans-serif-light"
    private var currentFontScale: Float = 1.0f

    private var expandedPanelFilterOverrideText: CharSequence? = null

    // ── R-P04：内部持有带样式的 PreeditDisplay ────────────────────────
    private var currentPreeditDisplay: PreeditDisplay? = null

    /** 纯文本快捷访问（向后兼容用） */
    private val currentComposingPreviewText: String?
        get() = currentPreeditDisplay?.plainText?.takeIf { it.isNotEmpty() }

    /**
     * 带样式的 Preedit 监听器。
     * - Flutter 主路径：宿主层通过 MethodChannel 拿到 plainText 传给编辑器，
     *   同时可解析 segments 用 Flutter Widget 渲染高亮。
     * - 纯原生路径：使用 PreeditSpanBuilder.build(display) 得到 SpannableString。
     *
     * 每次 preedit 有变化（含样式变化，即使 plainText 相同）均触发回调。
     */
    private var onPreeditDisplayChanged: ((PreeditDisplay?) -> Unit)? = null

    fun getExpandButton(): ImageButton = btnExpand
    fun getFilterButton(): Button = btnFilter
    fun getThemeButton(): Button = rootView.findViewById(R.id.btntooltheme)
    fun getLayoutButton(): Button = rootView.findViewById(R.id.btntoollayout)
    fun getFontButton(): ImageButton = btnToolFont

    fun getSelectedCandidateIndex(): Int {
        return clampCandidateIndex(selectedCandidateIndex)
    }

    private var selectedCandidateIndex: Int = 0
    private var currentCandidates: List<Candidate> = emptyList()

    fun setSelectedCandidateIndex(index: Int, scrollToItem: Boolean = true) {
        val clamped = clampCandidateIndex(index)
        selectedCandidateIndex = clamped

        if (this::adapterHorizontal.isInitialized) {
            adapterHorizontal.setSelectedIndex(clamped)
        }
        if (this::adapterVertical.isInitialized) {
            adapterVertical.setSelectedIndex(clamped)
        }

        if (scrollToItem && currentCandidates.isNotEmpty()) {
            scrollSelectionIntoView(clamped)
        }
    }

    fun resetSelectedCandidateIndex() {
        selectedCandidateIndex = 0
        if (this::adapterHorizontal.isInitialized) {
            adapterHorizontal.setSelectedIndex(0)
        }
        if (this::adapterVertical.isInitialized) {
            adapterVertical.setSelectedIndex(0)
        }
        if (this::recyclerHorizontal.isInitialized) {
            recyclerHorizontal.scrollToPosition(0)
        }
        if (this::recyclerVertical.isInitialized) {
            recyclerVertical.post { recyclerVertical.scrollToPosition(0) }
        }
    }

    fun moveSelectedCandidate(delta: Int): Int {
        if (currentCandidates.isEmpty()) {
            selectedCandidateIndex = 0
            return 0
        }
        val next = clampCandidateIndex(selectedCandidateIndex + delta)
        setSelectedCandidateIndex(next)
        return selectedCandidateIndex
    }

    private fun clampCandidateIndex(index: Int): Int {
        if (currentCandidates.isEmpty()) return 0
        return index.coerceIn(0, currentCandidates.lastIndex)
    }

    private fun scrollSelectionIntoView(index: Int) {
        if (!this::recyclerHorizontal.isInitialized || !this::recyclerVertical.isInitialized) return
        if (currentCandidates.isEmpty()) return
        if (index !in currentCandidates.indices) return

        recyclerHorizontal.smoothScrollToPosition(index)
        recyclerVertical.post {
            recyclerVertical.scrollToPosition(index)
        }
    }

    private fun installHorizontalSelectionSync() {
        recyclerHorizontal.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                if (currentCandidates.isEmpty()) return

                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return

                val firstCompletelyVisible = lm.findFirstCompletelyVisibleItemPosition()
                val firstVisible = lm.findFirstVisibleItemPosition()

                val target = when {
                    firstCompletelyVisible != RecyclerView.NO_POSITION -> firstCompletelyVisible
                    firstVisible != RecyclerView.NO_POSITION -> firstVisible
                    else -> return
                }

                if (target != getSelectedCandidateIndex()) {
                    setSelectedCandidateIndex(target, scrollToItem = false)
                }
            }
        })
    }

    fun applyFont(fontFamily: String, fontScale: Float) {
        currentFontFamily = fontFamily
        currentFontScale = fontScale.coerceIn(0.7f, 1.4f)
        FontApplier.apply(rootView, currentFontFamily, currentFontScale)
    }

    fun applySavedFontNow() {
        currentFontFamily = KeyboardPrefs.loadFontFamily(rootView.context)
        currentFontScale = KeyboardPrefs.loadFontScale(rootView.context)
        applyFont(currentFontFamily, currentFontScale)
    }

    private fun installRecyclerAutoFont(rv: RecyclerView) {
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                FontApplier.apply(view, currentFontFamily, currentFontScale)
            }

            override fun onChildViewDetachedFromWindow(view: View) {
            }
        })
    }

    fun setExpandedPanelFilterOverride(text: CharSequence?) {
        expandedPanelFilterOverrideText = text
        if (text != null) {
            btnFilter.text = text
            FontApplier.apply(btnFilter, currentFontFamily, currentFontScale)
        }
    }

    // ── Preedit 监听器注册 ─────────────────────────────────────────────

    /**
     * 注册 Preedit 带样式变化监听（取代旧的纯文本 setComposingPreviewListener）。
     *
     * 立即触发一次当前值的回调（与旧 API 行为一致）。
     *
     * @param listener 参数为 null 表示 Idle（无拼写中状态）；
     *                 非 null 时可通过 display.plainText 获取纯文本，
     *                 通过 display.segments 获取各段样式信息。
     */
    fun setPreeditDisplayListener(listener: (PreeditDisplay?) -> Unit) {
        onPreeditDisplayChanged = listener
        listener(currentPreeditDisplay)
    }

    /**
     * 向后兼容：旧代码调用 setComposingPreviewListener(String?) 时，
     * 内部包装为仅接收 plainText 的简化监听。
     *
     * 新代码应调用 setPreeditDisplayListener。
     */
    fun setComposingPreviewListener(listener: (String?) -> Unit) {
        setPreeditDisplayListener { display ->
            listener(display?.plainText?.takeIf { it.isNotEmpty() })
        }
    }

    // ── Preedit 更新入口 ───────────────────────────────────────────────

    /**
     * R-P04 主路径：接受带段样式的 PreeditDisplay，通知监听器。
     *
     * 对比逻辑：plainText 相同但 segments（样式）变化时也会触发回调，
     * 以确保焦点/锁定段高亮能实时更新。
     *
     * 由 ImeActionDispatcher.syncResolvedComposingToUiAndEditor() 调用。
     */
    fun setComposingPreviewDisplay(display: PreeditDisplay) {
        val normalized = if (display.plainText.trim().isEmpty()) null else display

        // 仅在内容变化时触发（含段样式变化）
        if (normalized == currentPreeditDisplay) return

        currentPreeditDisplay = normalized
        onPreeditDisplayChanged?.invoke(normalized)
    }

    /**
     * 兼容旧调用方：接受纯文本，包装为无样式的 NORMAL 段 PreeditDisplay。
     *
     * 由旧路径（transient preview / 英文模式 / 单元测试）调用。
     * 新代码应直接调用 setComposingPreviewDisplay(PreeditDisplay)。
     */
    fun setComposingPreview(text: String?) {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            setComposingPreviewDisplay(PreeditDisplay.EMPTY)
            return
        }
        val display = PreeditDisplay(
            plainText  = normalized,
            segments   = listOf(PreeditSegment(normalized, PreeditSegment.Style.NORMAL)),
            isFallback = false
        )
        setComposingPreviewDisplay(display)
    }

    // ─────────────────────────────────────────────────────────────────

    fun inflate(
        inflater: LayoutInflater,
        onCandidateClick: (Candidate) -> Unit
    ): View {
        return inflateInternal(
            inflater = inflater,
            onCandidateClick = onCandidateClick,
            onCandidateIndexClick = null
        )
    }

    fun inflateWithIndex(
        inflater: LayoutInflater,
        onCandidateIndexClick: (Int) -> Unit
    ): View {
        return inflateInternal(
            inflater = inflater,
            onCandidateClick = null,
            onCandidateIndexClick = onCandidateIndexClick
        )
    }

    private fun inflateInternal(
        inflater: LayoutInflater,
        onCandidateClick: ((Candidate) -> Unit)?,
        onCandidateIndexClick: ((Int) -> Unit)?
    ): View {
        rootView = inflater.inflate(R.layout.imecontainer, null)

        topBarFrame = rootView.findViewById(R.id.topbarframe)
        bodyFrame = rootView.findViewById(R.id.keyboardbodyframe)
        toolbarContainer = rootView.findViewById(R.id.toolbarcontainer)
        candidateStrip = rootView.findViewById(R.id.candidatestrip)
        expandedPanel = rootView.findViewById(R.id.expandedcandidatespanel)
        btnExpand = rootView.findViewById(R.id.btnexpandcandidates)
        btnExpandedClose = rootView.findViewById(R.id.expandpanelclose)
        btnFilter = rootView.findViewById(R.id.expandpanelfilter)

        btnToolFont = rootView.findViewById(R.id.btntoolfont)
        btnToolFont.setImageResource(R.drawable.ic_tool_font)

        recyclerHorizontal = rootView.findViewById(R.id.recyclercandidateshorizontal)
        recyclerVertical = rootView.findViewById(R.id.recyclercandidatesvertical)

        adapterHorizontal = CandidateStripAdapter { index ->
            setSelectedCandidateIndex(index)
            onCandidateIndexClick?.invoke(index) ?: run {
                val c = adapterHorizontal.getItem(index) ?: return@CandidateStripAdapter
                onCandidateClick?.invoke(c)
            }
        }

        adapterVertical = CandidatePanelAdapter { index ->
            setSelectedCandidateIndex(index)
            onCandidateIndexClick?.invoke(index) ?: run {
                val c = adapterVertical.getItem(index) ?: return@CandidatePanelAdapter
                onCandidateClick?.invoke(c)
            }
        }

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

        installHorizontalSelectionSync()

        installRecyclerAutoFont(recyclerHorizontal)
        installRecyclerAutoFont(recyclerVertical)

        btnExpandedClose.setOnClickListener { btnExpand.performClick() }

        currentPreeditDisplay = null
        setComposingPreview(null)
        showIdleState()

        applySavedFontNow()
        return rootView
    }

    private fun clearComposingUiState() {
        expandedPanel.visibility = View.GONE
        candidateStrip.visibility = View.GONE
        setCandidates(emptyList())
        resetSelectedCandidateIndex()
        setComposingPreview(null)
    }

    fun showIdleState() {
        topBarFrame.visibility = View.VISIBLE
        toolbarContainer.visibility = View.VISIBLE
        clearComposingUiState()
    }

    fun showComposingState(isExpanded: Boolean) {
        if (isExpanded) {
            toolbarContainer.visibility = View.GONE
            candidateStrip.visibility = View.GONE
            expandedPanel.visibility = View.VISIBLE
        } else {
            toolbarContainer.visibility = View.GONE
            candidateStrip.visibility = View.VISIBLE
            expandedPanel.visibility = View.GONE
        }
    }

    fun setCandidates(list: List<Candidate>) {
        currentCandidates = list.toList()

        adapterHorizontal.submitList(currentCandidates)
        adapterVertical.submitList(currentCandidates)

        selectedCandidateIndex = clampCandidateIndex(selectedCandidateIndex)
        adapterHorizontal.setSelectedIndex(selectedCandidateIndex)
        adapterVertical.setSelectedIndex(selectedCandidateIndex)

        if (currentCandidates.isEmpty()) {
            recyclerHorizontal.scrollToPosition(0)
        } else {
            scrollSelectionIntoView(selectedCandidateIndex)
        }

        recyclerVertical.post { adapterVertical.notifyDataSetChanged() }
    }

    fun setExpanded(expanded: Boolean, isComposing: Boolean) {
        if (expanded) {
            btnExpand.animate().rotation(180f).setDuration(200).start()
            expandedPanel.visibility = View.VISIBLE
            recyclerVertical.post {
                adapterVertical.notifyDataSetChanged()
                if (currentCandidates.isNotEmpty()) {
                    recyclerVertical.scrollToPosition(getSelectedCandidateIndex())
                }
            }
        } else {
            btnExpand.animate().rotation(0f).setDuration(200).start()
            expandedPanel.visibility = View.GONE

            if (isComposing) {
                candidateStrip.visibility = View.VISIBLE
                toolbarContainer.visibility = View.GONE
                if (currentCandidates.isNotEmpty()) {
                    recyclerHorizontal.post {
                        recyclerHorizontal.smoothScrollToPosition(getSelectedCandidateIndex())
                    }
                }
            } else {
                toolbarContainer.visibility = View.VISIBLE
                clearComposingUiState()
            }
        }
    }

    fun setFilterButton(singleCharMode: Boolean) {
        val override = expandedPanelFilterOverrideText
        if (override != null) {
            btnFilter.text = override
            FontApplier.apply(btnFilter, currentFontFamily, currentFontScale)
            return
        }

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

        rootView.setBackgroundColor(if (themeMode == 1) bgDark else bgLight)
        expandedPanel.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)
        toolbarContainer.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)
        candidateStrip.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)

        applyFont(currentFontFamily, currentFontScale)
        // R-P04：主题切换后重推当前 preedit（使颜色 Span 跟随主题更新）
        onPreeditDisplayChanged?.invoke(currentPreeditDisplay)
    }
}
