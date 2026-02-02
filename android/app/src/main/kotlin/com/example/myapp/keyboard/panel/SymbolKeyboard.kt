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
import com.example.myapp.ime.prefs.SymbolPrefs
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.PanelMode
import com.example.myapp.keyboard.core.RawCommitMode

class SymbolKeyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.panel_symbols), PanelMode, RawCommitMode, SymbolPanelUi {

    private val recycler: RecyclerView = rootView.findViewById(R.id.symbolRecycler)

    // Bottom tabs
    private val tabCommon: Button = rootView.findViewById(R.id.tabCommon)
    private val tabCn: Button = rootView.findViewById(R.id.tabCn)
    private val tabEn: Button = rootView.findViewById(R.id.tabEn)

    // Right fixed action column (from panel_symbols.xml)
    private val btnBack: Button = rootView.findViewById(R.id.btnSymBack)
    private val btnUp: Button = rootView.findViewById(R.id.btnSymUp)
    private val btnDown: Button = rootView.findViewById(R.id.btnSymDown)
    private val btnLock: Button = rootView.findViewById(R.id.btnSymLock)

    private val adapter = SymbolListAdapter(
        onSymbolClick = { symbol -> ime.commitSymbolFromPanel(symbol) }
    )

    init {
        // Left side: real scrollable grid, 4 columns
        recycler.layoutManager = GridLayoutManager(context, 4)
        recycler.adapter = adapter

        // Tabs
        tabCommon.setOnClickListener { ime.setSymbolCategory(ImeActions.SymbolCategory.COMMON) }
        tabCn.setOnClickListener { ime.setSymbolCategory(ImeActions.SymbolCategory.CN) }
        tabEn.setOnClickListener { ime.setSymbolCategory(ImeActions.SymbolCategory.EN) }

        // Right column actions
        btnBack.setOnClickListener { ime.closeSymbolPanel() }

        // In real-scroll mode, Up/Down mean "scroll", not "page".
        btnUp.setOnClickListener {
            // Scroll to top is the most predictable.
            recycler.smoothScrollToPosition(0)
        }
        btnDown.setOnClickListener {
            // Scroll down by one viewport height.
            recycler.smoothScrollBy(0, recycler.height)
        }

        btnLock.setOnClickListener { ime.toggleSymbolLock() }
    }

    override fun onActivate() {
        // Dispatcher will push state via KeyboardController.updateSymbolPanelUi(...)
    }

    override fun handleKeyPress(button: Button) {
        // No-op: RecyclerView + fixed column handle clicks
    }

    override fun renderSymbolPanel(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean,
        isChineseMainMode: Boolean
    ) {
        // page is ignored in "real scroll" mode (we keep the interface for dispatcher compatibility).
        highlightTab(category)

        // Back label varies by main language (your requirement)
        btnBack.text = if (isChineseMainMode) "返回" else "🔙"

        // Lock icon reflects current state
        btnLock.text = if (locked) "🔒" else "🔓"

        val symbols = when (category) {
            ImeActions.SymbolCategory.COMMON -> buildCommonSymbols()
            ImeActions.SymbolCategory.CN -> CN_SYMBOLS
            ImeActions.SymbolCategory.EN -> EN_SYMBOLS
        }

        adapter.submit(symbols)
    }

    private fun highlightTab(category: ImeActions.SymbolCategory) {
        fun styleTab(btn: Button, selected: Boolean) {
            btn.setTextColor(if (selected) Color.CYAN else Color.BLACK)
        }
        styleTab(tabCommon, category == ImeActions.SymbolCategory.COMMON)
        styleTab(tabCn, category == ImeActions.SymbolCategory.CN)
        styleTab(tabEn, category == ImeActions.SymbolCategory.EN)
    }

    private fun buildCommonSymbols(): List<String> {
        // MRU first, then default list without duplicates.
        val mru = SymbolPrefs.loadMruCommon(context)

        if (mru.isEmpty()) return DEFAULT_COMMON_SYMBOLS

        val set = LinkedHashSet<String>(mru.size + DEFAULT_COMMON_SYMBOLS.size)
        for (s in mru) set.add(s)
        for (s in DEFAULT_COMMON_SYMBOLS) set.add(s)
        return set.toList()
    }

    companion object {
        // Default Common: "Sogou-like" starter (mixed punctuation + web/email + brackets + programming)
        private val DEFAULT_COMMON_SYMBOLS = listOf(
            ",", ".", "?", "!", "…", ":", ";", "\"",
            "'", "(", ")", "[", "]", "{", "}", "<",
            ">", "@", "#", "$", "%", "&", "+", "-",
            "*", "/", "=", "_", "\\", "|", "~", "^",
            "`", "，", "。", "？", "！", "、", "“", "”"
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

private class SymbolListAdapter(
    private val onSymbolClick: (String) -> Unit
) : RecyclerView.Adapter<SymbolListAdapter.VH>() {

    private val items: MutableList<String> = ArrayList()

    fun submit(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_symbol_cell, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val symbol = items[position]
        holder.bind(symbol, onSymbolClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.symbolCellText)

        fun bind(symbol: String, onSymbolClick: (String) -> Unit) {
            tv.text = symbol
            itemView.setOnClickListener { onSymbolClick(symbol) }
        }
    }
}
