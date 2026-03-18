package com.example.myapp.dict.impl

import android.content.Context
import android.util.LruCache
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.db.DictionaryDbHelper
import com.example.myapp.dict.model.Candidate
import java.util.Locale
import kotlin.math.abs

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

    // ── 性能优化：预计算所有拼音的 T9 编码 ──────────────────────────
    private val pinyinToT9Cache: Map<String, String> by lazy {
        val map = HashMap<String, String>(allPinyinsLower.size + 10)
        for (py in allPinyinsLower) {
            val code = T9Lookup.encodeLetters(py)
            if (code.isNotEmpty()) map[py] = code
        }
        for (initial in listOf("zh", "ch", "sh")) {
            val code = T9Lookup.encodeLetters(initial)
            if (code.isNotEmpty()) map[initial] = code
        }
        map
    }

    // ── 性能优化：getPinyinPossibilities 结果缓存 ────────────────────
    private val pinyinPossibilityCache = LruCache<String, List<String>>(64)

    // ── 性能优化：getSuggestionsFromPinyinStack 结果缓存 ─────────────
    private val stackSuggestionsCache = LruCache<String, List<Candidate>>(32)

    fun invalidateStackCache() {
        stackSuggestionsCache.evictAll()
    }

    // ── 修复：持久化数据库连接 ──────────────────────────────────────
    // 原实现在每个查询方法里调用 dbHelper.readableDatabase，
    // SQLiteOpenHelper 首次调用时会同步执行文件 I/O，
    // 若恰逢 DictionaryInstaller 正在向同一文件写入，则会等待文件锁，
    // 导致在 Dispatchers.Default 协程里阻塞长达 30-60 秒。
    //
    // 修复方案：setReady(true) 在 installer 后台线程调用，此时文件写入已完成，
    // 可以安全地一次性打开连接并持久化。后续所有查询直接使用 _db，零额外开销。
    @Volatile
    private var _db: android.database.sqlite.SQLiteDatabase? = null

    private data class ScoredStackCandidate(
        val candidate: Candidate,
        val sourceRank: Int,
        val exactInput: Boolean,
        val prefixInput: Boolean,
        val exactT9WithRaw: Boolean,
        val prefixT9WithRaw: Boolean,
        val pinyinCountMatched: Boolean,
        val syllableDistance: Int,
        val score: Int
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
        // 修复：installer 完成后（此处仍在后台线程）立即预热连接。
        // readableDatabase 此时不再竞争文件锁，调用安全且迅速。
        // 后续所有查询直接复用 _db，主线程和协程均不再触发额外 I/O。
        if (ready && _db == null) {
            _db = dbHelper.readableDatabase
        }
    }

    override fun getPinyinPossibilities(digits: String): List<String> {
        val normalizedDigits = digits.filter { it in '0'..'9' }
        if (normalizedDigits.isEmpty()) return emptyList()

        pinyinPossibilityCache.get(normalizedDigits)?.let { return it }

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

            val code = pinyinToT9Cache[normalized] ?: T9Lookup.encodeLetters(normalized)
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

        val result = out
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

        pinyinPossibilityCache.put(normalizedDigits, result)
        return result
    }

    override fun getSuggestionsFromPinyinStack(
        pinyinStack: List<String>,
        rawDigits: String
    ): List<Candidate> {
        if (!isLoaded) return emptyList()

        // 修复：使用持久连接 _db，若尚未就绪则安全返回空列表
        val db = _db ?: return emptyList()

        val normalizedStack = pinyinStack
            .map { normalizePinyinToken(it) }
            .filter { it.isNotEmpty() }

        if (normalizedStack.isEmpty()) return emptyList()

        val normalizedRawDigits = rawDigits.filter { it in '0'..'9' }
        val cacheKey = normalizedStack.joinToString("'") + "|" + normalizedRawDigits

        stackSuggestionsCache.get(cacheKey)?.let { return it }

        val result = LinkedHashMap<String, ScoredStackCandidate>(300)
        val totalLimit = 300

        val t9EncodeCache = HashMap<String, String>(64)

        fun pushAll(
            list: List<Candidate>,
            sourceRank: Int,
            forcedPinyinCount: Int
        ) {
            for (cand in list) {
                val scored = scoreStackCandidate(
                    cand = cand,
                    stack = normalizedStack,
                    rawDigits = normalizedRawDigits,
                    sourceRank = sourceRank,
                    forcedPinyinCount = forcedPinyinCount,
                    t9EncodeCache = t9EncodeCache
                )

                val existing = result[scored.candidate.word]
                if (existing == null || isBetter(scored, existing)) {
                    result[scored.candidate.word] = scored
                }

                if (result.size >= totalLimit) return
            }
        }

        for (count in normalizedStack.size downTo 1) {
            val joined = normalizedStack.take(count).joinToString("")

            val exactList = queries.queryDb(
                db = db,
                input = joined,
                isT9 = false,
                lang = 0,
                limit = if (count == normalizedStack.size) 140 else 90,
                offset = 0,
                matchedLen = joined.length,
                exactMatch = true,
                pinyinFilter = null
            )
            pushAll(
                list = exactList,
                sourceRank = (normalizedStack.size - count) * 10,
                forcedPinyinCount = count
            )

            val prefixList = queries.queryDb(
                db = db,
                input = joined,
                isT9 = false,
                lang = 0,
                limit = if (count == normalizedStack.size) 120 else 70,
                offset = 0,
                matchedLen = joined.length,
                exactMatch = false,
                pinyinFilter = joined
            )
            pushAll(
                list = prefixList,
                sourceRank = (normalizedStack.size - count) * 10 + 1,
                forcedPinyinCount = count
            )

            if (count == 1 && result.size < totalLimit) {
                val exactSingle = queries.querySingleCharByInputExact(
                    db = db,
                    input = joined,
                    limit = 180
                )
                pushAll(
                    list = exactSingle,
                    sourceRank = 100,
                    forcedPinyinCount = 1
                )

                val prefixSingle = queries.querySingleCharByInputPrefix(
                    db = db,
                    prefix = joined
                )
                pushAll(
                    list = prefixSingle,
                    sourceRank = 101,
                    forcedPinyinCount = 1
                )
            }

            if (result.size >= totalLimit) break
        }

        val sorted = result.values
            .sortedWith(
                compareByDescending<ScoredStackCandidate> { it.score }
                    .thenByDescending { if (it.exactInput) 1 else 0 }
                    .thenByDescending { if (it.prefixInput) 1 else 0 }
                    .thenByDescending { if (it.exactT9WithRaw) 1 else 0 }
                    .thenByDescending { if (it.prefixT9WithRaw) 1 else 0 }
                    .thenByDescending { if (it.pinyinCountMatched) 1 else 0 }
                    .thenBy { it.syllableDistance }
                    .thenBy { it.sourceRank }
                    .thenByDescending { it.candidate.priority }
                    .thenByDescending { it.candidate.word.length }
                    .thenByDescending { it.candidate.syllables }
                    .thenBy { it.candidate.word }
            )
            .map { it.candidate }
            .take(totalLimit)

        stackSuggestionsCache.put(cacheKey, sorted)
        return sorted
    }

    override fun getSuggestions(
        input: String,
        isT9: Boolean,
        isChineseMode: Boolean
    ): List<Candidate> {
        if (!isLoaded) return emptyList()

        // 修复：使用持久连接 _db，若尚未就绪则安全返回空列表
        val db = _db ?: return emptyList()

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

    override fun querySingleCharsWithPinyinPrefix(prefix: String): List<Candidate> {
        if (!isLoaded) return emptyList()
        val norm = prefix.trim().lowercase(Locale.ROOT)
        if (norm.isEmpty()) return emptyList()
        // 修复：使用持久连接 _db
        val db = _db ?: return emptyList()
        return queries.querySingleCharByInputPrefix(db = db, prefix = norm).take(20)
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────

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

        for ((_, code) in pinyinToT9Cache) {
            if (code.isNotEmpty() && digits.startsWith(code)) {
                return true
            }
        }

        val first = digits.firstOrNull() ?: return false
        return T9Lookup.charsFromDigit(first).isNotEmpty()
    }

    private fun scoreStackCandidate(
        cand: Candidate,
        stack: List<String>,
        rawDigits: String,
        sourceRank: Int,
        forcedPinyinCount: Int,
        t9EncodeCache: HashMap<String, String> = HashMap()
    ): ScoredStackCandidate {
        val normalizedInput = normalizePinyinConcat(cand.pinyin ?: cand.input)
        val stackConcat = normalizePinyinConcat(stack.joinToString(""))
        val rawT9 = rawDigits

        val candT9 = t9EncodeCache.getOrPut(normalizedInput) {
            T9Lookup.encodeLetters(normalizedInput)
        }

        val exactInput = normalizedInput.isNotEmpty() && normalizedInput == stackConcat
        val prefixInput = normalizedInput.isNotEmpty() &&
            (normalizedInput.startsWith(stackConcat) || stackConcat.startsWith(normalizedInput))

        val exactT9WithRaw = rawT9.isNotEmpty() && candT9 == rawT9
        val prefixT9WithRaw = rawT9.isNotEmpty() &&
            (candT9.startsWith(rawT9) || rawT9.startsWith(candT9))

        val resolvedSyllables = resolveSyllables(cand, normalizedInput)
        val stackSize = stack.size
        val effectivePinyinCount = forcedPinyinCount
            .coerceAtLeast(1)
            .coerceAtMost(stackSize)

        val syllableDistance = abs(resolvedSyllables - effectivePinyinCount)
        val pinyinCountMatched = syllableDistance == 0

        val sourceBonus = when {
            sourceRank == 0 -> 5000
            sourceRank == 1 -> 3600
            sourceRank < 20 -> 2500 - sourceRank * 40
            else -> 700 - sourceRank * 3
        }

        val exactInputBonus = if (exactInput) 1400 else 0
        val prefixInputBonus = if (prefixInput) 620 else 0
        val exactT9Bonus = if (exactT9WithRaw) 320 else 0
        val prefixT9Bonus = if (prefixT9WithRaw) 140 else 0
        val pinyinCountBonus = if (pinyinCountMatched) 260 else 0
        val phraseBonus = if (cand.word.length > 1) 220 else 0
        val charBonus = cand.word.length * 26
        val freqBonus = cand.priority.coerceAtLeast(0) / 1000

        val syllablePenalty = syllableDistance * 90
        val shorterThanStackPenalty =
            if (resolvedSyllables < effectivePinyinCount) (effectivePinyinCount - resolvedSyllables) * 120 else 0

        val score = sourceBonus +
            exactInputBonus +
            prefixInputBonus +
            exactT9Bonus +
            prefixT9Bonus +
            pinyinCountBonus +
            phraseBonus +
            charBonus +
            freqBonus -
            syllablePenalty -
            shorterThanStackPenalty

        val normalizedCandidate = cand.copy(
            matchedLength = rawDigits.length,
            pinyinCount = effectivePinyinCount,
            pinyin = cand.pinyin ?: cand.input
        )

        return ScoredStackCandidate(
            candidate = normalizedCandidate,
            sourceRank = sourceRank,
            exactInput = exactInput,
            prefixInput = prefixInput,
            exactT9WithRaw = exactT9WithRaw,
            prefixT9WithRaw = prefixT9WithRaw,
            pinyinCountMatched = pinyinCountMatched,
            syllableDistance = syllableDistance,
            score = score
        )
    }

    private fun isBetter(
        a: ScoredStackCandidate,
        b: ScoredStackCandidate
    ): Boolean {
        return when {
            a.score != b.score -> a.score > b.score
            a.exactInput != b.exactInput -> a.exactInput
            a.prefixInput != b.prefixInput -> a.prefixInput
            a.exactT9WithRaw != b.exactT9WithRaw -> a.exactT9WithRaw
            a.prefixT9WithRaw != b.prefixT9WithRaw -> a.prefixT9WithRaw
            a.pinyinCountMatched != b.pinyinCountMatched -> a.pinyinCountMatched
            a.syllableDistance != b.syllableDistance -> a.syllableDistance < b.syllableDistance
            a.sourceRank != b.sourceRank -> a.sourceRank < b.sourceRank
            a.candidate.priority != b.candidate.priority -> a.candidate.priority > b.candidate.priority
            a.candidate.word.length != b.candidate.word.length -> a.candidate.word.length > b.candidate.word.length
            else -> a.candidate.word < b.candidate.word
        }
    }

    private fun resolveSyllables(cand: Candidate, normalizedPinyin: String): Int {
        if (cand.syllables > 0) return cand.syllables

        val splitByApostrophe = normalizedPinyin
            .split("'")
            .map { it.trim() }
            .count { it.isNotEmpty() }

        if (splitByApostrophe > 0) return splitByApostrophe

        val parsed = analyzer.splitConcatPinyinToSyllables(
            normalizedPinyin.replace("'", "")
        )
        if (parsed.isNotEmpty()) return parsed.size

        return 1
    }

    private fun normalizePinyinConcat(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("ü", "v")
            .replace("'", "")
    }

    private fun normalizePinyinToken(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("ü", "v")
    }
}
