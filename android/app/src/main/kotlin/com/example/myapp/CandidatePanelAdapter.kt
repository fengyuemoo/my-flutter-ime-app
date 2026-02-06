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

    // UI 参数：要和 TextView 实际一致，测量才准
    private val panelTextSp = 15f
    private val paddingHdp = 10f
    private val paddingVdp = 8f
    private val marginHdp = 4f
    private val marginVdp = 4f

    private val fullRowMaxLines = 12

    fun submitList(newItems: List<Candidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /**
     * 默认每行 4 等分（span=1），只有放不下才升到 2/3/4。
     * 规则是按像素测量：能放进 1 列就 1，能放进 2 列就 2……否则 4（整行）。
     */
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
            typeface = android.graphics.Typeface.SANS_SERIF
        }

        val textWidth = paint.measureText(word)
        val needPx = textWidth + extraPx

        val colWidth = totalWidthPx.toFloat() / spanCount.toFloat()
        val spans = ceil(needPx / colWidth).toInt().coerceIn(1, spanCount)

        return spans
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        fun Float.dp(): Int = (this * ctx.resources.displayMetrics.density).roundToInt()

        val tv = TextView(ctx).apply {
            // GridLayoutManager 会根据 span 分配宽度，这里 MATCH_PARENT 就是“填满本格/跨格宽度”
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginHdp.dp(), marginVdp.dp(), marginHdp.dp(), marginVdp.dp())
            }

            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, panelTextSp)
            setPadding(paddingHdp.dp(), paddingVdp.dp(), paddingHdp.dp(), paddingVdp.dp())

            // 默认按“普通格子”：单行，避免在窄格子里变成 2 字一行
            isSingleLine = true
            maxLines = 1
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

        // 当它“占满整行”（span=4）时，允许它自身多行，并保持整行宽度显示
        // 注意：这里用 parent 宽度判断不方便，因此用 LayoutParams.width==MATCH_PARENT + maxLines 切换即可，
        // 实际是否 span=4 由 SpanSizeLookup 决定；我们用“字符测量是否远超 1 格”的启发式来开启多行。
        val parentRv = tv.parent
        val totalWidthPx = (parentRv as? RecyclerView)?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0

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
