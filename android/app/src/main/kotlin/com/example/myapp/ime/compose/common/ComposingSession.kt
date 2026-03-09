package com.example.myapp.ime.compose.common

import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import java.util.Locale

class ComposingSession {

    data class T9MaterializedSegmentSnapshot(
        val syllable: String,
        val digitChunk: String,
        val localCuts: List<Int>,
        val locked: Boolean
    )

    private val _pinyinStack = ArrayList<String>()

    // 与 _pinyinStack 对齐：记录每个已物化拼音段实际消费掉的 digits 前缀
    private val _t9DigitsStack = ArrayList<String>()

    // 与 _pinyinStack 对齐：记录每个已物化拼音段消费时被移除的 cuts（相对该段自身的局部坐标）
    private val _t9CutsStack = ArrayList<List<Int>>()

    private var _rawT9Digits = ""
    private var _qwertyInput = ""
    private var _committedPrefix = ""

    // CN-T9: 手动切分点（位置是“rawT9Digits 的 index”，1..len-1 为内部切分点；len 表示“尾部待生效切分点”）
    private val _t9ManualCuts = HashSet<Int>()

    val pinyinStack: List<String> get() = _pinyinStack
    val rawT9Digits: String get() = _rawT9Digits
    val qwertyInput: String get() = _qwertyInput
    val committedPrefix: String get() = _committedPrefix

    val t9ManualCuts: List<Int>
        get() = _t9ManualCuts.toList().sorted()

    val t9MaterializedSegments: List<T9MaterializedSegmentSnapshot>
        get() = _pinyinStack.indices.map { index ->
            T9MaterializedSegmentSnapshot(
                syllable = _pinyinStack.getOrNull(index).orEmpty(),
                digitChunk = _t9DigitsStack.getOrNull(index).orEmpty(),
                localCuts = _t9CutsStack.getOrNull(index)?.sorted() ?: emptyList(),
                locked = true
            )
        }

    private sealed class PickRecord {
        data class Qwerty(
            val word: String,
            val consumedPrefix: String
        ) : PickRecord()

        data class Apostrophe(
            val word: String,
            val consumedParts: List<String>
        ) : PickRecord()

        data class T9(
            val word: String,
            val consumedPinyins: List<String>,
            val consumedDigitChunks: List<String>,
            val consumedCutChunks: List<List<Int>>,
            val restorePinyinCountOnUndo: Int
        ) : PickRecord()

        data class T9Digits(
            val word: String,
            val consumedDigits: String,
            val consumedCuts: List<Int>
        ) : PickRecord()
    }

    private val pickHistory = ArrayList<PickRecord>()

    sealed class PickResult {
        data class Commit(val text: String) : PickResult()
        object Updated : PickResult()
    }

    fun clear() {
        _pinyinStack.clear()
        _t9DigitsStack.clear()
        _t9CutsStack.clear()
        _rawT9Digits = ""
        _qwertyInput = ""
        _committedPrefix = ""
        _t9ManualCuts.clear()
        pickHistory.clear()
    }

    fun isComposing(): Boolean {
        return _committedPrefix.isNotEmpty() ||
            _pinyinStack.isNotEmpty() ||
            _rawT9Digits.isNotEmpty() ||
            _qwertyInput.isNotEmpty()
    }

    fun appendQwerty(text: String) {
        if (text.isEmpty()) return
        _qwertyInput += text.lowercase(Locale.ROOT)
    }

    fun appendT9Digit(digit: String) {
        if (digit.isEmpty()) return
        _rawT9Digits += digit.filter { it in '0'..'9' }
    }

    fun insertT9ManualCutAtEnd() {
        val len = _rawT9Digits.length
        if (len <= 0) return
        _t9ManualCuts.add(len)
    }

