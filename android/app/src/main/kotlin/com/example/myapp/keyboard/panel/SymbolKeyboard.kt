package com.example.myapp.keyboard.panel

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.SymbolPanelUi
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.PanelMode
import com.example.myapp.keyboard.core.RawCommitMode

class SymbolKeyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.panel_symbols), PanelMode, RawCommitMode, SymbolPanelUi {

    private val recycler: RecyclerView = rootView.findViewById(R.id.symbolRecycler)

    private val tabCommon: Button = rootView.findViewById(R.id.tabCommon)
    private val tabCn: Button = rootView.findViewById(R.id.tabCn)
    private val tabEn: Button = rootView.findViewById(R.id.tabEn)

    private val adapter = SymbolGridAdapter(
        onSymbolClick = { symbol -> ime.commitSymbolFromPanel(symbol) },
        onActionClick = { action ->
            when (action) {
                SymbolAction.Back -> ime.closeSymbolPanel()
                SymbolAction.PageUp -> ime.symbolPageUp()
                SymbolAction.PageDown -> ime.symbolPageDown()
                SymbolAction.ToggleLock -> ime.toggleSymbolLock()
            }
        }
    )

    init {
        recycler.layoutManager = GridLayoutManager(context, 5)
        recycler.adapter = adapter

        tabCommon.setOnClickListener { ime.setSymbolCategory(ImeActions.SymbolCategory.COMMON) }
        tabCn.setOnClickListener { ime.setSymbolCategory(ImeActions.SymbolCategory.CN) }
        tabEn.setOnClickListener { ime.setSymbolCategory(ImeActions.SymbolCategory.EN) }
    }

    override fun onActivate() {
        // Do not render here by reading IME state.
        // Dispatcher will push state via KeyboardController.updateSymbolPanelUi(...).
    }

    // We don't use BaseKeyboard's default button traversal here; RecyclerView handles clicks.
    override fun handleKeyPress(button: Button) {
        // No-op
    }

    override fun renderSymbolPanel(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean,
        isChineseMainMode: Boolean
    ) {
        // Highlight tabs (simple UI; you can theme later)
        highlightTab(category)

        val cells = buildPageCells(
            category = category,
            page = page,
            locked = locked,
            isChineseMainMode = isChineseMainMode
        )
        adapter.submit(cells)
    }

    private fun highlightTab(category: ImeActions.SymbolCategory) {
        fun styleTab(btn: Button, selected: Boolean) {
            btn.setTextColor(if (selected) Color.CYAN else Color.BLACK)
        }
        styleTab(tabCommon, category == ImeActions.SymbolCategory.COMMON)
        styleTab(tabCn, category == ImeActions.SymbolCategory.CN)
        styleTab(tabEn, category == ImeActions.SymbolCategory.EN)
    }

    private fun buildPageCells(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean,
        isChineseMainMode: Boolean
    ): List<SymbolCell> {
        val symbols = when (category) {
            ImeActions.SymbolCategory.COMMON -> COMMON_SYMBOLS
            ImeActions.SymbolCategory.CN -> CN_SYMBOLS
            ImeActions.SymbolCategory.EN -> EN_SYMBOLS
        }

        // Left area: 4 cols * 4 rows = 16 symbols per page
        val pageSize = 16
        val from = page * pageSize
        val to = minOf(from + pageSize, symbols.size)
        val pageSymbols = if (from in 0..symbols.size && from < to) symbols.subList(from, to) else emptyList()

        // 4 rows * 5 cols = 20 cells
        val result = ArrayList<SymbolCell>(20)

        // Build 4 rows
        var symIndex = 0
        for (row in 0 until 4) {
            // left 4 symbol cells
            for (col in 0 until 4) {
                val text = pageSymbols.getOrNull(symIndex)
                result.add(
                    if (text == null) SymbolCell.Empty else SymbolCell.Symbol(text)
                )
                symIndex++
            }

            // right function column
            val actionCell: SymbolCell = when (row) {
                0 -> {
                    val backText = if (isChineseMainMode) "返回" else "🔙"
                    SymbolCell.Action(SymbolAction.Back, backText)
                }
                1 -> SymbolCell.Action(SymbolAction.PageUp, "︿")
                2 -> SymbolCell.Action(SymbolAction.PageDown, "﹀")
                else -> {
                    // lock
                    val lockText = if (locked) "🔒" else "🔓"
                    SymbolCell.Action(SymbolAction.ToggleLock, lockText)
                }
            }
            result.add(actionCell)
        }

        return result
    }

    companion object {

        // Common: mix of CN/EN/web/programming frequent symbols (starter set).
        // Later you can add MRU (recently used) ahead of this list.
        private val COMMON_SYMBOLS = listOf(
            ",", ".", "?", "!", "…", ":", ";", "，",
            "。", "？", "！", "、", "“", "”", "\"", "'",
            "(", ")", "[", "]", "{", "}", "<", ">",
            "@", "#", "$", "%", "&", "+", "-", "*",
            "/", "=", "_", "\\", "|", "~", "^", "`"
        )

        private val CN_SYMBOLS = listOf(
            "，", "。", "？", "！", "……", "：", "；", "、",
            "“", "”", "‘", "’", "（", "）", "《", "》",
            "【", "】", "「", "」", "『", "』", "—", "·",
            "～", "…", "￥", "％", "＆", "＠"
        )

        private val EN_SYMBOLS = listOf(
            ",", ".", "?", "!", "...", ":", ";", "\"",
            "'", "(", ")", "[", "]", "{", "}", "<",
            ">", "@", "#", "$", "%", "&", "+", "-",
            "*", "/", "=", "_", "\\", "|", "~", "^",
            "`"
        )
    }
}

private sealed class SymbolCell {
    data object Empty : SymbolCell()
    data class Symbol(val text: String) : SymbolCell()
    data class Action(val action: SymbolAction, val text: String) : SymbolCell()
}

private enum class SymbolAction { Back, PageUp, PageDown, ToggleLock }

private class SymbolGridAdapter(
    private val onSymbolClick: (String) -> Unit,
    private val onActionClick: (SymbolAction) -> Unit
) : RecyclerView.Adapter<SymbolGridAdapter.VH>() {

    private val items: MutableList<SymbolCell> = ArrayList()

    fun submit(newItems: List<SymbolCell>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_symbol_cell, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cell = items[position]
        holder.bind(cell, onSymbolClick, onActionClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.symbolCellText)

        fun bind(
            cell: SymbolCell,
            onSymbolClick: (String) -> Unit,
            onActionClick: (SymbolAction) -> Unit
        ) {
            itemView.isClickable = true
            itemView.setOnClickListener(null)

            when (cell) {
                is SymbolCell.Empty -> {
                    tv.text = ""
                    itemView.isClickable = false
                }

                is SymbolCell.Symbol -> {
                    tv.text = cell.text
                    itemView.setOnClickListener { onSymbolClick(cell.text) }
                }

                is SymbolCell.Action -> {
                    tv.text = cell.text
                    itemView.setOnClickListener { onActionClick(cell.action) }
                }
            }
        }
    }
}
