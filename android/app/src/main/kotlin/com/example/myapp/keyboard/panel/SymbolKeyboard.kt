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
import kotlin.math.min

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

        // ︿/﹀：改为翻页（由 dispatcher 维护 page 状态并回调 renderSymbolPanel）
        btnUp.setOnClickListener { ime.symbolPageUp() }
        btnDown.setOnClickListener { ime.symbolPageDown() }

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

        val full = symbolsFor(category)
        val pageSize = pageSizeFor(category)

        val totalPages = maxOf(1, (full.size + pageSize - 1) / pageSize)
        val effectivePage = page.coerceIn(0, totalPages - 1)

        val from = effectivePage * pageSize
        val to = min(full.size, from + pageSize)
        val pageList = if (from in 0..full.size && from < to) full.subList(from, to) else emptyList()

        val suffix = if (totalPages > 1) " ${effectivePage + 1}/$totalPages" else ""
        btnBack.text = if (isChineseMainMode) "返回$suffix" else "🔙$suffix"
        btnLock.text = if (locked) "🔒" else "🔓"

        val canUp = effectivePage > 0
        val canDown = effectivePage < totalPages - 1
        btnUp.isEnabled = canUp
        btnDown.isEnabled = canDown
        btnUp.alpha = if (canUp) 1.0f else 0.35f
        btnDown.alpha = if (canDown) 1.0f else 0.35f

        adapter.submit(pageList)
        recycler.scrollToPosition(0)
    }

    private fun pageSizeFor(category: ImeActions.SymbolCategory): Int {
        return when (category) {
            ImeActions.SymbolCategory.WEB -> 72
            ImeActions.SymbolCategory.EMAIL -> 72
            ImeActions.SymbolCategory.KAOMOJI -> 60
            ImeActions.SymbolCategory.MATH -> 72
            ImeActions.SymbolCategory.SPECIAL -> 84
            ImeActions.SymbolCategory.ARROWS -> 84
            ImeActions.SymbolCategory.BOX -> 84
            ImeActions.SymbolCategory.SERIAL -> 84
            ImeActions.SymbolCategory.VERTICAL -> 84
            ImeActions.SymbolCategory.RADICALS -> 84
            ImeActions.SymbolCategory.SYLLABICS -> 84
            ImeActions.SymbolCategory.TIBETAN -> 84
            ImeActions.SymbolCategory.JAPANESE -> 72
            ImeActions.SymbolCategory.IPA -> 84
            else -> 60
        }
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

            ImeActions.SymbolCategory.CN -> CN_SYMBOLS_FULL
            ImeActions.SymbolCategory.EN -> EN_SYMBOLS_FULL

            ImeActions.SymbolCategory.WEB -> WEB_SYMBOLS_FULL
            ImeActions.SymbolCategory.EMAIL -> EMAIL_SYMBOLS_FULL

            ImeActions.SymbolCategory.KAOMOJI -> KAOMOJI_COMMON

            ImeActions.SymbolCategory.MATH -> MATH_SYMBOLS_FULL
            ImeActions.SymbolCategory.SUPER -> SUPER_SUB_SYMBOLS_FULL
            ImeActions.SymbolCategory.SERIAL -> SERIAL_SYMBOLS_FULL

            ImeActions.SymbolCategory.IPA -> IPA_SYMBOLS_FULL
            ImeActions.SymbolCategory.JAPANESE -> JAPANESE_SYMBOLS_FULL

            ImeActions.SymbolCategory.ARROWS -> ARROW_SYMBOLS_FULL
            ImeActions.SymbolCategory.SPECIAL -> SPECIAL_SYMBOLS_FULL

            ImeActions.SymbolCategory.PINYIN -> PINYIN_SYMBOLS
            ImeActions.SymbolCategory.ZHUYIN -> ZHUYIN_SYMBOLS_FULL

            ImeActions.SymbolCategory.VERTICAL -> VERTICAL_PUNCT_FULL

            ImeActions.SymbolCategory.RADICALS -> RADICALS_GF0011_ALL_BY_STROKES

            ImeActions.SymbolCategory.RUSSIAN -> RUSSIAN_LETTERS
            ImeActions.SymbolCategory.GREEK -> GREEK_LETTERS
            ImeActions.SymbolCategory.LATIN -> LATIN_EXT_LETTERS

            ImeActions.SymbolCategory.BOX -> BOX_DRAWING_FULL
            ImeActions.SymbolCategory.SYLLABICS -> CANADIAN_SYLLABICS_FULL
            ImeActions.SymbolCategory.TIBETAN -> TIBETAN_CHARS_FULL
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

        // ---------------- Helpers ----------------

        private fun cpToString(cp: Int): String = String(Character.toChars(cp))

        private fun rangeToList(
            startInclusive: Int,
            endInclusive: Int,
            accept: (Int) -> Boolean = { true }
        ): List<String> {
            val out = ArrayList<String>(endInclusive - startInclusive + 1)
            var cp = startInclusive
            while (cp <= endInclusive) {
                if (Character.isDefined(cp) && !Character.isISOControl(cp) && accept(cp)) {
                    val t = Character.getType(cp)
                    if (t != Character.SURROGATE.toInt() && t != Character.PRIVATE_USE.toInt()) {
                        out.add(cpToString(cp))
                    }
                }
                cp++
            }
            return out
        }

        private fun uniq(vararg lists: List<String>): List<String> {
            val set = LinkedHashSet<String>()
            for (lst in lists) {
                for (s in lst) {
                    val v = s.trim()
                    if (v.isNotEmpty()) set.add(v)
                }
            }
            return set.toList()
        }

        private fun isMathOrSymbol(cp: Int): Boolean {
            val t = Character.getType(cp)
            return t == Character.MATH_SYMBOL.toInt() ||
                t == Character.OTHER_SYMBOL.toInt() ||
                t == Character.MODIFIER_SYMBOL.toInt()
        }

        private fun isLetter(cp: Int): Boolean = Character.isLetter(cp)

        // ---------------- Common / CN / EN ----------------

        private val DEFAULT_COMMON_SYMBOLS = listOf(
            ",", ".", "?", "!", "…", ":", ";", "\"",
            "'", "(", ")", "[", "]", "{", "}", "<",
            ">", "@", "#", "$", "%", "&", "+", "-",
            "*", "/", "=", "_", "\\", "|", "~", "^",
            "`", "，", "。", "？", "！", "、", "“", "”"
        )

        private val CN_SYMBOLS_FULL = uniq(
            listOf(
                "，", "。", "？", "！", "……", "：", "；", "、",
                "“", "”", "‘", "’",
                "（", "）", "《", "》", "〈", "〉",
                "【", "】", "「", "」", "『", "』",
                "—", "——", "·", "～", "…",
                "￥", "％", "＆", "＠", "＃", "＊", "＋", "－", "＝", "／", "＼", "｜"
            ),
            // 常用中文序号/括号变体（让中文类更“足”）
            listOf("（", "）", "［", "］", "｛", "｝", "〈", "〉", "《", "》", "「", "」", "『", "』")
        )

        private val EN_SYMBOLS_FULL = uniq(
            listOf(
                ",", ".", "?", "!", "...", ":", ";", "\"", "'",
                "(", ")", "[", "]", "{", "}", "<", ">",
                "@", "#", "$", "%", "&", "+", "-", "*", "/", "=", "_",
                "\\", "|", "~", "^", "`"
            ),
            listOf("—", "–", "…", "•", "·", "°", "‰", "§"),
            listOf("“", "”", "‘", "’")
        )

        // ---------------- Web (全面补齐：协议/域名/TLD/路径/查询/运算符) ----------------

        private val WEB_TLDS_COMMON = listOf(
            ".com", ".net", ".org", ".edu", ".gov", ".mil", ".info", ".biz",
            ".io", ".ai", ".dev", ".app", ".cloud", ".site", ".online", ".store", ".shop",
            ".top", ".xyz", ".link", ".live", ".pro", ".icu", ".me", ".cc", ".tv", ".co"
        )

        private val WEB_TLDS_COUNTRY = listOf(
            ".cn", ".com.cn", ".net.cn", ".org.cn",
            ".us", ".uk", ".jp", ".kr", ".de", ".fr", ".ru", ".in", ".br", ".au", ".ca",
            ".es", ".it", ".nl", ".se", ".no", ".fi", ".dk", ".ch", ".at", ".pl", ".cz",
            ".hk", ".tw", ".sg", ".my", ".id", ".th", ".vn"
        )

        private val WEB_SYMBOLS_FULL = uniq(
            // 协议/常见前缀
            listOf(
                "http://", "https://", "ftp://", "ftps://", "ws://", "wss://",
                "file://", "mailto:", "tel:", "sms:", "geo:",
                "://", "www.", "m.", "api.", "cdn.", "static."
            ),
            // 常见 TLD
            WEB_TLDS_COMMON,
            WEB_TLDS_COUNTRY,
            // URL 结构片段
            listOf(
                "/", "//", "/#", "#", "##",
                "?", "&", "&&", "=", "==", "!=",
                ":", ";", ".", "..", "...",
                "-", "_", "+", "~",
                "%", "%20", "%2F", "%3A", "%3F", "%26", "%3D", "%23",
                "@", ":", ":80", ":443",
                "()", "[]", "{}", "<>", "\"\"", "''"
            ),
            // 常见路径/锚点/参数名（覆盖 Web 实际输入常见）
            listOf(
                "/index.html", "/robots.txt", "/sitemap.xml", "/favicon.ico",
                "/login", "/logout", "/signup", "/register",
                "/api", "/v1", "/v2", "/docs", "/swagger", "/openapi.json",
                "/search", "/q", "/id", "/user", "/users",
                "utm_source=", "utm_medium=", "utm_campaign=", "utm_content=", "utm_term=",
                "ref=", "source=", "lang=", "locale=", "redirect=", "callback=", "returnUrl=",
                "page=", "size=", "limit=", "offset=", "sort=", "order=",
                "token=", "access_token=", "refresh_token=", "code=", "state="
            )
        )

        // ---------------- Email (全面补齐：常见域名/别名/头字段/符号) ----------------

        private val EMAIL_DOMAINS_COMMON = listOf(
            "@gmail.com", "@outlook.com", "@hotmail.com", "@live.com", "@msn.com",
            "@icloud.com", "@me.com",
            "@yahoo.com", "@yahoo.co.jp",
            "@proton.me", "@protonmail.com",
            "@yandex.com", "@yandex.ru",
            "@aol.com",
            "@qq.com", "@163.com", "@126.com", "@yeah.net", "@foxmail.com",
            "@sina.com", "@sohu.com", "@aliyun.com"
        )

        private val EMAIL_SYMBOLS_FULL = uniq(
            listOf(
                "@", ".", "_", "-", "+",
                "(", ")", "[", "]", "{", "}", "<", ">", "\"", "'",
                "mailto:", "noreply@", "no-reply@", "support@", "admin@", "service@", "info@", "hr@", "jobs@",
                "name+tag@", "user+tag@", "user.name@", "user_name@"
            ),
            // 顶级域（给自建域输入）
            listOf(".com", ".cn", ".net", ".org", ".edu", ".gov", ".io", ".ai", ".dev", ".app", ".me", ".co", ".cc", ".tv"),
            // 常见邮箱服务商
            EMAIL_DOMAINS_COMMON,
            // 邮件头字段（复制粘贴邮件/写 RFC 风格内容时常用）
            listOf(
                "To:", "Cc:", "Bcc:", "From:", "Reply-To:", "Subject:",
                "Date:", "Message-ID:", "In-Reply-To:", "References:",
                "Content-Type:", "MIME-Version:",
                "text/plain", "text/html", "charset=utf-8", "boundary="
            )
        )

        // ---------------- Kaomoji (A：通用精选，扩到更够用) ----------------

        private val KAOMOJI_COMMON = listOf(
            "(＾▽＾)", "(≧▽≦)", "(｡◕‿◕｡)", "(•‿•)", "(๑´ڡ`๑)", "(๑´ㅂ`๑)", "(•̀ᴗ•́)و", "(｡•̀ᴗ-)✧",
            "(づ｡◕‿‿◕｡)づ", "(づ￣ ³￣)づ", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "(✿◕‿◕✿)", "(★ω★)", "(≖‿≖✿)",
            "(〃▽〃)", "(⁄ ⁄•⁄ω⁄•⁄ ⁄)", "(*/ω＼*)", "(｡♥‿♥｡)", "(๑•́ ₃ •̀๑)",
            "(°_°)", "(O_O)", "(⊙_⊙)", "(ಠ_ಠ)", "(・_・;)", "(；´Д｀)", "(；￣Д￣)", "(；ω；)", "(´；ω；`)",
            "(╥﹏╥)", "(ಥ_ಥ)", "(T_T)", "(>_<)", "(｡•́︿•̀｡)", "(´・ω・`)", "(｀・ω・´)",
            "¯\\_(ツ)_/¯", "(￣▽￣)", "(￣^￣)ゞ", "(￣︶￣)ゞ", "(¬_¬)", "(•_•)", "(•̀ᴗ•́)و✧",
            "( ͡° ͜ʖ ͡°)", "(¬‿¬)", "(ง'̀-'́)ง", "(ง •̀_•́)ง", "(ง￣▽￣)ง",
            "(╬ಠ益ಠ)", "(ノಠ益ಠ)ノ", "(╯°□°）╯︵ ┻━┻", "(ノ°Д°)ノ︵ ┻━┻",
            "┬─┬ ノ( ゜-゜ノ)", "┬─┬ノ( º _ ºノ)",
            "(☞ﾟヮﾟ)☞", "☜(ﾟヮﾟ☜)", "(ง⌐■_■)ง", "(⌐■_■)",
            "♡", "❤", "♥", "ღ", "☆", "★", "♪"
        )

        // ---------------- Math / Super / Serial / Arrows / Special ----------------

        private val MATH_SYMBOLS_FULL: List<String> by lazy {
            val basic = listOf(
                "±", "×", "÷", "≈", "≠", "≤", "≥", "∞", "√",
                "∑", "∏", "∫", "∂", "π", "φ", "Ω", "μ",
                "∈", "∉", "∩", "∪", "⊂", "⊃", "⊆", "⊇",
                "⊕", "⊗", "⊥", "∥", "∠", "∴", "∵", "≡", "≅", "∝"
            )
            val ops = rangeToList(0x2200, 0x22FF) { isMathOrSymbol(it) }       // Mathematical Operators
            val supplement = rangeToList(0x2A00, 0x2AFF) { isMathOrSymbol(it) } // Supplemental Mathematical Operators
            val letterlike = rangeToList(0x2100, 0x214F) { isMathOrSymbol(it) } // Letterlike Symbols
            val miscA = rangeToList(0x27C0, 0x27EF) { isMathOrSymbol(it) }      // Misc Mathematical Symbols-A
            val miscB = rangeToList(0x2980, 0x29FF) { isMathOrSymbol(it) }      // Misc Mathematical Symbols-B
            uniq(basic, ops, supplement, letterlike, miscA, miscB)
        }

        private val SUPER_SUB_SYMBOLS_FULL: List<String> by lazy {
            val block = rangeToList(0x2070, 0x209F) { cp ->
                val t = Character.getType(cp)
                t != Character.FORMAT.toInt() && !Character.isWhitespace(cp)
            }
            uniq(
                listOf("⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁺", "⁻", "⁼", "⁽", "⁾"),
                listOf("₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉", "₊", "₋", "₌", "₍", "₎"),
                block
            )
        }

        private val SERIAL_SYMBOLS_FULL: List<String> by lazy {
            val enclosed = rangeToList(0x2460, 0x24FF) { Character.isDefined(it) } // Enclosed Alphanumerics
            val cjkEnclosed = rangeToList(0x3200, 0x32FF) { Character.isDefined(it) } // Enclosed CJK Letters and Months
            val roman = rangeToList(0x2160, 0x2188) { Character.isDefined(it) } // Roman numerals
            uniq(
                listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩", "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳"),
                enclosed,
                cjkEnclosed,
                roman
            )
        }

        private val ARROW_SYMBOLS_FULL: List<String> by lazy {
            val basic = rangeToList(0x2190, 0x21FF) { isMathOrSymbol(it) } // Arrows
            val supplementA = rangeToList(0x27F0, 0x27FF) { isMathOrSymbol(it) } // Supplemental Arrows-A
            val supplementB = rangeToList(0x2900, 0x297F) { isMathOrSymbol(it) } // Supplemental Arrows-B
            val misc = rangeToList(0x2B00, 0x2BFF) { isMathOrSymbol(it) } // Misc Symbols and Arrows（包含很多箭头）
            uniq(listOf("↩", "↪", "↺", "↻", "⬅", "➡", "⬆", "⬇"), basic, supplementA, supplementB, misc)
        }

        private val SPECIAL_SYMBOLS_FULL: List<String> by lazy {
            val currency = rangeToList(0x20A0, 0x20CF) { isMathOrSymbol(it) } // Currency Symbols
            val miscSymbols = rangeToList(0x2600, 0x26FF) { isMathOrSymbol(it) } // Misc Symbols
            val dingbats = rangeToList(0x2700, 0x27BF) { isMathOrSymbol(it) } // Dingbats
            val geom = rangeToList(0x25A0, 0x25FF) { isMathOrSymbol(it) } // Geometric Shapes
            val miscTech = rangeToList(0x2300, 0x23FF) { isMathOrSymbol(it) } // Misc Technical
            uniq(
                listOf("©", "®", "™", "✓", "✗", "★", "☆", "♪", "♬", "•", "°", "‰", "§"),
                currency,
                miscSymbols,
                dingbats,
                geom,
                miscTech
            )
        }

        // ---------------- IPA / Japanese / Zhuyin / Vertical ----------------

        private val IPA_SYMBOLS_BASE = listOf(
            "i", "iː", "ɪ", "e", "eɪ", "ɛ", "æ", "ɑ", "ɑː", "ɒ", "ɔ", "ɔː", "oʊ", "ʊ", "u", "uː", "ʌ", "ə", "ɜː",
            "p", "b", "t", "d", "k", "g", "f", "v", "θ", "ð", "s", "z", "ʃ", "ʒ", "h", "m", "n", "ŋ", "l", "r", "j", "w",
            "tʃ", "dʒ",
            "ˈ", "ˌ", "ː", "ˑ", "̆", "̃", "̩", "̯", "ʔ"
        )

        private val IPA_SYMBOLS_FULL: List<String> by lazy {
            // IPA Extensions 0250–02AF + Spacing Modifier Letters 02B0–02FF（过滤掉组合附加符）
            val ipaExt = rangeToList(0x0250, 0x02AF) { cp ->
                val t = Character.getType(cp)
                (t == Character.LOWERCASE_LETTER.toInt() || t == Character.MODIFIER_LETTER.toInt() || t == Character.OTHER_LETTER.toInt())
            }
            val modifiers = rangeToList(0x02B0, 0x02FF) { cp ->
                val t = Character.getType(cp)
                t == Character.MODIFIER_LETTER.toInt() || t == Character.MODIFIER_SYMBOL.toInt()
            }
            uniq(IPA_SYMBOLS_BASE, ipaExt, modifiers)
        }

        private val JAPANESE_SYMBOLS_BASE = listOf(
            "。", "、", "・", "ー", "「", "」", "『", "』", "（", "）", "！", "？", "～",
            "あ", "い", "う", "え", "お",
            "か", "き", "く", "け", "こ",
            "さ", "し", "す", "せ", "そ",
            "た", "ち", "つ", "て", "と",
            "な", "に", "ぬ", "ね", "の",
            "は", "ひ", "ふ", "へ", "ほ",
            "ま", "み", "む", "め", "も",
            "や", "ゆ", "よ",
            "ら", "り", "る", "れ", "ろ",
            "わ", "を", "ん",
            "が", "ぎ", "ぐ", "げ", "ご",
            "ざ", "じ", "ず", "ぜ", "ぞ",
            "だ", "ぢ", "づ", "で", "ど",
            "ば", "び", "ぶ", "べ", "ぼ",
            "ぱ", "ぴ", "ぷ", "ぺ", "ぽ",
            "ぁ", "ぃ", "ぅ", "ぇ", "ぉ", "ゃ", "ゅ", "ょ", "っ",
            "ア", "イ", "ウ", "エ", "オ",
            "カ", "キ", "ク", "ケ", "コ",
            "サ", "シ", "ス", "セ", "ソ",
            "タ", "チ", "ツ", "テ", "ト",
            "ナ", "ニ", "ヌ", "ネ", "ノ",
            "ハ", "ヒ", "フ", "ヘ", "ホ",
            "マ", "ミ", "ム", "メ", "モ",
            "ヤ", "ユ", "ヨ",
            "ラ", "リ", "ル", "レ", "ロ",
            "ワ", "ヲ", "ン",
            "ガ", "ギ", "グ", "ゲ", "ゴ",
            "ザ", "ジ", "ズ", "ゼ", "ゾ",
            "ダ", "ヂ", "ヅ", "デ", "ド",
            "バ", "ビ", "ブ", "ベ", "ボ",
            "パ", "ピ", "プ", "ペ", "ポ",
            "ァ", "ィ", "ゥ", "ェ", "ォ", "ャ", "ュ", "ョ", "ッ"
        )

        private val JAPANESE_SYMBOLS_FULL: List<String> by lazy {
            // Halfwidth and Fullwidth Forms（含半角片假名与部分日文符号）
            val halfwidthKana = rangeToList(0xFF61, 0xFF9F) { Character.isDefined(it) }
            // CJK Symbols and Punctuation（含日文常见符号如「」等，补充一些）
            val cjkPunct = rangeToList(0x3000, 0x303F) { Character.isDefined(it) }
            uniq(JAPANESE_SYMBOLS_BASE, halfwidthKana, cjkPunct)
        }

        private val PINYIN_SYMBOLS = listOf(
            "ā", "á", "ǎ", "à",
            "ē", "é", "ě", "è",
            "ī", "í", "ǐ", "ì",
            "ō", "ó", "ǒ", "ò",
            "ū", "ú", "ǔ", "ù",
            "ǖ", "ǘ", "ǚ", "ǜ"
        )

        private val ZHUYIN_SYMBOLS_BASE = listOf(
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

        private val ZHUYIN_SYMBOLS_FULL: List<String> by lazy {
            val bopomofo = rangeToList(0x3100, 0x312F) { Character.isDefined(it) }
            val bopomofoExt = rangeToList(0x31A0, 0x31BF) { Character.isDefined(it) } // Bopomofo Extended
            uniq(ZHUYIN_SYMBOLS_BASE, bopomofo, bopomofoExt)
        }

        private val VERTICAL_PUNCT_FULL: List<String> by lazy {
            // Vertical Forms FE10–FE1F + CJK Compatibility Forms FE30–FE4F
            val verticalForms = rangeToList(0xFE10, 0xFE1F) { Character.isDefined(it) }
            val cjkCompatForms = rangeToList(0xFE30, 0xFE4F) { Character.isDefined(it) }
            uniq(verticalForms, cjkCompatForms)
        }

        // ---------------- Russian / Greek / Latin ----------------

        private val RUSSIAN_LETTERS: List<String> by lazy {
            val upper = rangeToList(0x0410, 0x042F) { isLetter(it) } // А..Я
            val lower = rangeToList(0x0430, 0x044F) { isLetter(it) } // а..я
            uniq(listOf("Ё", "ё"), upper, lower)
        }

        private val GREEK_LETTERS: List<String> by lazy {
            val upper = rangeToList(0x0391, 0x03A9) { isLetter(it) }
            val lower = rangeToList(0x03B1, 0x03C9) { isLetter(it) }
            val extended = rangeToList(0x1F00, 0x1FFF) { isLetter(it) } // Greek Extended
            uniq(upper, lower, listOf("ς"), extended)
        }

        private val LATIN_EXT_LETTERS: List<String> by lazy {
            val latin1 = rangeToList(0x00C0, 0x00FF) { isLetter(it) } // Latin-1 Supplement letters
            val extA = rangeToList(0x0100, 0x017F) { isLetter(it) } // Latin Extended-A
            val extB = rangeToList(0x0180, 0x024F) { isLetter(it) } // Latin Extended-B
            uniq(latin1, extA, extB, listOf("ß"))
        }

        // ---------------- Box / Syllabics / Tibetan ----------------

        private val BOX_DRAWING_FULL: List<String> by lazy {
            val box = rangeToList(0x2500, 0x257F) { isMathOrSymbol(it) } // Box Drawing
            val block = rangeToList(0x2580, 0x259F) { isMathOrSymbol(it) } // Block Elements
            val geom = rangeToList(0x25A0, 0x25FF) { isMathOrSymbol(it) } // Geometric Shapes（补充）
            uniq(
                listOf("┌", "┬", "┐", "├", "┼", "┤", "└", "┴", "┘", "─", "│", "═", "║", "╔", "╦", "╗", "╠", "╬", "╣", "╚", "╩", "╝"),
                box, block, geom
            )
        }

        private val CANADIAN_SYLLABICS_FULL: List<String> by lazy {
            val base = rangeToList(0x1400, 0x167F) { isLetter(it) } // Canadian Aboriginal Syllabics
            val ext = rangeToList(0x18B0, 0x18FF) { isLetter(it) } // Canadian Aboriginal Syllabics Extended
            uniq(base, ext)
        }

        private val TIBETAN_CHARS_FULL: List<String> by lazy {
            val letters = rangeToList(0x0F40, 0x0F6C) { isLetter(it) }
            val digits = rangeToList(0x0F20, 0x0F29) { Character.isDigit(it) }
            val punct = listOf("་", "།", "༄", "༅", "༔", "༴", "༺", "༻", "༼", "༽")
            uniq(punct, digits, letters)
        }

        // ---------------- Radical list (keep your existing implementation) ----------------

        private data class StrokeGroup(val strokes: Int, val items: List<String>)

        private val MAIN_GROUPS: List<StrokeGroup> = listOf(
            StrokeGroup(1, listOf("一", "丨", "丿", "丶", "乛")),
            StrokeGroup(2, listOf("十", "厂", "匚", "卜", "冂", "八", "人", "勹", "儿", "匕", "几", "亠", "冫", "冖", "凵", "卩", "刀", "力", "又", "厶", "廴")),
            StrokeGroup(3, listOf("干", "工", "土", "艹", "寸", "廾", "大", "尢", "弋", "小", "口", "囗", "山", "巾", "彳", "彡", "夕", "夂", "丬", "广", "门", "宀", "辶", "彐", "尸", "己", "弓", "子", "屮", "女", "飞", "马", "么", "巛")),
            StrokeGroup(4, listOf("王", "无", "韦", "木", "支", "犬", "歹", "车", "牙", "戈", "比", "瓦", "止", "攴", "日", "贝", "水", "见", "牛", "手", "气", "毛", "长", "片", "斤", "爪", "父", "月", "氏", "欠", "风", "殳", "文", "方", "火", "斗", "户", "心", "毋", "示", "甘", "石", "龙", "业", "目", "田", "罒", "皿", "生", "矢", "禾", "白", "瓜", "鸟", "疒", "立", "穴", "疋", "皮", "癶", "矛")),
            StrokeGroup(6, listOf("耒", "老", "耳", "臣", "覀", "而", "页", "至", "虍", "虫", "肉", "缶", "舌", "竹", "臼", "自", "血", "舟", "色", "齐", "衣", "羊", "米", "聿", "艮", "羽", "糸")),
            StrokeGroup(7, listOf("麦", "走", "赤", "豆", "酉", "辰", "豕", "卤", "里", "足", "邑", "身", "釆", "谷", "豸", "龟", "角", "言", "辛")),
            StrokeGroup(8, listOf("青", "龺", "雨", "非", "齿", "黾", "隹", "阜", "金", "鱼", "隶")),
            StrokeGroup(9, listOf("革", "面", "韭", "骨", "香", "鬼", "食", "音", "首")),
            StrokeGroup(10, listOf("髟", "鬲", "鬥", "高")),
            StrokeGroup(11, listOf("黄", "麻", "鹿")),
            StrokeGroup(12, listOf("鼎", "黑", "黍")),
            StrokeGroup(13, listOf("鼓", "鼠")),
            StrokeGroup(14, listOf("鼻")),
            StrokeGroup(17, listOf("龠"))
        )

        private val MAIN_ORDER: Map<String, Int> by lazy {
            val m = HashMap<String, Int>(256)
            var i = 0
            for (g in MAIN_GROUPS) for (r in g.items) {
                m[r] = i
                i++
            }
            m
        }

        private val VARIANTS_RAW: List<String> = listOf(
            "亅",
            "𠂆", "⺊", "⺆", "丷", "亻", "入", "𠘨", "㔾", "刂", "⺈",
            "士", "艸", "兀", "尣", "⺌", "爿", "門", "辵", "⺕", "彑", "已", "巳", "飛", "馬",
            "玉", "旡", "韋", "朩", "犭", "歺", "車", "攵", "⺜", "曰", "貝", "氵", "氺", "見", "牜", "扌", "龵", "镸",
            "長", "爫", "⺝", "風", "灬", "忄", "⺗", "母", "礻", "龍", "鳥", "𤴔",
            "耂", "襾", "西", "頁", "虎", "𥫗", "𦥑", "齊", "衤", "⺶", "𦍌", "肀", "⺻", "纟", "糹",
            "麥", "鹵", "𧾷", "⻏", "龜", "讠",
            "齒", "黽", "⻖", "钅", "魚",
            "饣", "飠"
        )

        private val VARIANT_SELF_STROKES: Map<String, Int> = linkedMapOf(
            "亅" to 1,

            "𠂆" to 2, "⺊" to 2, "⺆" to 2, "丷" to 2, "亻" to 2, "入" to 2, "𠘨" to 2, "㔾" to 2, "刂" to 2, "⺈" to 2,

            "士" to 3, "艸" to 3, "兀" to 3, "尣" to 3, "⺌" to 3, "爿" to 3, "門" to 3, "辵" to 3, "⺕" to 3, "彑" to 3,
            "已" to 3, "巳" to 3, "飛" to 3, "馬" to 3,

            "玉" to 4, "旡" to 4, "韋" to 4, "朩" to 4,
            "犭" to 3, "歺" to 4, "車" to 4, "攵" to 4, "⺜" to 4, "曰" to 4, "貝" to 4,
            "氵" to 3, "氺" to 4, "見" to 4, "牜" to 4, "扌" to 3, "龵" to 4, "镸" to 4,
            "長" to 4, "爫" to 4, "⺝" to 4, "風" to 4, "灬" to 4, "忄" to 3, "⺗" to 4, "母" to 4,
            "礻" to 4, "龍" to 4, "鳥" to 4, "𤴔" to 4,

            "耂" to 6, "襾" to 6, "西" to 6, "頁" to 6, "虎" to 6, "𥫗" to 6, "𦥑" to 6, "齊" to 6, "衤" to 6,
            "⺶" to 6, "𦍌" to 6, "肀" to 6, "⺻" to 6,
            "纟" to 3, "糹" to 6,

            "麥" to 7, "鹵" to 7, "𧾷" to 7,
            "⻏" to 2, "龜" to 7, "讠" to 2,

            "齒" to 8, "黽" to 8, "⻖" to 2, "钅" to 5, "魚" to 8,

            "饣" to 3, "飠" to 9
        )

        private val VARIANT_ORDER: Map<String, Int> by lazy {
            val m = HashMap<String, Int>(128)
            for ((i, r) in VARIANTS_RAW.withIndex()) m[r] = i
            m
        }

        private data class SortKey(val strokes: Int, val typeRank: Int, val orderKey: Int)

        private val VARIANTS_BY_SELF_STROKES: List<String> by lazy {
            require(VARIANTS_RAW.all { VARIANT_SELF_STROKES.containsKey(it) }) {
                "VARIANT_SELF_STROKES missing keys"
            }
            VARIANTS_RAW.sortedWith(
                compareBy(
                    { VARIANT_SELF_STROKES[it] ?: Int.MAX_VALUE },
                    { VARIANT_ORDER[it] ?: Int.MAX_VALUE }
                )
            )
        }

        val RADICALS_GF0011_ALL_BY_STROKES: List<String> by lazy {
            val all = ArrayList<Pair<String, SortKey>>(201 + VARIANTS_RAW.size)

            for (g in MAIN_GROUPS) {
                for (r in g.items) {
                    all.add(r to SortKey(strokes = g.strokes, typeRank = 0, orderKey = MAIN_ORDER[r] ?: 0))
                }
            }

            for (r in VARIANTS_BY_SELF_STROKES) {
                val s = VARIANT_SELF_STROKES[r] ?: Int.MAX_VALUE
                all.add(r to SortKey(strokes = s, typeRank = 1, orderKey = VARIANT_ORDER[r] ?: 0))
            }

            all.sortedWith(compareBy({ it.second.strokes }, { it.second.typeRank }, { it.second.orderKey }))
                .map { it.first }
        }
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