    private fun trimCutsToLength(newLen: Int) {
        if (_t9ManualCuts.isEmpty()) return
        val it = _t9ManualCuts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p > newLen) {
                it.remove()
            }
        }
    }

    private fun consumeDigitsPrefixForCuts(len: Int): List<Int> {
        if (len <= 0 || _t9ManualCuts.isEmpty()) return emptyList()

        val removed = ArrayList<Int>()
        val remain = HashSet<Int>(_t9ManualCuts.size)

        for (p in _t9ManualCuts) {
            if (p <= len) {
                removed.add(p)
            } else {
                remain.add(p - len)
            }
        }

        _t9ManualCuts.clear()
        _t9ManualCuts.addAll(remain)
        removed.sort()
        return removed
    }

    private fun restoreCutsOnPrepend(prefixLen: Int, restoredCuts: List<Int>) {
        if (prefixLen <= 0 && restoredCuts.isEmpty()) return

        val shifted = HashSet<Int>(_t9ManualCuts.size + restoredCuts.size)

        for (p in _t9ManualCuts) {
            shifted.add(p + prefixLen)
        }
        for (p in restoredCuts) {
            if (p in 1..prefixLen) {
                shifted.add(p)
            }
        }

        _t9ManualCuts.clear()
        _t9ManualCuts.addAll(shifted)
    }

    private fun flattenCutChunks(
        digitChunks: List<String>,
        cutChunks: List<List<Int>>
    ): List<Int> {
        if (digitChunks.isEmpty() || cutChunks.isEmpty()) return emptyList()

        val out = ArrayList<Int>()
        var offset = 0

        val n = minOf(digitChunks.size, cutChunks.size)
        for (i in 0 until n) {
            val digits = digitChunks[i]
            val cuts = cutChunks[i]
            for (c in cuts) {
                if (c in 1..digits.length) {
                    out.add(offset + c)
                }
            }
            offset += digits.length
        }

        out.sort()
        return out
    }

    fun onPinyinSidebarClick(pinyin: String, t9Code: String) {
        val normalizedPinyin = pinyin.trim().lowercase(Locale.ROOT)
        if (normalizedPinyin.isEmpty()) return

        val normalizedCode = t9Code.filter { it in '0'..'9' }
        val consume = normalizedCode.length.coerceAtLeast(0).coerceAtMost(_rawT9Digits.length)
        val consumedDigits = if (consume > 0) _rawT9Digits.substring(0, consume) else ""
        val consumedCuts = if (consume > 0) consumeDigitsPrefixForCuts(consume) else emptyList()

        _pinyinStack.add(normalizedPinyin)
        _t9DigitsStack.add(consumedDigits)
        _t9CutsStack.add(consumedCuts)

        _rawT9Digits =
            if (_rawT9Digits.length >= consume) _rawT9Digits.substring(consume) else ""
        trimCutsToLength(_rawT9Digits.length)
    }

    private fun repLetter(d: Char): String {
        val list = T9Lookup.charsFromDigit(d)
        return if (list.isNotEmpty()) list[0] else "?"
    }

    private fun buildT9DisplayFallbackSuffix(): String {
        if (_rawT9Digits.isEmpty()) return ""

        if (_pinyinStack.isNotEmpty()) {
            return ""
        }

        if (_rawT9Digits.length == 1) {
            return repLetter(_rawT9Digits[0])
        }

        return ""
    }

    fun displayText(useT9Layout: Boolean): String? {
        if (!isComposing()) return null

        if (!useT9Layout) {
            return buildString {
                append(_committedPrefix)
                append(_qwertyInput)
            }
        }

        val stackUi = _pinyinStack.joinToString("'") { it.lowercase(Locale.ROOT) }
        val fallbackUi = buildT9DisplayFallbackSuffix()

        val text = buildString {
            append(_committedPrefix)
            if (stackUi.isNotEmpty()) {
                append(stackUi)
            } else if (fallbackUi.isNotEmpty()) {
                append(fallbackUi)
            }
        }

        return text.takeIf { it.isNotEmpty() }
    }

    fun backspace(useT9Layout: Boolean): Boolean {
        if (_committedPrefix.isNotEmpty()) {
            undoLastPick()
            return true
        }

        if (!isComposing()) return false

        if (!useT9Layout) {
            if (_qwertyInput.isNotEmpty()) {
                _qwertyInput = _qwertyInput.dropLast(1)
            }
            return true
        }

        return backspaceT9ByDigitGranularity()
    }

    private fun backspaceT9ByDigitGranularity(): Boolean {
        if (_rawT9Digits.isNotEmpty()) {
            deleteLastRawT9Digit()
            return true
        }

        if (_pinyinStack.isNotEmpty()) {
            restoreLastMaterializedT9Segment()
            return true
        }

        return true
    }

    private fun deleteLastRawT9Digit() {
        _rawT9Digits = _rawT9Digits.dropLast(1)
        trimCutsToLength(_rawT9Digits.length)
    }

    private fun restoreLastMaterializedT9Segment() {
        undoLastSidebarSelection()
    }

    private fun undoLastSidebarSelection() {
        if (_pinyinStack.isEmpty()) return

        val idx = _pinyinStack.lastIndex
        _pinyinStack.removeAt(idx)

        val restoredDigits = if (idx in _t9DigitsStack.indices) {
            _t9DigitsStack.removeAt(idx)
        } else {
            ""
        }

        val restoredCuts = if (idx in _t9CutsStack.indices) {
            _t9CutsStack.removeAt(idx)
        } else {
            emptyList()
        }

        if (restoredDigits.isNotEmpty()) {
            _rawT9Digits = restoredDigits + _rawT9Digits
            restoreCutsOnPrepend(restoredDigits.length, restoredCuts)
            trimCutsToLength(_rawT9Digits.length)
        }
    }

    fun backspaceMaterializedSegmentTailDigit(index: Int): Boolean {
        if (index !in _pinyinStack.indices) return false

        val targetDigits = _t9DigitsStack.getOrNull(index).orEmpty()
        if (targetDigits.isEmpty()) return false

        val targetCuts = _t9CutsStack.getOrNull(index)?.sorted() ?: emptyList()

        val shortenedTargetDigits = targetDigits.dropLast(1)
        val shortenedTargetCuts = targetCuts.filter { it in 1..shortenedTargetDigits.length }

        val suffixDigitChunks = if (index + 1 < _t9DigitsStack.size) {
            _t9DigitsStack.subList(index + 1, _t9DigitsStack.size).toList()
        } else {
            emptyList()
        }

        val suffixCutChunks = if (index + 1 < _t9CutsStack.size) {
            _t9CutsStack.subList(index + 1, _t9CutsStack.size).map { it.sorted() }
        } else {
            emptyList()
        }

        val restoredDigitChunks = ArrayList<String>()
        val restoredCutChunks = ArrayList<List<Int>>()

        if (shortenedTargetDigits.isNotEmpty()) {
            restoredDigitChunks.add(shortenedTargetDigits)
            restoredCutChunks.add(shortenedTargetCuts)
        }

        restoredDigitChunks.addAll(suffixDigitChunks)
        restoredCutChunks.addAll(suffixCutChunks)

        while (_pinyinStack.size > index) {
            _pinyinStack.removeAt(_pinyinStack.lastIndex)
            _t9DigitsStack.removeAt(_t9DigitsStack.lastIndex)
            _t9CutsStack.removeAt(_t9CutsStack.lastIndex)
        }

        val restoredDigits = restoredDigitChunks.joinToString("")
        if (restoredDigits.isNotEmpty()) {
            _rawT9Digits = restoredDigits + _rawT9Digits
            val restoredCuts = flattenCutChunks(
                digitChunks = restoredDigitChunks,
                cutChunks = restoredCutChunks
            )
            restoreCutsOnPrepend(restoredDigits.length, restoredCuts)
        }

        trimCutsToLength(_rawT9Digits.length)
        return true
    }

    fun rollbackMaterializedSegmentsFrom(index: Int): Boolean {
        if (index !in _pinyinStack.indices) return false

        while (_pinyinStack.size > index) {
            undoLastSidebarSelection()
        }
        return true
    }

    fun pickCandidate(
        cand: Candidate,
        useT9Layout: Boolean,
        isChinese: Boolean,
        restorePinyinCountOnUndo: Int = -1,
        t9ConsumedDigitsCount: Int = -1
    ): PickResult {
        if (useT9Layout) {
            _committedPrefix += cand.word

            val consumePinyin = cand.pinyinCount
                .coerceAtLeast(0)
                .coerceAtMost(_pinyinStack.size)

            if (consumePinyin > 0) {
                val consumedPinyins = _pinyinStack.take(consumePinyin)
                val consumedDigitChunks = _t9DigitsStack.take(consumePinyin)
                val consumedCutChunks = _t9CutsStack.take(consumePinyin)

                val restoreCount = if (restorePinyinCountOnUndo >= 0) {
                    restorePinyinCountOnUndo.coerceIn(0, consumePinyin)
                } else {
                    consumePinyin
                }

                pickHistory.add(
                    PickRecord.T9(
                        word = cand.word,
                        consumedPinyins = consumedPinyins,
                        consumedDigitChunks = consumedDigitChunks,
                        consumedCutChunks = consumedCutChunks,
                        restorePinyinCountOnUndo = restoreCount
                    )
                )

                repeat(consumePinyin) {
                    if (_pinyinStack.isNotEmpty()) _pinyinStack.removeAt(0)
                    if (_t9DigitsStack.isNotEmpty()) _t9DigitsStack.removeAt(0)
                    if (_t9CutsStack.isNotEmpty()) _t9CutsStack.removeAt(0)
                }

                return if (_pinyinStack.isEmpty() && _rawT9Digits.isEmpty()) {
                    PickResult.Commit(_committedPrefix)
                } else {
                    PickResult.Updated
                }
            }

            if (_rawT9Digits.isEmpty()) {
                return PickResult.Commit(_committedPrefix)
            }

            val explicitConsumeDigits = t9ConsumedDigitsCount
                .coerceAtMost(_rawT9Digits.length)

            val consumeDigits = when {
                explicitConsumeDigits > 0 -> explicitConsumeDigits
                else -> cand.input.length
                    .coerceAtLeast(1)
                    .coerceAtMost(_rawT9Digits.length)
            }

            val consumedDigits = _rawT9Digits.substring(0, consumeDigits)
            val consumedCuts = consumeDigitsPrefixForCuts(consumeDigits)

            _rawT9Digits = _rawT9Digits.substring(consumeDigits)
            trimCutsToLength(_rawT9Digits.length)

            pickHistory.add(
                PickRecord.T9Digits(
                    word = cand.word,
                    consumedDigits = consumedDigits,
                    consumedCuts = consumedCuts
                )
            )

            return if (_pinyinStack.isEmpty() && _rawT9Digits.isEmpty()) {
                PickResult.Commit(_committedPrefix)
            } else {
                PickResult.Updated
            }
        }

        val inputStr = _qwertyInput

        if (isChinese && inputStr.contains("'")) {
            val parts = inputStr
                .split("'")
                .map { it.trim().lowercase(Locale.ROOT) }

            val nonEmptyParts = parts.filter { it.isNotEmpty() }
            val consume = cand.input.length
                .coerceAtLeast(1)
                .coerceAtMost(nonEmptyParts.size)

            val consumedParts = nonEmptyParts.take(consume)
            val remainParts = nonEmptyParts.drop(consume)

            _committedPrefix += cand.word
            pickHistory.add(PickRecord.Apostrophe(cand.word, consumedParts))

            _qwertyInput = remainParts.joinToString("'")

            return if (remainParts.isEmpty()) {
                PickResult.Commit(_committedPrefix)
            } else {
                PickResult.Updated
            }
        }

        val consume = cand.input.length
            .coerceAtLeast(1)
            .coerceAtMost(inputStr.length)

        val consumedPrefix = inputStr.substring(0, consume)
        val remain = inputStr.substring(consume)

        _committedPrefix += cand.word
        pickHistory.add(PickRecord.Qwerty(cand.word, consumedPrefix))

        _qwertyInput = remain

        return if (_qwertyInput.isEmpty()) {
            PickResult.Commit(_committedPrefix)
        } else {
            PickResult.Updated
        }
    }

    private fun undoLastPick() {
        if (pickHistory.isEmpty()) {
            _committedPrefix = ""
            return
        }

        val last = pickHistory.removeAt(pickHistory.size - 1)
        val lastWord = lastWord(last)

        _committedPrefix = if (_committedPrefix.length >= lastWord.length) {
            _committedPrefix.dropLast(lastWord.length)
        } else {
            ""
        }

        when (last) {
            is PickRecord.Qwerty -> {
                _qwertyInput = last.consumedPrefix + _qwertyInput
            }

            is PickRecord.Apostrophe -> {
                val remainParts = _qwertyInput
                    .split("'")
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }

                _qwertyInput = (last.consumedParts + remainParts).joinToString("'")
            }

            is PickRecord.T9 -> {
                val restoreStackCount = last.restorePinyinCountOnUndo
                    .coerceIn(0, last.consumedPinyins.size)

                if (restoreStackCount > 0) {
                    _pinyinStack.addAll(0, last.consumedPinyins.take(restoreStackCount))
                    _t9DigitsStack.addAll(0, last.consumedDigitChunks.take(restoreStackCount))
                    _t9CutsStack.addAll(0, last.consumedCutChunks.take(restoreStackCount))
                }

                val rawDigitChunks = last.consumedDigitChunks.drop(restoreStackCount)
                val rawCutChunks = last.consumedCutChunks.drop(restoreStackCount)

                val restoredDigits = rawDigitChunks.joinToString("")
                if (restoredDigits.isNotEmpty()) {
                    _rawT9Digits = restoredDigits + _rawT9Digits
                    val restoredCuts = flattenCutChunks(
                        digitChunks = rawDigitChunks,
                        cutChunks = rawCutChunks
                    )
                    restoreCutsOnPrepend(restoredDigits.length, restoredCuts)
                    trimCutsToLength(_rawT9Digits.length)
                }
            }

            is PickRecord.T9Digits -> {
                _rawT9Digits = last.consumedDigits + _rawT9Digits
                restoreCutsOnPrepend(last.consumedDigits.length, last.consumedCuts)
                trimCutsToLength(_rawT9Digits.length)
            }
        }
    }

    private fun lastWord(r: PickRecord): String {
        return when (r) {
            is PickRecord.Qwerty -> r.word
            is PickRecord.Apostrophe -> r.word
            is PickRecord.T9 -> r.word
            is PickRecord.T9Digits -> r.word
        }
    }
}
