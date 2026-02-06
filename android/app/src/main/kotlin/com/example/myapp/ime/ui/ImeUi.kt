package com.example.myapp.ime.ui

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.CandidatePanelAdapter
import com.example.myapp.CandidateStripAdapter
import com.example.myapp.R
import com.example.myapp.dict.model.Candidate

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

        btnExpandedClose.setOnClickListener { btnExpand.performClick() }

        setComposingPreview(null)
        showIdleState()

        return rootView
    }

    fun getExpandButton(): ImageButton = btnExpand
    fun getFilterButton(): Button = btnFilter
    fun getThemeButton(): Button = rootView.findViewById(R.id.btntooltheme)
    fun getLayoutButton(): Button = rootView.findViewById(R.id.btntoollayout)

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
            // 关键：展开面板时，彻底移除 topBarFrame 占位，让 expanded panel 顶到最上面
            topBarFrame.visibility = View.GONE

            toolbarContainer.visibility = View.GONE
            candidateStrip.visibility = View.GONE
            // tvComposingPreview 不在 topBarFrame 里了，是 overlay 浮层；不需要在这里处理
        } else {
            topBarFrame.visibility = View.VISIBLE

            toolbarContainer.visibility = View.GONE
            candidateStrip.visibility = View.VISIBLE
        }
    }

    fun setComposingPreview(text: String?) {
        if (text.isNullOrEmpty()) {
            tvComposingPreview.text = ""
            tvComposingPreview.visibility = View.GONE
        } else {
            tvComposingPreview.visibility = View.VISIBLE
            tvComposingPreview.text = text
        }
    }

    fun setCandidates(list: List<Candidate>) {
        adapterHorizontal.submitList(list)
        adapterVertical.submitList(list)
        recyclerHorizontal.scrollToPosition(0)

        recyclerVertical.post { adapterVertical.notifyDataSetChanged() }
    }

    fun setExpanded(expanded: Boolean, isComposing: Boolean) {
        if (expanded) {
            // 关键：展开时 topBarFrame 直接 GONE，避免“候选面板被顶下去”
            topBarFrame.visibility = View.GONE

            btnExpand.animate().rotation(180f).setDuration(200).start()
            expandedPanel.visibility = View.VISIBLE

            recyclerVertical.post { adapterVertical.notifyDataSetChanged() }
        } else {
            topBarFrame.visibility = View.VISIBLE

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

        tvComposingPreview.setBackgroundColor(
            if (themeMode == 1) panelDark else Color.parseColor("#F5F5F5")
        )
        tvComposingPreview.setTextColor(if (themeMode == 1) textDark else textLight)
    }
}
