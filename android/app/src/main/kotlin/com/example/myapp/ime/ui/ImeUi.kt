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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.CandidateAdapter
import com.example.myapp.R
import com.example.myapp.dict.model.Candidate

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

    private lateinit var btnToolLayout: Button
    private lateinit var btnToolTheme: Button

    private lateinit var recyclerHorizontal: RecyclerView
    private lateinit var recyclerVertical: RecyclerView
    private lateinit var adapterHorizontal: CandidateAdapter
    private lateinit var adapterVertical: CandidateAdapter

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

        btnToolLayout = rootView.findViewById(R.id.btntoollayout)
        btnToolTheme = rootView.findViewById(R.id.btntooltheme)

        recyclerHorizontal = rootView.findViewById(R.id.recyclercandidateshorizontal)
        recyclerVertical = rootView.findViewById(R.id.recyclercandidatesvertical)

        recyclerHorizontal.layoutManager =
            LinearLayoutManager(rootView.context, LinearLayoutManager.HORIZONTAL, false)
        recyclerVertical.layoutManager = GridLayoutManager(rootView.context, 4)

        adapterHorizontal = CandidateAdapter(isGrid = false) { onCandidateClick(it) }
        adapterVertical = CandidateAdapter(isGrid = true) { onCandidateClick(it) }

        recyclerHorizontal.adapter = adapterHorizontal
        recyclerVertical.adapter = adapterVertical

        setComposingPreview(null)
        showIdleState()

        return rootView
    }

    fun getExpandButton(): ImageButton = btnExpand
    fun getFilterButton(): Button = btnFilter
    fun getThemeButton(): Button = btnToolTheme
    fun getLayoutButton(): Button = btnToolLayout

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
    }

    fun setExpanded(expanded: Boolean, isComposing: Boolean) {
        if (expanded) {
            btnExpand.animate().rotation(180f).setDuration(200).start()
            expandedPanel.visibility = View.VISIBLE
            adapterVertical.notifyDataSetChanged()
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

        val activeColor = ContextCompat.getColor(rootView.context, R.color.ime_accent)
        val inactiveColor = ContextCompat.getColor(rootView.context, R.color.ime_hint_text)

        if (!singleCharMode) {
            spannable.setSpan(RelativeSizeSpan(1.15f), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(activeColor), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            spannable.setSpan(RelativeSizeSpan(0.85f), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(inactiveColor), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            spannable.setSpan(RelativeSizeSpan(0.85f), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(inactiveColor), 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            spannable.setSpan(RelativeSizeSpan(1.15f), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(activeColor), 3, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        val bg = ContextCompat.getColor(rootView.context, R.color.ime_panel_bg)
        val surface = ContextCompat.getColor(rootView.context, R.color.ime_surface)
        val toolbarBg = ContextCompat.getColor(rootView.context, R.color.ime_toolbar_bg)
        val accent = ContextCompat.getColor(rootView.context, R.color.ime_accent)

        val panelLight = Color.parseColor("#F0F0F0")
        val panelDark = Color.parseColor("#2A2A2A")

        rootView.setBackgroundColor(bg)
        expandedPanel.setBackgroundColor(surface)
        toolbarContainer.setBackgroundColor(toolbarBg)

        candidateStrip.setBackgroundColor(if (themeMode == 1) panelDark else panelLight)

        tvComposingPreview.setBackgroundColor(surface)
        tvComposingPreview.setTextColor(accent)
    }
}
