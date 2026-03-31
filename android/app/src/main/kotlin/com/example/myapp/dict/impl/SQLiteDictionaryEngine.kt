package com.example.myapp.dict.impl

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

    // 预计算所有拼音的 T9 编码
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

    // 性能优化：getPinyinPossibilities 结果缓存
    private val pinyinPossibilityCache = LruCache<String, List<String>>(64)

    // getSuggestionsFromPinyinStack 结果缓存
    private val stackSuggestionsCache = LruCache<String, List<Candidate>>(32)

    fun invalidateStackCache() {
        stackSuggestionsCache.evictAll()
    }

    // ── 持久化数据库连接 ──────────────────────────────────────────
    //
    // 修复方案（彻底解决 30-60 秒卡顿）：
    //
    // 原问题根源：DictionaryDbHelper.onOpen() 中执行 PRAGMA journal_mode=WAL，
    // 该 PRAGMA 是写操作，会请求文件写锁。若 installer 后台线程正在向同一文件
    // 写入，SQLite busy handler 会等待（默认 30 秒超时），造成 T9 卡顿。
    //
    // 新方案：
    //  1. onOpen 中不再执行任何写操作（已在 DictionaryDbHelper 中移除）。
    //  2. setReady(true) 在 installer 后台线程调用，此时文件写入已完成。
    //     先通过 writableDatabase 设置 WAL + busy_timeout，然后关闭写连接，
    //     再打开只读连接供所有查询使用。
    //  3. 后续所有查询直接使用 _db（只读），无文件锁竞争，无额外 I/O。
    //
    // busy_timeout = 500ms：即使出现极端的偶发竞争，最多等 500ms 而非 30 秒。
    @Volatile
    private var _db: SQLiteDatabase? = null

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
        if (ready && _db == null) {
            try {
                // 步骤1：先用读写连接设置 WAL 和 busy_timeout。
                // 此时 installer 已完成，文件无写锁，操作迅速。
                // busy_timeout 500ms 作为防御性兜底，避免极端情况下的长时间阻塞。
                val rwDb = dbHelper.writableDatabase
                rwDb.execSQL("PRAGMA journal_mode=WAL")
                rwDb.execSQL("PRAGMA synchronous=NORMAL")
                rwDb.execSQL("PRAGMA busy_timeout=500")
                // 步骤2：WAL 设置完成后，打开只读连接供查询使用。
                // WAL 模式下读写可并发，只读连接不会阻塞未来的写操作。
                _db = dbHelper.readableDatabase
            } catch (_: Exception) {
                // 降级：如果读写连接失败（如 assets 词库本身已是 WAL），
                // 直接打开只读连接，功能不受影响，只是没有 busy_timeout 保护。
                try {
                    _db = dbHelper.readableDatabase
                } catch (_: Exception) {
                    // 词库文件损坏或未复制完成，保持 _db = null，查询会返回空列表
                }
            }
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

        // 性能优化：直接遍历 pinyinToT9Cache，避免重复调用 encodeLetters。
        // 原逻辑遍历 allPinyinsLower 再调 encodeLetters，现在直接用预计算缓存，
        // 过滤条件改为检查 code.startsWith 在已知前缀下更快。
        fun push(text: String, code: String) {
            if (!normalizedDigits.startsWith(code)) return
            if (!seen.add(text)) return

            val isInitialOnly = text == "zh" || text == "ch" || text == "sh"
            val isFullSyllable = analyzer.isFullSyllable(text) && !isInitialOnly
            val exactLenMatch = code.length == normalizedDigits.length

            val remainderDigits = normalizedDigits.substring(code.length)
            val remainderCanContinue = remainderDigits.isEmpty() || hasLikelyContinuation(remainderDigits)

            val score = buildPinyinPossibilityScore(
                text = text,
                code = code,
                exactLenMatch = exactLenMatch,
                isFullSyllable = isFullSyllable,
                isInitialOnly = isInitialOnly,
                remainderCanContinue = remainderCanContinue
            )

            out.add(
                Item(
                    text = text,
                    code = code,
                    exactLenMatch = exactLenMatch,
                    isFullSyllable = isFullSyllable,
                    isInitialOnly = isInitialOnly,
                    remainderCanContinue = remainderCanContinue,
                    score = score
                )
            )
        }

        // 直接遍历预计算的 pinyinToT9Cache，零额外 encodeLetters 调用
        for ((py, code) in pinyinToT9Cache) {
            push(py, code)
        }

        // 从当前数字的第一个字母取 fallback（单字母选项）
        val firstDigit = normalizedDigits.firstOrNull()
        if (firstDigit != null) {
            for (fallback in T9Lookup.charsFromDigit(firstDigit)) {
                val norm = fallback.trim().lowercase(Locale.ROOT)
                if (norm.isEmpty()) continue
                val code = T9Lookup.encodeLetters(norm)
                if (code.isNotEmpty()) push(norm, code)
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
