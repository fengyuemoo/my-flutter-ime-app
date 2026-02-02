package com.example.myapp.keyboard.panel

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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

    private val tabContainer: LinearLayout = rootView.findViewById(R.id.symbolTabContainer)

    private val btnBack: Button = rootView.findViewById(R.id.btnSymBack)
    private val btnUp: Button = rootView.findViewById(R.id.btnSymUp)
    private val btnDown: Button = rootView.findViewById(R.id.btnSymDown)
    private val btnLock: Button = rootView.findViewById(R.id.btnSymLock)

    private val adapter = SymbolListAdapter(
        onSymbolClick = { symbol -> ime.commitSymbolFromPanel(symbol) }
    )

    private val categoryDefs: List<CategoryDef> = listOf(
        CategoryDef(ImeActions.SymbolCategory.COMMON, "常用", "Common"),
        CategoryDef(ImeActions.SymbolCategory.CN, "中文", "CN"),
        CategoryDef(ImeActions.SymbolCategory.EN, "英文", "EN"),
        CategoryDef(ImeActions.SymbolCategory.WEB, "网络", "Web"),
        CategoryDef(ImeActions.SymbolCategory.EMAIL, "邮箱", "Email"),
        CategoryDef(ImeActions.SymbolCategory.KAOMOJI, "颜文", "Kaomoji"),
        CategoryDef(ImeActions.SymbolCategory.MATH, "数学", "Math"),
        CategoryDef(ImeActions.SymbolCategory.SUPER, "角标", "Sup"),
        CategoryDef(ImeActions.SymbolCategory.SERIAL, "序号", "No."),
        CategoryDef(ImeActions.SymbolCategory.IPA, "音标", "IPA"),
        CategoryDef(ImeActions.SymbolCategory.JAPANESE, "日文", "Jpn"),
        CategoryDef(ImeActions.SymbolCategory.ARROWS, "箭头", "Arrow"),
        CategoryDef(ImeActions.SymbolCategory.SPECIAL, "特殊", "Special"),
        CategoryDef(ImeActions.SymbolCategory.PINYIN, "拼音", "Pinyin"),
        CategoryDef(ImeActions.SymbolCategory.ZHUYIN, "注音", "Zhuyin"),
        CategoryDef(ImeActions.SymbolCategory.VERTICAL, "竖标", "Vert"),
        CategoryDef(ImeActions.SymbolCategory.RADICALS, "部首", "Rad"),
        CategoryDef(ImeActions.SymbolCategory.RUSSIAN, "俄文", "Rus"),
        CategoryDef(ImeActions.SymbolCategory.GREEK, "希腊", "Greek"),
        CategoryDef(ImeActions.SymbolCategory.LATIN, "拉丁", "Latin"),
        CategoryDef(ImeActions.SymbolCategory.BOX, "制表", "Box"),
        CategoryDef(ImeActions.SymbolCategory.SYLLABICS, "土音", "Syll"),
        CategoryDef(ImeActions.SymbolCategory.TIBETAN, "藏文", "Tibet")
    )

    // Keep references for updating selected state without recreating views
    private val tabButtons: MutableMap<ImeActions.SymbolCategory, Button> = LinkedHashMap()

    init {
        recycler.layoutManager = GridLayoutManager(context, 4)
        recycler.adapter = adapter
        recycler.addItemDecoration(GridSpacingDecoration(4, dpToPx(6), includeEdge = true))

        btnBack.setOnClickListener { ime.closeSymbolPanel() }
        btnUp.setOnClickListener { recycler.smoothScrollToPosition(0) }
        btnDown.setOnClickListener { recycler.smoothScrollBy(0, recycler.height) }
        btnLock.setOnClickListener { ime.toggleSymbolLock() }
    }

    override fun onActivate() {
        // Dispatcher will push state via KeyboardController.updateSymbolPanelUi(...)
    }

    override fun handleKeyPress(button: Button) {
        // No-op
    }

    override fun renderSymbolPanel(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean,
        isChineseMainMode: Boolean
    ) {
        // page ignored in real-scroll mode
        ensureTabsBuilt(isChineseMainMode)

        updateTabSelected(category)

        btnBack.text = if (isChineseMainMode) "返回" else "🔙"
        btnLock.text = if (locked) "🔒" else "🔓"

        val symbols = symbolsFor(category)
        adapter.submit(symbols)
    }

    private fun ensureTabsBuilt(isChineseMainMode: Boolean) {
        if (tabButtons.isNotEmpty()) {
            // Only update labels when main language changes
            // (Simple approach: always refresh texts; cost is tiny for 23 buttons)
            categoryDefs.forEach { def ->
                tabButtons[def.id]?.text = if (isChineseMainMode) def.labelZh else def.labelEn
            }
            return
        }

        tabContainer.removeAllViews()
        tabButtons.clear()

        val inflater = LayoutInflater.from(context)

        categoryDefs.forEach { def ->
            val v = inflater.inflate(R.layout.item_symbol_tab, tabContainer, false) as Button
            v.text = if (isChineseMainMode) def.labelZh else def.labelEn
            v.setOnClickListener { ime.setSymbolCategory(def.id) }
            tabContainer.addView(v)
            tabButtons[def.id] = v
        }
    }

    private fun updateTabSelected(selected: ImeActions.SymbolCategory) {
        tabButtons.forEach { (id, btn) ->
            val isSel = id == selected
            btn.setTextColor(if (isSel) Color.parseColor("#1A5DFF") else Color.parseColor("#222222"))
            btn.setBackgroundResource(
                if (isSel) R.drawable.bg_symbol_tab_selected else R.drawable.bg_symbol_tab_normal
            )
        }
    }

    private fun symbolsFor(category: ImeActions.SymbolCategory): List<String> {
        return when (category) {
            ImeActions.SymbolCategory.COMMON -> buildCommonSymbols()

            // 起步集合：后续会补充/替换为更完整的符号表
            ImeActions.SymbolCategory.CN -> CN_SYMBOLS
            ImeActions.SymbolCategory.EN -> EN_SYMBOLS

            ImeActions.SymbolCategory.WEB -> listOf("://", "www.", ".com", ".cn", ".net", ".org", "/", "?", "&", "=", "#", "%", "@")
            ImeActions.SymbolCategory.EMAIL -> listOf("@", ".", "_", "-", "+", "mailto:", ".com", ".cn", ".net", ".org")
            ImeActions.SymbolCategory.KAOMOJI -> listOf("(＾▽＾)", "(>_<)", "(╯°□°）╯︵ ┻━┻", "┬─┬ ノ( ゜-゜ノ)", "¯\\_(ツ)_/¯", "(•̀ᴗ•́)و", "(T_T)", "(｡•́︿•̀｡)")
            ImeActions.SymbolCategory.MATH -> listOf("±", "×", "÷", "≈", "≠", "≤", "≥", "∞", "√", "∑", "∏", "∫", "∂", "π", "∈", "∉", "∩", "∪")
            ImeActions.SymbolCategory.SUPER -> listOf("⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁺", "⁻", "⁼", "⁽", "⁾", "₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉")
            ImeActions.SymbolCategory.SERIAL -> listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩", "⑪", "⑫", "⑬", "⑭", "⑮", "㈠", "㈡", "㈢", "㈣", "㈤")
            ImeActions.SymbolCategory.IPA -> listOf("ɑ", "æ", "ə", "ɪ", "iː", "ʊ", "uː", "ɔː", "ʌ", "θ", "ð", "ʃ", "ʒ", "tʃ", "dʒ", "ŋ")
            ImeActions.SymbolCategory.JAPANESE -> listOf("あ", "い", "う", "え", "お", "ア", "イ", "ウ", "エ", "オ", "・", "ー", "「", "」", "。", "、")
            ImeActions.SymbolCategory.ARROWS -> listOf("←", "→", "↑", "↓", "↔", "↕", "⇐", "⇒", "⇑", "⇓", "⇔", "⟵", "⟶")
            ImeActions.SymbolCategory.SPECIAL -> listOf("©", "®", "™", "✓", "✗", "★", "☆", "♪", "♬", "☑", "☒", "☀", "☁", "☂")
            ImeActions.SymbolCategory.PINYIN -> listOf("ā", "á", "ǎ", "à", "ē", "é", "ě", "è", "ī", "í", "ǐ", "ì", "ō", "ó", "ǒ", "ò", "ū", "ú", "ǔ", "ù", "ǖ", "ǘ", "ǚ", "ǜ")
            ImeActions.SymbolCategory.ZHUYIN -> listOf("ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ", "ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ", "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ", "ㄧ", "ㄨ", "ㄩ")
            ImeActions.SymbolCategory.VERTICAL -> listOf("︐", "︑", "︒", "︓", "︔", "︕", "︖", "︗", "︘", "︙", "︰", "︱", "︳", "︴", "︵", "︶", "︷", "︸", "︹", "︺", "︽", "︾")
            ImeActions.SymbolCategory.RADICALS -> listOf("一", "丨", "丶", "丿", "乙", "亅", "二", "亠", "人", "儿", "入", "八", "冂", "冖", "冫", "几", "凵", "刀", "力", "勹")
            ImeActions.SymbolCategory.RUSSIAN -> listOf("А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ы", "Ь", "Э", "Ю", "Я")
            ImeActions.SymbolCategory.GREEK -> listOf("α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "σ", "τ", "υ", "φ", "χ", "ψ", "ω")
            ImeActions.SymbolCategory.LATIN -> listOf("À", "Á", "Â", "Ã", "Ä", "Å", "Æ", "Ç", "È", "É", "Ê", "Ë", "Ì", "Í", "Î", "Ï", "Ñ", "Ò", "Ó", "Ô", "Õ", "Ö", "Ø", "Ù", "Ú", "Û", "Ü", "Ý", "ß")
            ImeActions.SymbolCategory.BOX -> listOf("┌", "┬", "┐", "├", "┼", "┤", "└", "┴", "┘", "─", "│", "═", "║", "╔", "╦", "╗", "╠", "╬", "╣", "╚", "╩", "╝")
            ImeActions.SymbolCategory.SYLLABICS -> listOf("ᐁ", "ᐃ", "ᐅ", "ᐇ", "ᐉ", "ᐊ", "ᐋ", "ᐍ", "ᐏ", "ᐑ", "ᐓ", "ᐕ")
            ImeActions.SymbolCategory.TIBETAN -> listOf("ཀ", "ཁ", "ག", "ང", "ཅ", "ཆ", "ཇ", "ཉ", "ཏ", "ཐ", "ད", "ན", "པ", "ཕ", "བ", "མ", "ཙ", "ཚ", "ཛ", "ཝ", "ཞ", "ཟ", "འ", "ཡ", "ར", "ལ", "ཤ", "ས", "ཧ", "ཨ")
        }
    }

    private fun buildCommonSymbols(): List<String> {
        val mru = SymbolPrefs.loadMruCommon(context)
        if (mru.isEmpty()) return DEFAULT_COMMON_SYMBOLS

        val set = LinkedHashSet<String>(mru.size + DEFAULT_COMMON_SYMBOLS.size)
        for (s in mru) set.add(s)
        for (s in DEFAULT_COMMON_SYMBOLS) set.add(s)
        return set.toList()
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    private data class CategoryDef(
        val id: ImeActions.SymbolCategory,
        val labelZh: String,
        val labelEn: String
    )

    companion object {
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

private class GridSpacingDecoration(
    private val spanCount: Int,
    private val spacingPx: Int,
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount

        if (includeEdge) {
            outRect.left = spacingPx - column * spacingPx / spanCount
            outRect.right = (column + 1) * spacingPx / spanCount
            if (position < spanCount) outRect.top = spacingPx
            outRect.bottom = spacingPx
        } else {
            outRect.left = column * spacingPx / spanCount
            outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
            if (position >= spanCount) outRect.top = spacingPx
        }
    }
}
