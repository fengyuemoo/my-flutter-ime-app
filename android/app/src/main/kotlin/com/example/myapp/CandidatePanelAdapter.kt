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

    // 展开面板：允许多行兜底，但我们会优先通过“占更多格”来避免换行/省略号
    private val panelMaxLines = 3

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
     * 像素宽度估算 + 偏向“不换行/不省略号”的保守策略：
     * - 短词（尤其中文 2~4 字）优先当成“1 行”来算，需要就升到 2 格
     * - 一旦估算到 3 格，直接升到 4 格整行
     */
    fun getSpanSize(
        position: Int,
        spanCount: Int,
        totalWidthPx: Int,
        context: Context
    ): Int {
        val word = items.getOrNull(position)?.word?.trim().orEmpty()
        if (word.isEmpty()) return 1

        // 宽度还没 layout 出来时，先给保底（偏保守，避免一上来就很窄导致换行）
        if (totalWidthPx <= 0) {
            val len = word.length
            val basic = when {
                len <= 2 -> 1
                len <= 4 -> minOf(2, spanCount)
                len <= 6 -> minOf(2, spanCount)
                else -> spanCount
            }
            return if (basic >= spanCount - 1) spanCount else basic
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

        val totalTextWidth = paint.measureText(word)

        // 英文/数字长串不易换行：用最长 token 宽度做下限
        val hasWhitespace = word.any { it.isWhitespace() }
        val maxTokenWidth = if (hasWhitespace) {
            val tokens = word.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var m = 0f
            for (t in tokens) m = max(m, paint.measureText(t))
            m
        } else {
            0f
        }

        // “更像你要的效果”的关键：短中文优先按 1 行计算（这样会更倾向占 2 格而不是换行）
        val preferredLines = when {
            word.length <= 4 -> 1
            word.length <= 8 -> 2
            else -> panelMaxLines
        }

        val requiredLineWidth = max(maxTokenWidth, totalTextWidth / preferredLines.toFloat())
        val requiredWidth = requiredLineWidth + extraPx

        var spans = ceil(requiredWidth / colWidthPx).toInt().coerceIn(1, spanCount)

        // 保守：只要算到 3 格，就整行（4 格）
        if (spans >= spanCount - 1) spans = spanCount

        return spans
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
