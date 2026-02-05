package com.example.myapp

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

        // 关键：不要 setBackgroundColor（会覆盖 bg_candidate_cell 的圆角/描边等效果）
        val bg = tv.background
        if (bg != null) {
            val tintColor = if (isDark) Color.parseColor("#2B2B2B") else Color.parseColor("#FFFFFF")
            bg.mutate().setTint(tintColor)
        }

        tv.setOnClickListener { onItemClick(candidate) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
