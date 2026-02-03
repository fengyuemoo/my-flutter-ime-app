package com.example.myapp

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.dict.model.Candidate

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
        val tv = TextView(parent.context).apply {
            val width = if (isGrid) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = ViewGroup.MarginLayoutParams(width, dp(40)).apply {
                if (!isGrid) setMargins(dp(6), 0, dp(6), 0) else setMargins(0, 0, 0, 0)
            }
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (!isGrid) minWidth = dp(60)
            setPadding(dp(12), 0, dp(12), 0)

            // The background will be set in onBindViewHolder
            // setBackgroundResource(R.drawable.ripple_ime_key)

            typeface = Typeface.SANS_SERIF
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val candidate = items[position]
        val tv = holder.itemView as TextView
        tv.text = candidate.word

        val isDark = themeMode == 1
        tv.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
        tv.setBackgroundColor(if (isDark) Color.parseColor("#333333") else Color.parseColor("#FFFFFF"))

        tv.setOnClickListener { onItemClick(candidate) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun TextView.dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
