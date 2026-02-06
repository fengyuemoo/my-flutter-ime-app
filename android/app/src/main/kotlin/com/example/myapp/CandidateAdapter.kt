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

        // 轻微强调第一个候选（通常是最可能的那个），让候选栏更“有层次”
        val isPrimary = position == 0 && !isGrid

        tv.background = buildCandidateChipBackground(tv.context, isDark, isPrimary)

        tv.setOnClickListener { onItemClick(candidate) }
    }

    override fun getItemCount(): Int = items.size

    private fun buildCandidateChipBackground(ctx: Context, isDark: Boolean, isPrimary: Boolean): Drawable {
        fun Int.dp(): Float = this * ctx.resources.displayMetrics.density

        val radius = 11.dp()
        val strokeW = 1.dp().roundToInt()

        val fill = if (isDark) Color.parseColor("#262626") else Color.parseColor("#FFFFFF")
        val fillTop = if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#FFFFFF")

        val stroke = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A000000")
        val primaryStroke = if (isDark) Color.parseColor("#66FFFFFF") else Color.parseColor("#33000000")

        val content = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fillTop, fill)).apply {
            cornerRadius = radius
            setStroke(strokeW, if (isPrimary) primaryStroke else stroke)
        }

        val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#14000000")
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
        } else {
            // 旧系统兜底（没有 ripple）
            content
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
