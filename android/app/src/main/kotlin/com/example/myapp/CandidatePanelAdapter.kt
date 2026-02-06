package com.example.myapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.dict.model.Candidate
import com.google.android.flexbox.FlexboxLayoutManager
import kotlin.math.roundToInt

class CandidatePanelAdapter(
    private val onItemClick: (Candidate) -> Unit
) : RecyclerView.Adapter<CandidatePanelAdapter.ViewHolder>() {

    private val items = ArrayList<Candidate>()
    var themeMode = 0

    private var availableWidthPx: Int = 0

    // UI 常量（保持与视觉一致，测量才准）
    private val panelTextSp = 15f
    private val panelMaxLinesLong = 12 // 想更完整就调大；接受撑高就可以更大
    private val paddingHdp = 10f
    private val paddingVdp = 8f
    private val marginHdp = 4f
    private val marginVdp = 4f

    fun setAvailableWidthPx(px: Int) {
        availableWidthPx = px
    }

    fun submitList(newItems: List<Candidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        fun Float.dp(): Int = (this * ctx.resources.displayMetrics.density).roundToInt()

        val tv = TextView(ctx).apply {
            layoutParams = FlexboxLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginHdp.dp(), marginVdp.dp(), marginHdp.dp(), marginVdp.dp())
                flexGrow = 0f
                flexShrink = 1f
                isWrapBefore = false
            }

            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, panelTextSp)
            setPadding(paddingHdp.dp(), paddingVdp.dp(), paddingHdp.dp(), paddingVdp.dp())

            // 默认：短词单行 chip
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }

            isClickable = true
            isFocusable = true
            typeface = android.graphics.Typeface.SANS_SERIF
        }

        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = items[position]
        val tv = holder.itemView as TextView
        val word = candidate.word ?: ""
        tv.text = word

        val isDark = themeMode == 1
        tv.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
        tv.background = buildPanelChipBackground(tv.context, isDark)

        val lp = tv.layoutParams as FlexboxLayoutManager.LayoutParams

        // 计算“这一行的最大可用宽度”（扣掉左右 margin）
        val metrics = tv.context.resources.displayMetrics
        val marginHPx = (marginHdp * metrics.density * 2f).roundToInt()
        val fullRowWidth = (availableWidthPx - marginHPx).coerceAtLeast(1)

        if (availableWidthPx > 0) {
            // 这部分用来判断“是否超长到需要整行多行显示”
            val textWidth = tv.paint.measureText(word)
            val paddingHPx = (paddingHdp * metrics.density * 2f).roundToInt()
            val singleLineNeed = (textWidth + paddingHPx).toInt()

            val isTooLongForOneRow = singleLineNeed > fullRowWidth

            if (isTooLongForOneRow) {
                // 超长：强制占满整行，并且自身允许多行
                lp.isWrapBefore = true
                lp.width = fullRowWidth
                lp.flexGrow = 0f
                lp.flexShrink = 0f
                tv.layoutParams = lp

                tv.maxWidth = fullRowWidth
                tv.isSingleLine = false
                tv.maxLines = panelMaxLinesLong
                tv.ellipsize = TextUtils.TruncateAt.END
            } else {
                // 正常：连续延展的 chip（单行，宽度随内容）
                lp.isWrapBefore = false
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.flexGrow = 0f
                lp.flexShrink = 1f
                tv.layoutParams = lp

                tv.maxWidth = fullRowWidth
                tv.isSingleLine = true
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
            }
        } else {
            // 宽度未知：先按普通 chip 渲染
            lp.isWrapBefore = false
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.flexGrow = 0f
            lp.flexShrink = 1f
            tv.layoutParams = lp

            tv.isSingleLine = true
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
        }

        tv.setOnClickListener { onItemClick(candidate) }
    }

    override fun getItemCount(): Int = items.size

    private fun buildPanelChipBackground(ctx: Context, isDark: Boolean): Drawable {
        fun Int.dp(): Float = this * ctx.resources.displayMetrics.density

        val radius = 12.dp()
        val strokeW = 1.dp().roundToInt()

        val fillBottom = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#FFFFFF")
        val fillTop = if (isDark) Color.parseColor("#303030") else Color.parseColor("#FFFFFF")
        val stroke = if (isDark) Color.parseColor("#2FFFFFFF") else Color.parseColor("#1A000000")

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
