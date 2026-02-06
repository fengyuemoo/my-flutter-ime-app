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

class CandidatePanelAdapter(
    private val onItemClick: (Candidate) -> Unit
) : RecyclerView.Adapter<CandidatePanelAdapter.ViewHolder>() {

    private val items = ArrayList<Candidate>()
    var themeMode = 0

    fun submitList(newItems: List<Candidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getSpanSize(position: Int, spanCount: Int): Int {
        val word = items.getOrNull(position)?.word ?: return 1
        val len = word.trim().length

        // 你现在是 4 列网格：短词 1 格，中等词 2 格，长词 4 格独占一行
        return when {
            len >= 6 -> spanCount          // 很长：整行
            len >= 4 -> minOf(2, spanCount) // 中等：两格
            else -> 1                      // 短：一格
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        fun Int.dp(): Int = (this * ctx.resources.displayMetrics.density).roundToInt()

        val tv = TextView(ctx).apply {
            // GridLayoutManager 会根据 span 宽度分配 item 的实际宽度，这里用 MATCH_PARENT 即可
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp())
            }

            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())

            // 允许多行显示，尽量不截断
            isSingleLine = false
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END

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

        // 展开面板不需要“首选强强调”，避免视觉太花；只做统一 chip
        tv.background = buildPanelChipBackground(tv.context, isDark)

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
