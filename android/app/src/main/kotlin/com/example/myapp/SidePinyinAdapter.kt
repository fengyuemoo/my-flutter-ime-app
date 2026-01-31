package com.example.myapp

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SidePinyinAdapter(
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<SidePinyinAdapter.ViewHolder>() {

    private val items = ArrayList<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                110 // 高度
            )
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundResource(R.drawable.bg_sidebar_item) 
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pinyin = items[position]
        (holder.itemView as TextView).text = pinyin
        
        holder.itemView.setOnClickListener {
            onItemClick(pinyin)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}