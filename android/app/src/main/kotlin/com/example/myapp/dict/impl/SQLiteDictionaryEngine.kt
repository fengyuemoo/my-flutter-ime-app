package com.example.myapp.dict.impl

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.db.DictionaryDbHelper
import com.example.myapp.dict.model.Candidate

class SQLiteDictionaryEngine(private val context: Context) : Dictionary {

    private val dbHelper = DictionaryDbHelper(context)
    private val queries = SQLiteWordQueries()

    private val allPinyins: List<String> = PinyinTable.allPinyins
    private val analyzer = PinyinInputAnalyzer(allPinyins)

    private val chineseApostropheSuggester = ChineseApostropheSuggester(
        analyzer = analyzer,
        queries = queries
    )

    private val chineseQwertySuggester = ChineseQwertySuggester(
        analyzer = analyzer,
        queries = queries
    )

    private val chineseT9Suggester = ChineseT9Suggester(
        queries = queries
    )

    private val englishSuggester = EnglishSuggester(
        queries = queries
    )

    @Volatile
    override var isLoaded: Boolean = false
        private set

    override var debugInfo: String? = "等待部署..."
        private set

    override fun setReady(ready: Boolean, info: String?) {
        isLoaded = ready
        if (info != null) debugInfo = info
    }

    /**
     * CN-T9 sidebar: “下一节”候选（单列滚动）。
     *
     * 规则：
     * - 完整拼音音节：仅允许 digits 以该音节的 T9 code 开头（严格不超过输入长度）
     * - 声母节：zh/ch/sh 也作为“完成一个节”（同样严格前缀）
     * - 单字母节：总是追加 digits[0] 对应的 a-z 小写字母（不输出大写/数字）
     */
    override fun getPinyinPossibilities(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()

        data class Item(val text: String, val code: String, val typeRank: Int)
        // typeRank: 0=完整音节, 1=声母(zh/ch/sh)

        val items = ArrayList<Item>()

        // 1) 完整音节：只允许 “digits.startsWith(t9Code)”
        for (pinyinRaw in allPinyins) {
            val pinyin = pinyinRaw.lowercase()
            val t9 = T9Lookup.encodeLetters(pinyin)
            if (t9.isNotEmpty() && digits.startsWith(t9)) {
                items.add(Item(text = pinyin, code = t9, typeRank = 0))
            }
        }

        // 2) 声母节：zh/ch/sh 也当作“完成一个节”
        val initials = listOf("zh", "ch", "sh")
        for (init in initials) {
            val code = T9Lookup.encodeLetters(init)
            if (code.isNotEmpty() && digits.startsWith(code)) {
                items.add(Item(text = init, code = code, typeRank = 1))
            }
        }

        // 排序：更长 code 优先（更具体），完整音节优先于声母，其次按字母序
        val sorted = items.sortedWith(
            compareBy<Item>(
                { -it.code.length },
                { it.typeRank },
                { it.text }
            )
        )

        val out = LinkedHashSet<String>()
        for (it in sorted) out.add(it.text)

        // 3) 单字母节：追加当前首位 digit 对应的小写字母（w/x/y/z, g/h/i ...）
        val firstDigit = digits[0]
        for (s0 in T9Lookup.charsFromDigit(firstDigit)) {
            val s = s0.lowercase().trim()
            if (s.length == 1 && s[0] in 'a'..'z') out.add(s)
        }

        return out.toList()
    }

    override fun getSuggestionsFromPinyinStack(pinyinStack: List<String>, rawDigits: String): List<Candidate> {
        if (!isLoaded) return emptyList()
        val db = dbHelper.readableDatabase

        val resultList = ArrayList<Candidate>()
        val seenWords = HashSet<String>()
        val limit = 300

        for (i in pinyinStack.size downTo 1) {
            val currentStack = pinyinStack.subList(0, i)
            val pinyinStr = currentStack.joinToString("").lowercase()

            val res = queries.queryDb(
                db = db,
                input = pinyinStr,
                isT9 = false,
                lang = 0,
                limit = if (i == pinyinStack.size) 80 else 50,
                offset = 0,
                matchedLen = pinyinStr.length,
                exactMatch = true,
                pinyinFilter = null
            )

            for (c in res) {
                if (i == 1 && c.word.length != 1) continue
                if (seenWords.add(c.word)) {
                    resultList.add(c.copy(pinyinCount = i))
                }
            }
            if (resultList.size >= limit) break
        }

        return resultList
    }

    override fun getSuggestions(input: String, isT9: Boolean, isChineseMode: Boolean): List<Candidate> {
        if (!isLoaded) return emptyList()

        val db = dbHelper.readableDatabase
        val norm = if (!isT9) input.lowercase() else input

        return when {
            !isChineseMode -> englishSuggester.suggest(db, norm, isT9)
            isT9 -> chineseT9Suggester.suggest(db, norm)
            norm.contains("'") -> chineseApostropheSuggester.suggest(db, norm)
            else -> chineseQwertySuggester.suggest(db, norm)
        }
    }
}
