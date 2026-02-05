package com.example.myapp

import android.content.res.ColorStateList
import android.graphics.Color
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

        val tv = TextView(ctx).apply {
            val width = if (isGrid) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = ViewGroup.MarginLayoutParams(width, if (isGrid) 44.dp() else 40.dp()).apply {
                if (!isGrid) setMargins(4.dp(), 0, 4.dp(), 0) else setMargins(0, 0, 0, 0)
            }

            gravity = Gravity.CENTER

            // 更紧凑一点，争取一屏显示更多候选（单字目标 7 个）
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

            // 横向模式：减少 padding + 降低 minWidth（原来 minWidth=100px、padding=30px 更占宽）[旧逻辑见原文件]
            if (!isGrid) {
                minWidth = 44.dp()
                setPadding(10.dp(), 0, 10.dp(), 0)
            } else {
                setPadding(12.dp(), 0, 12.dp(), 0)
            }

            // 统一单行显示，长词组省略号（避免撑爆宽度）
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END

            // 使用 drawable 做圆角/描边，后面用 tint 适配深浅色
            setBackgroundResource(R.drawable.bg_candidate_cell)

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

        // 不要 setBackgroundColor（会覆盖 bg_candidate_cell）
        val bg = tv.background
        if (bg != null) {
            val tintColor = if (isDark) Color.parseColor("#2F2F2F") else Color.parseColor("#FFFFFF")
            bg.mutate().setTint(tintColor)
        } else {
            // 极端兜底
            tv.backgroundTintList = ColorStateList.valueOf(if (isDark) Color.parseColor("#2F2F2F") else Color.WHITE)
        }

        tv.setOnClickListener { onItemClick(candidate) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
