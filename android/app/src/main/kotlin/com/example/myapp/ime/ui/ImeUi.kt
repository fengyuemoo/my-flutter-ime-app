package com.example.myapp.ime.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
    private var currentComposingPreviewText: String? = null
    private var onComposingPreviewChanged: ((String?) -> Unit)? = null

    private var currentCandidates: List<Candidate> = emptyList()
    private var selectedCandidateIndex: Int = 0

    fun getExpandButton(): ImageButton = btnExpand
    fun getFilterButton(): Button = btnFilter
    fun getThemeButton(): Button = rootView.findViewById(R.id.btntooltheme)
    fun getLayoutButton(): Button = rootView.findViewById(R.id.btntoollayout)
    fun getFontButton(): ImageButton = btnToolFont

    fun getSelectedCandidateIndex(): Int {
        return clampCandidateIndex(selectedCandidateIndex)
    }

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

    fun setComposingPreviewListener(listener: (String?) -> Unit) {
        onComposingPreviewChanged = listener
        listener(currentComposingPreviewText)
    }

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

        currentComposingPreviewText = null
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

    fun setComposingPreview(text: String?) {
        val normalized = text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (normalized == currentComposingPreviewText) return

        currentComposingPreviewText = normalized
        onComposingPreviewChanged?.invoke(normalized)
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
        onComposingPreviewChanged?.invoke(currentComposingPreviewText)
    }
}
