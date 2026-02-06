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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.CandidatePanelAdapter
import com.example.myapp.CandidateStripAdapter
import com.example.myapp.R
import com.example.myapp.dict.model.Candidate
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent

class ImeUi {

    lateinit var rootView: View
        private set

    lateinit var bodyFrame: FrameLayout
        private set

    private lateinit var toolbarContainer: LinearLayout
    private lateinit var candidateStrip: LinearLayout
    private lateinit var expandedPanel: LinearLayout
    private lateinit var btnExpand: ImageButton
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

        bodyFrame = rootView.findViewById(R.id.keyboardbodyframe)
        toolbarContainer = rootView.findViewById(R.id.toolbarcontainer)
        candidateStrip = rootView.findViewById(R.id.candidatestrip)
        expandedPanel = rootView.findViewById(R.id.expandedcandidatespanel)
        btnExpand = rootView.findViewById(R.id.btnexpandcandidates)
        tvComposingPreview = rootView.findViewById(R.id.tvcomposingpreview)
        btnFilter = rootView.findViewById(R.id.expandpanelfilter)

        recyclerHorizontal = rootView.findViewById(R.id.recyclercandidateshorizontal)
        recyclerVertical = rootView.findViewById(R.id.recyclercandidatesvertical)

        adapterHorizontal = CandidateStripAdapter { onCandidateClick(it) }
        adapterVertical = CandidatePanelAdapter { onCandidateClick(it) }

        recyclerHorizontal.layoutManager =
            LinearLayoutManager(rootView.context, LinearLayoutManager.HORIZONTAL, false)

        // 展开面板：从 Grid 改为 Flexbox，实现“连续延展，满行再换行”
        recyclerVertical.layoutManager = FlexboxLayoutManager(rootView.context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.CENTER
        }

        recyclerHorizontal.adapter = adapterHorizontal
        recyclerVertical.adapter = adapterVertical

        // 初次布局完成后同步一次可用宽度，避免 adapter 内计算时 width=0
        recyclerVertical.post {
            val w = recyclerVertical.width - recyclerVertical.paddingLeft - recyclerVertical.paddingRight
            adapterVertical.setAvailableWidthPx(w)
            adapterVertical.notifyDataSetChanged()
        }

        setComposingPreview(null)
        showIdleState()

        return rootView
    }

    fun getExpandButton(): ImageButton = btnExpand
    fun getFilterButton(): Button = btnFilter
    fun getThemeButton(): Button = rootView.findViewById(R.id.btntooltheme)
    fun getLayoutButton(): Button = rootView.findViewById(R.id.btntoollayout)

    fun showIdleState() {
        toolbarContainer.visibility = View.VISIBLE
        candidateStrip.visibility = View.GONE
        setExpanded(false, isComposing = false)
        setCandidates(emptyList())
    }

    fun showComposingState(isExpanded: Boolean) {
        if (!isExpanded) {
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

        // 候选变化/面板尺寸变化时，也更新可用宽度（更稳）
        recyclerVertical.post {
            val w = recyclerVertical.width - recyclerVertical.paddingLeft - recyclerVertical.paddingRight
            adapterVertical.setAvailableWidthPx(w)
        }
    }

    fun setExpanded(expanded: Boolean, isComposing: Boolean) {
        if (expanded) {
            btnExpand.animate().rotation(180f).setDuration(200).start()
            expandedPanel.visibility = View.VISIBLE

            // 展开后再刷新一次，确保“超长候选占满整行 + 多行显示”立即生效
            recyclerVertical.post {
                val w = recyclerVertical.width - recyclerVertical.paddingLeft - recyclerVertical.paddingRight
                adapterVertical.setAvailableWidthPx(w)
                adapterVertical.notifyDataSetChanged()
            }
        } else {
            btnExpand.animate().rotation(0f).setDuration(200).start()
            expandedPanel.visibility = View.GONE
            if (isComposing) {
                candidateStrip.visibility = View.VISIBLE
                toolbarContainer.visibility = View.GONE
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
