package com.example.myapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.dict.model.Candidate
import kotlin.math.roundToInt

class CandidateAdapter(
    private val isGrid: Boolean,
    private val onItemClick: (Candidate) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {

    private val items = ArrayList<Candidate>()
    var themeMode = 0

    fun submitList(newItems: List<Candidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        fun Int.dp(): Int = (this * ctx.resources.displayMetrics.density).roundToInt()

        // 保留你已经调好的“7个刚好”的参数（高度/字号/minWidth/padding/margin）
        val tv = TextView(ctx).apply {
            val width = if (isGrid) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = ViewGroup.MarginLayoutParams(width, if (isGrid) 42.dp() else 40.dp()).apply {
                if (!isGrid) setMargins(3.dp(), 0, 3.dp(), 0) else setMargins(0, 0, 0, 0)
            }

            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)

            if (!isGrid) {
                minWidth = 38.dp()
                setPadding(6.dp(), 0, 6.dp(), 0)
            } else {
                setPadding(10.dp(), 0, 10.dp(), 0)
            }

            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END

            // 让 ripple 生效
            isClickable = true
            isFocusable = true

            typeface = android.graphics.Typeface.SANS_SERIF
        }

        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = items[position]
        val tv = holder.itemView as TextView
        tv.text = candidate.word

        val isDark = themeMode == 1
        tv.setTextColor(if (isDark) Color.WHITE else Color.BLACK)

        // 明显强调第一个候选（横向候选栏里 position==0）
        val isPrimary = position == 0 && !isGrid

        tv.background = buildCandidateChipBackground(tv.context, isDark, isPrimary)

        tv.setOnClickListener { onItemClick(candidate) }
    }

    override fun getItemCount(): Int = items.size

    private fun buildCandidateChipBackground(ctx: Context, isDark: Boolean, isPrimary: Boolean): Drawable {
        fun Int.dp(): Float = this * ctx.resources.displayMetrics.density

        val radius = 11.dp()
        val strokeW = 1.dp().roundToInt()

        // 普通候选
        val normalFillBottom = if (isDark) Color.parseColor("#262626") else Color.parseColor("#FFFFFF")
        val normalFillTop = if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#FFFFFF")
        val normalStroke = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A000000")

        // 第一个候选：更深底色 + 更明显描边（轻蓝强调，观感更“高亮”）
        val primaryFillBottom = if (isDark) Color.parseColor("#343434") else Color.parseColor("#DCEBFF")
        val primaryFillTop = if (isDark) Color.parseColor("#3A3A3A") else Color.parseColor("#E8F2FF")
        val primaryStroke = if (isDark) Color.parseColor("#88FFFFFF") else Color.parseColor("#661A5DFF")

        val fillTop = if (isPrimary) primaryFillTop else normalFillTop
        val fillBottom = if (isPrimary) primaryFillBottom else normalFillBottom
        val stroke = if (isPrimary) primaryStroke else normalStroke

        val content = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fillTop, fillBottom)).apply {
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
