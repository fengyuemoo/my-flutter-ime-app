package com.example.myapp.dict.impl

import android.content.Context
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.db.DictionaryDbHelper
import com.example.myapp.dict.model.Candidate
import java.util.Locale

class SQLiteDictionaryEngine(
    private val context: Context
) : Dictionary {

    private val dbHelper = DictionaryDbHelper(context)
    private val queries = SQLiteWordQueries()

    private val allPinyins: List<String> = PinyinTable.allPinyins
    private val allPinyinsLower: List<String> = allPinyins.map { it.lowercase(Locale.ROOT) }

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
        if (info != null) {
            debugInfo = info
        }
    }

    override fun getPinyinPossibilities(digits: String): List<String> {
        val normalizedDigits = digits.filter { it in '0'..'9' }
        if (normalizedDigits.isEmpty()) return emptyList()

        data class Item(
            val text: String,
            val code: String,
            val exactLenMatch: Boolean,
            val isFullSyllable: Boolean,
            val isInitialOnly: Boolean,
            val remainderCanContinue: Boolean,
            val score: Int
        )

        val out = ArrayList<Item>()
        val seen = HashSet<String>()

        fun push(text: String) {
            val normalized = normalizePinyinToken(text)
            if (normalized.isEmpty()) return

            val code = T9Lookup.encodeLetters(normalized)
            if (code.isEmpty()) return
            if (!normalizedDigits.startsWith(code)) return
            if (!seen.add(normalized)) return

            val isInitialOnly = normalized == "zh" || normalized == "ch" || normalized == "sh"
            val isFullSyllable = analyzer.isFullSyllable(normalized) && !isInitialOnly
            val exactLenMatch = code.length == normalizedDigits.length

            val remainderDigits = normalizedDigits.substring(code.length)
            val remainderCanContinue = when {
                remainderDigits.isEmpty() -> true
                else -> hasLikelyContinuation(remainderDigits)
            }

            val score = buildPinyinPossibilityScore(
                text = normalized,
                code = code,
                exactLenMatch = exactLenMatch,
                isFullSyllable = isFullSyllable,
                isInitialOnly = isInitialOnly,
                remainderCanContinue = remainderCanContinue
            )

            out.add(
                Item(
                    text = normalized,
                    code = code,
                    exactLenMatch = exactLenMatch,
                    isFullSyllable = isFullSyllable,
                    isInitialOnly = isInitialOnly,
                    remainderCanContinue = remainderCanContinue,
                    score = score
                )
            )
        }

        for (pinyin in allPinyinsLower) {
            push(pinyin)
        }

        for (initial in listOf("zh", "ch", "sh")) {
            push(initial)
        }

        val firstDigit = normalizedDigits.firstOrNull()
        if (firstDigit != null) {
            for (fallback in T9Lookup.charsFromDigit(firstDigit)) {
                push(fallback)
            }
        }

        return out
            .sortedWith(
                compareByDescending<Item> { it.score }
                    .thenByDescending { if (it.exactLenMatch) 1 else 0 }
                    .thenByDescending { if (it.isFullSyllable) 1 else 0 }
                    .thenByDescending { if (it.remainderCanContinue) 1 else 0 }
                    .thenByDescending { if (it.isInitialOnly) 1 else 0 }
                    .thenByDescending { it.code.length }
                    .thenByDescending { it.text.length }
                    .thenBy { it.text }
            )
            .map { it.text }
            .take(24)
    }

    override fun getSuggestionsFromPinyinStack(
        pinyinStack: List<String>,
        rawDigits: String
    ): List<Candidate> {
        if (!isLoaded) return emptyList()

        val normalizedStack = pinyinStack
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

        if (normalizedStack.isEmpty()) return emptyList()

        val db = dbHelper.readableDatabase
        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()
        val totalLimit = 300

        fun addUnique(list: List<Candidate>, forcedPinyinCount: Int) {
            for (cand in list) {
                if (!seen.add(cand.word)) continue
                result.add(
                    cand.copy(
                        pinyinCount = forcedPinyinCount.coerceAtLeast(1),
                        matchedLength = rawDigits.length
                    )
                )
                if (result.size >= totalLimit) return
            }
        }

        for (count in normalizedStack.size downTo 1) {
            if (result.size >= totalLimit) break

            val joined = normalizedStack.take(count).joinToString("")
            val list = queries.queryDb(
                db = db,
                input = joined,
                isT9 = false,
                lang = 0,
                limit = if (count == normalizedStack.size) 120 else 80,
                offset = 0,
                matchedLen = joined.length,
                exactMatch = true,
                pinyinFilter = null
            )

            addUnique(
                list = list,
                forcedPinyinCount = count
            )

            if (count == 1 && result.size < totalLimit) {
                addUnique(
                    list = queries.querySingleCharByInputExact(
                        db = db,
                        input = joined,
                        limit = 180
                    ),
                    forcedPinyinCount = 1
                )
            }
        }

        return result
    }

    override fun getSuggestions(
        input: String,
        isT9: Boolean,
        isChineseMode: Boolean
    ): List<Candidate> {
        if (!isLoaded) return emptyList()

        val db = dbHelper.readableDatabase
        val normalized = if (isT9) {
            input.filter { it in '0'..'9' }
        } else {
            input.trim().lowercase(Locale.ROOT)
        }

        if (normalized.isEmpty()) return emptyList()

        return when {
            !isChineseMode -> {
                englishSuggester.suggest(
                    db = db,
                    input = normalized,
                    isT9 = isT9
                )
            }

            isT9 -> {
                chineseT9Suggester.suggest(
                    db = db,
                    digits = normalized
                )
            }

            normalized.contains("'") -> {
                chineseApostropheSuggester.suggest(
                    db = db,
                    rawInputLower = normalized
                )
            }

            else -> {
                chineseQwertySuggester.suggest(
                    db = db,
                    normLower = normalized
                )
            }
        }
    }

    private fun buildPinyinPossibilityScore(
        text: String,
        code: String,
        exactLenMatch: Boolean,
        isFullSyllable: Boolean,
        isInitialOnly: Boolean,
        remainderCanContinue: Boolean
    ): Int {
        var score = 0

        score += code.length * 60
        score += text.length * 8

        if (exactLenMatch) score += 700
        if (isFullSyllable) score += 420
        if (isInitialOnly) score += 180
        if (remainderCanContinue) score += 160

        if (text.length == 1) score -= 260
        if (!isFullSyllable && !isInitialOnly && text.length <= 2) score -= 120

        return score
    }

    private fun hasLikelyContinuation(digits: String): Boolean {
        if (digits.isEmpty()) return true

        for (pinyin in allPinyinsLower) {
            val code = T9Lookup.encodeLetters(pinyin)
            if (code.isNotEmpty() && digits.startsWith(code)) {
                return true
            }
        }

        for (initial in listOf("zh", "ch", "sh")) {
            val code = T9Lookup.encodeLetters(initial)
            if (code.isNotEmpty() && digits.startsWith(code)) {
                return true
            }
        }

        val first = digits.firstOrNull() ?: return false
        return T9Lookup.charsFromDigit(first).isNotEmpty()
    }

    private fun normalizePinyinToken(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("ü", "v")
    }
}
