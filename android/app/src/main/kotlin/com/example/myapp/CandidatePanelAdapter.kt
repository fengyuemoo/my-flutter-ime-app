package com.example.myapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import kotlin.math.max
import kotlin.math.roundToInt

class CandidatePanelAdapter(
    private val onItemClick: (Candidate) -> Unit
) : RecyclerView.Adapter<CandidatePanelAdapter.ViewHolder>() {

    private val items = ArrayList<Candidate>()
    var themeMode = 0

    // 与 item 视觉参数保持一致，span 估算才会更准
    private val panelTextSp = 15f
    private val panelMaxLines = 4

    // 与 onCreateViewHolder 一致：padding(10dp,8dp,10dp,8dp), margin=4dp
    private val paddingHdp = 10f  // left/right each
    private val paddingVdp = 8f   // top/bottom each
    private val marginHdp = 4f    // left/right each
    private val marginVdp = 4f

    fun submitList(newItems: List<Candidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * 像素宽度估算 + 更保守策略：
     * - 先按测量估算需要 1..spanCount 列
     * - 如果估算到 3 列（或更大），直接升级为整行（4 列），尽量避免省略号
     */
    fun getSpanSize(
        position: Int,
        spanCount: Int,
        totalWidthPx: Int,
        context: Context
    ): Int {
        val word = items.getOrNull(position)?.word?.trim().orEmpty()
        if (word.isEmpty()) return 1

        // 宽度还没 layout 出来时，先用简单保底规则
        if (totalWidthPx <= 0) {
            val len = word.length
            val basic = when {
                len >= 6 -> spanCount
                len >= 4 -> minOf(2, spanCount)
                else -> 1
            }
            // 保守：只要达到 3，就整行（这里 basic 不会到 3，但保持一致性）
            return if (basic >= 3) spanCount else basic
        }

        val metrics = context.resources.displayMetrics
        val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, panelTextSp, metrics)

        val paddingHPx = (paddingHdp * metrics.density * 2f).roundToInt()
        val marginHPx = (marginHdp * metrics.density * 2f).roundToInt()
        val extraPx = paddingHPx + marginHPx

        val colWidthPx = totalWidthPx.toFloat() / spanCount.toFloat()

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = textPx
            typeface = android.graphics.Typeface.SANS_SERIF
        }

        // 总文本宽度（单行情况下）
        val totalTextWidth = paint.measureText(word)

        // 估算“不可拆分的最小宽度”：
        // - 有空格：按最长 token（英文长串不会被低估）
        // - 无空格：按最大单字符宽度（CJK 可逐字换行，避免太保守）
        val hasWhitespace = word.any { it.isWhitespace() }
        val unbreakableMinWidth = if (hasWhitespace) {
            val tokens = word.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var maxToken = 0f
            for (t in tokens) maxToken = max(maxToken, paint.measureText(t))
            if (tokens.isEmpty()) totalTextWidth else maxToken
        } else {
            val n = minOf(word.length, 32)
            var maxChar = 0f
            for (i in 0 until n) {
                maxChar = max(maxChar, paint.measureText(word[i].toString()))
            }
            if (maxChar <= 0f) totalTextWidth else maxChar
        }

        // 允许最多 panelMaxLines 行：把总宽度按行数摊薄（粗略估算）
        val wrapWidthEstimate = totalTextWidth / panelMaxLines.toFloat()

        // “单行至少需要的宽度”取更严格的一边
        val requiredLineWidth = max(unbreakableMinWidth, wrapWidthEstimate)

        val requiredWidth = requiredLineWidth + extraPx
        val spans = ceil(requiredWidth / colWidthPx).toInt().coerceIn(1, spanCount)

        // 更保守：只要估算到 3 列，就直接整行（4列）
        return if (spans >= spanCount - 1) spanCount else spans
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

            isSingleLine = false
            maxLines = panelMaxLines
            ellipsize = TextUtils.TruncateAt.END

            // 更好的换行质量（不影响低版本）
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
        tv.text = candidate.word

        val isDark = themeMode == 1
        tv.setTextColor(if (isDark) Color.WHITE else Color.BLACK)

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
