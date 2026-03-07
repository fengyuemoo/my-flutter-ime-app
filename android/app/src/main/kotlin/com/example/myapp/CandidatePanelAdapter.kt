package com.example.myapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.dict.model.Candidate
import kotlin.math.ceil
import kotlin.math.roundToInt

class CandidatePanelAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<CandidatePanelAdapter.ViewHolder>() {

    private val items = ArrayList<Candidate>()
    var themeMode = 0

    private var selectedIndex: Int = 0

    private val panelTextSp = 15f
    private val paddingHdp = 10f
    private val paddingVdp = 8f
    private val marginHdp = 4f
    private val marginVdp = 4f

    private val fullRowMaxLines = 12

    private fun thinTypeface(): Typeface {
        return Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    fun submitList(newItems: List<Candidate>) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = clampIndex(selectedIndex)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): Candidate? = items.getOrNull(position)

    fun getSelectedIndex(): Int = clampIndex(selectedIndex)

    fun setSelectedIndex(index: Int) {
        val newIndex = clampIndex(index)
        if (newIndex == selectedIndex) return

        val oldIndex = selectedIndex
        selectedIndex = newIndex

        if (oldIndex in items.indices) {
            notifyItemChanged(oldIndex)
        } else {
            notifyDataSetChanged()
        }

        if (selectedIndex in items.indices) {
            notifyItemChanged(selectedIndex)
        }
    }

    fun moveSelection(delta: Int): Int {
        if (items.isEmpty()) {
            selectedIndex = 0
            return selectedIndex
        }
        val next = clampIndex(selectedIndex + delta)
        setSelectedIndex(next)
        return selectedIndex
    }

    fun resetSelection() {
        setSelectedIndex(0)
    }

    private fun clampIndex(index: Int): Int {
        if (items.isEmpty()) return 0
        return index.coerceIn(0, items.lastIndex)
    }

    fun getSpanSize(
        position: Int,
        spanCount: Int,
        totalWidthPx: Int,
        context: Context
    ): Int {
        val word = items.getOrNull(position)?.word?.trim().orEmpty()
        if (word.isEmpty()) return 1
        if (totalWidthPx <= 0) return 1

        val metrics = context.resources.displayMetrics
        val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, panelTextSp, metrics)

        val paddingHPx = (paddingHdp * metrics.density * 2f).roundToInt()
        val marginHPx = (marginHdp * metrics.density * 2f).roundToInt()
        val extraPx = paddingHPx + marginHPx

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = textPx
            typeface = thinTypeface()
        }

        val textWidth = paint.measureText(word)
        val needPx = textWidth + extraPx

        val colWidth = totalWidthPx.toFloat() / spanCount.toFloat()
        return ceil(needPx / colWidth).toInt().coerceIn(1, spanCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        fun Float.dp(): Int = (this * ctx.resources.displayMetrics.density).roundToInt()

        val tv = TextView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginHdp.dp(), marginVdp.dp(), marginHdp.dp(), marginVdp.dp())
            }

            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, panelTextSp)
            setPadding(paddingHdp.dp(), paddingVdp.dp(), paddingHdp.dp(), paddingVdp.dp())

            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }

            isClickable = true
            isFocusable = true
            typeface = thinTypeface()
        }

        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = items[position]
        val tv = holder.itemView as TextView
        tv.text = candidate.word

        val isDark = themeMode == 1
        val isSelected = position == selectedIndex

        tv.setTextColor(
            when {
                isSelected && isDark -> Color.WHITE
                isSelected && !isDark -> Color.BLACK
                isDark -> Color.WHITE
                else -> Color.BLACK
            }
        )
        tv.background = buildPanelChipBackground(tv.context, isDark, isSelected)

        val parentRv = tv.parent
        val totalWidthPx = (parentRv as? RecyclerView)?.let {
            it.width - it.paddingLeft - it.paddingRight
        } ?: 0

        if (totalWidthPx > 0) {
            val spanCount = 4
            val spans = getSpanSize(position, spanCount, totalWidthPx, tv.context)
            val isFullRow = spans >= spanCount

            if (isFullRow) {
                tv.isSingleLine = false
                tv.maxLines = fullRowMaxLines
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            } else {
                tv.isSingleLine = true
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
                tv.gravity = Gravity.CENTER
            }
        }

        tv.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                setSelectedIndex(idx)
                onItemClick(idx)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun buildPanelChipBackground(
        ctx: Context,
        isDark: Boolean,
        isSelected: Boolean
    ): Drawable {
        fun Int.dp(): Float = this * ctx.resources.displayMetrics.density

        val radius = 12.dp()
        val strokeW = 1.dp().roundToInt()

        val normalFillBottom = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#FFFFFF")
        val normalFillTop = if (isDark) Color.parseColor("#303030") else Color.parseColor("#FFFFFF")
        val normalStroke = if (isDark) Color.parseColor("#2FFFFFFF") else Color.parseColor("#1A000000")

        val selectedFillBottom = if (isDark) Color.parseColor("#343434") else Color.parseColor("#DCEBFF")
        val selectedFillTop = if (isDark) Color.parseColor("#3A3A3A") else Color.parseColor("#E8F2FF")
        val selectedStroke = if (isDark) Color.parseColor("#88FFFFFF") else Color.parseColor("#661A5DFF")

        val fillBottom = if (isSelected) selectedFillBottom else normalFillBottom
        val fillTop = if (isSelected) selectedFillTop else normalFillTop
        val stroke = if (isSelected) selectedStroke else normalStroke

        val content = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(fillTop, fillBottom)
        ).apply {
            cornerRadius = radius
            setStroke(strokeW, stroke)
        }

        val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#14000000")
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
        } else {
            content
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
