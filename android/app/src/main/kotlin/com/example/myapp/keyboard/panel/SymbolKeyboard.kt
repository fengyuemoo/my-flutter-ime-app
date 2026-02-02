package com.example.myapp.keyboard.panel

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.SymbolPanelUi
import com.example.myapp.ime.prefs.SymbolPrefs
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.PanelMode
import com.example.myapp.keyboard.core.RawCommitMode
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent

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

    private val tabButtons: MutableMap<ImeActions.SymbolCategory, Button> = LinkedHashMap()

    init {
        val lm = FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
            alignItems = AlignItems.FLEX_START
        }
        recycler.layoutManager = lm
        recycler.adapter = adapter
        recycler.addItemDecoration(SimpleSpaceDecoration(dpToPx(6)))

        btnBack.setOnClickListener { ime.closeSymbolPanel() }
        btnUp.setOnClickListener { recycler.smoothScrollToPosition(0) }
        btnDown.setOnClickListener { recycler.smoothScrollBy(0, recycler.height) }
        btnLock.setOnClickListener { ime.toggleSymbolLock() }
    }

    override fun onActivate() {}

    override fun handleKeyPress(button: Button) {}

    override fun renderSymbolPanel(
        category: ImeActions.SymbolCategory,
        page: Int,
        locked: Boolean,
        isChineseMainMode: Boolean
    ) {
        ensureTabsBuilt(isChineseMainMode)
        updateTabSelected(category)

        btnBack.text = if (isChineseMainMode) "返回" else "🔙"
        btnLock.text = if (locked) "🔒" else "🔓"

        adapter.submit(symbolsFor(category))
    }

    private fun ensureTabsBuilt(isChineseMainMode: Boolean) {
        if (tabButtons.isNotEmpty()) {
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
        val selColor = ContextCompat.getColor(context, R.color.ime_accent)
        val normalColor = ContextCompat.getColor(context, R.color.ime_key_text)

        tabButtons.forEach { (id, btn) ->
            val isSel = id == selected
            btn.setTextColor(if (isSel) selColor else normalColor)
            btn.setBackgroundResource(
                if (isSel) R.drawable.bg_symbol_tab_selected else R.drawable.bg_symbol_tab_normal
            )
        }
    }

    private fun symbolsFor(category: ImeActions.SymbolCategory): List<String> {
        return when (category) {
            ImeActions.SymbolCategory.COMMON -> buildCommonSymbols()

            ImeActions.SymbolCategory.CN -> CN_SYMBOLS
            ImeActions.SymbolCategory.EN -> EN_SYMBOLS

            ImeActions.SymbolCategory.WEB -> listOf(
                "://", "www.", ".com", ".cn", ".net", ".org", ".io", ".dev",
                "/", "?", "&", "=", "#", "%", "@", "~"
            )

            ImeActions.SymbolCategory.EMAIL -> listOf(
                "@", ".", "_", "-", "+",
                ".com", ".cn", ".net", ".org", ".edu",
                "mailto:"
            )

            ImeActions.SymbolCategory.KAOMOJI -> listOf(
                "(＾▽＾)", "(>_<)", "(T_T)", "(•̀ᴗ•́)و", "(｡•́︿•̀｡)",
                "¯\\_(ツ)_/¯", "(╯°□°）╯︵ ┻━┻", "┬─┬ ノ( ゜-゜ノ)"
            )

            ImeActions.SymbolCategory.MATH -> listOf(
                "±", "×", "÷", "≈", "≠", "≤", "≥", "∞", "√",
                "∑", "∏", "∫", "∂", "π",
                "∈", "∉", "∩", "∪", "⊂", "⊃", "⊆", "⊇"
            )

            ImeActions.SymbolCategory.SUPER -> listOf(
                "⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹",
                "⁺", "⁻", "⁼", "⁽", "⁾",
                "₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉"
            )

            ImeActions.SymbolCategory.SERIAL -> listOf(
                "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
                "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
                "㈠", "㈡", "㈢", "㈣", "㈤", "㈥", "㈦", "㈧", "㈨", "㈩"
            )

            ImeActions.SymbolCategory.IPA -> IPA_SYMBOLS
            ImeActions.SymbolCategory.JAPANESE -> JAPANESE_SYMBOLS

            ImeActions.SymbolCategory.ARROWS -> listOf(
                "←", "→", "↑", "↓", "↔", "↕",
                "⇐", "⇒", "⇑", "⇓", "⇔",
                "⟵", "⟶", "⟷", "↩", "↪"
            )

            ImeActions.SymbolCategory.SPECIAL -> listOf(
                "©", "®", "™", "✓", "✗", "★", "☆", "♪", "♬",
                "☑", "☒", "☀", "☁", "☂", "☕", "☎", "✈"
            )

            ImeActions.SymbolCategory.PINYIN -> listOf(
                "ā", "á", "ǎ", "à",
                "ē", "é", "ě", "è",
                "ī", "í", "ǐ", "ì",
                "ō", "ó", "ǒ", "ò",
                "ū", "ú", "ǔ", "ù",
                "ǖ", "ǘ", "ǚ", "ǜ"
            )

            ImeActions.SymbolCategory.ZHUYIN -> listOf(
                "ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ",
                "ㄍ", "ㄎ", "ㄏ",
                "ㄐ", "ㄑ", "ㄒ",
                "ㄓ", "ㄔ", "ㄕ", "ㄖ",
                "ㄗ", "ㄘ", "ㄙ",
                "ㄧ", "ㄨ", "ㄩ",
                "ㄚ", "ㄛ", "ㄜ", "ㄝ",
                "ㄞ", "ㄟ", "ㄠ", "ㄡ",
                "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ",
                "ˉ", "ˊ", "ˇ", "ˋ", "˙"
            )

            ImeActions.SymbolCategory.VERTICAL -> listOf(
                "︐", "︑", "︒", "︓", "︔", "︕", "︖",
                "︗", "︘", "︙", "︰", "︱", "︲", "︳",
                "︴", "︵", "︶", "︷", "︸", "︹", "︺",
                "︽", "︾"
            )

            // GF 0011-2009：按笔画数升序（同笔画内保持原顺序），主 201 + 附形 99
            ImeActions.SymbolCategory.RADICALS -> RADICALS_GF0011_ALL_BY_STROKES

            ImeActions.SymbolCategory.RUSSIAN -> listOf(
                "А","Б","В","Г","Д","Е","Ё","Ж","З","И","Й","К","Л","М","Н","О","П",
                "Р","С","Т","У","Ф","Х","Ц","Ч","Ш","Щ","Ъ","Ы","Ь","Э","Ю","Я"
            )

            ImeActions.SymbolCategory.GREEK -> listOf(
                "α","β","γ","δ","ε","ζ","η","θ","ι","κ","λ","μ","ν","ξ","ο","π","ρ","σ","τ","υ","φ","χ","ψ","ω"
            )

            ImeActions.SymbolCategory.LATIN -> listOf(
                "À","Á","Â","Ã","Ä","Å","Æ","Ç",
                "È","É","Ê","Ë",
                "Ì","Í","Î","Ï",
                "Ñ",
                "Ò","Ó","Ô","Õ","Ö","Ø",
                "Ù","Ú","Û","Ü",
                "Ý","ß"
            )

            ImeActions.SymbolCategory.BOX -> listOf(
                "┌","┬","┐","├","┼","┤","└","┴","┘",
                "─","│",
                "═","║","╔","╦","╗","╠","╬","╣","╚","╩","╝"
            )

            ImeActions.SymbolCategory.SYLLABICS -> listOf("ᐁ","ᐃ","ᐅ","ᐇ","ᐉ","ᐊ","ᐋ","ᐍ","ᐏ","ᐑ","ᐓ","ᐕ")

            ImeActions.SymbolCategory.TIBETAN -> listOf(
                "ཀ","ཁ","ག","ང","ཅ","ཆ","ཇ","ཉ","ཏ","ཐ","ད","ན",
                "པ","ཕ","བ","མ","ཙ","ཚ","ཛ","ཝ","ཞ","ཟ","འ","ཡ","ར","ལ","ཤ","ས","ཧ","ཨ"
            )
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

        private val JAPANESE_SYMBOLS = listOf(
            "。","、","・","ー","「","」","『","』","（","）","！","？","～",
            "あ","い","う","え","お",
            "か","き","く","け","こ",
            "さ","し","す","せ","そ",
            "た","ち","つ","て","と",
            "な","に","ぬ","ね","の",
            "は","ひ","ふ","へ","ほ",
            "ま","み","む","め","も",
            "や","ゆ","よ",
            "ら","り","る","れ","ろ",
            "わ","を","ん",
            "が","ぎ","ぐ","げ","ご",
            "ざ","じ","ず","ぜ","ぞ",
            "だ","ぢ","づ","で","ど",
            "ば","び","ぶ","べ","ぼ",
            "ぱ","ぴ","ぷ","ぺ","ぽ",
            "ぁ","ぃ","ぅ","ぇ","ぉ","ゃ","ゅ","ょ","っ",
            "ア","イ","ウ","エ","オ",
            "カ","キ","ク","ケ","コ",
            "サ","シ","ス","セ","ソ",
            "タ","チ","ツ","テ","ト",
            "ナ","ニ","ヌ","ネ","ノ",
            "ハ","ヒ","フ","ヘ","ホ",
            "マ","ミ","ム","メ","モ",
            "ヤ","ユ","ヨ",
            "ラ","リ","ル","レ","ロ",
            "ワ","ヲ","ン",
            "ガ","ギ","グ","ゲ","ゴ",
            "ザ","ジ","ズ","ゼ","ゾ",
            "ダ","ヂ","ヅ","デ","ド",
            "バ","ビ","ブ","ベ","ボ",
            "パ","ピ","プ","ペ","ポ",
            "ァ","ィ","ゥ","ェ","ォ","ャ","ュ","ョ","ッ"
        )

        private val IPA_SYMBOLS = listOf(
            "i","iː","ɪ","e","eɪ","ɛ","æ","ɑ","ɑː","ɒ","ɔ","ɔː","oʊ","ʊ","u","uː","ʌ","ə","ɜː",
            "p","b","t","d","k","g","f","v","θ","ð","s","z","ʃ","ʒ","h","m","n","ŋ","l","r","j","w",
            "tʃ","dʒ",
            "ˈ","ˌ","ː","ˑ","̆","̃","̩","̯","ʔ"
        )

        // GF 0011-2009：按笔画数升序；同笔画内保持原表顺序
        private val RADICALS_GF0011_MAIN_201_BY_STROKES = listOf(
            "一","丨","丿","丶","乛",
            "十","厂","匚","卜","冂","八","人","勹","儿","匕","几","亠","冫","冖","凵","卩","刀","力","又","厶","廴",
            "干","工","土","艹","寸","廾","大","尢","弋","小","口","囗","山","巾","彳","彡","夕","夂","丬","广","门","宀","辶","彐","尸","己","弓","子","屮","女","飞","马","么","巛",
            "王","无","韦","木","支","犬","歹","车","牙","戈","比","瓦","止","攴","日","贝","水","见","牛","手","气","毛","长","片","斤","爪","父","月","氏","欠","风","殳","文","方","火","斗","户","心","毋","示","甘","石","龙","业","目","田","罒","皿","生","矢","禾","白","瓜","鸟","疒","立","穴","疋","皮","癶","矛",
            "耒","老","耳","臣","覀","而","页","至","虍","虫","肉","缶","舌","竹","臼","自","血","舟","色","齐","衣","羊","米","聿","艮","羽","糸",
            "麦","走","赤","豆","酉","辰","豕","卤","里","足","邑","身","釆","谷","豸","龟","角","言","辛",
            "青","龺","雨","非","齿","黾","隹","阜","金","鱼","隶",
            "革","面","韭","骨","香","鬼","食","音","首",
            "髟","鬲","鬥","高",
            "黄","麻","鹿",
            "鼎","黑","黍",
            "鼓","鼠",
            "鼻",
            "龠"
        )

        private val RADICALS_GF0011_VARIANTS_99_BY_STROKES = listOf(
            "亅",
            "𠂆","⺊","⺆","丷","亻","入","𠘨","㔾","刂","⺈",
            "士","艸","兀","尣","⺌","爿","門","辵","⺕","彑","已","巳","飛","馬",
            "玉","旡","韋","朩","犭","歺","車","攵","⺜","曰","貝","氵","氺","見","牜","扌","龵","镸","長","爫","⺝","風","灬","忄","⺗","母","礻","龍","鳥","𤴔",
            "耂","襾","西","頁","虎","𥫗","𦥑","齊","衤","⺶","𦍌","肀","⺻","纟","糹",
            "麥","鹵","𧾷","⻏","龜","讠",
            "齒","黽","⻖","钅","魚",
            "饣","飠"
        )

        val RADICALS_GF0011_ALL_BY_STROKES: List<String> =
            RADICALS_GF0011_MAIN_201_BY_STROKES + RADICALS_GF0011_VARIANTS_99_BY_STROKES
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

private class SimpleSpaceDecoration(
    private val spacingPx: Int
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.left = spacingPx / 2
        outRect.right = spacingPx / 2
        outRect.top = spacingPx / 2
        outRect.bottom = spacingPx / 2
    }
}
