package com.example.myapp.ime.compose.common

import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate

class ComposingSession {

    private val _pinyinStack = ArrayList<String>()
    private var _rawT9Digits = ""
    private var _qwertyInput = ""
    private var _committedPrefix = ""

    // CN-T9: 手动切分点（位置是“rawT9Digits 的 index”，范围 1..len-1 有意义）
    private val _t9ManualCuts = HashSet<Int>()

    val pinyinStack: List<String> get() = _pinyinStack
    val rawT9Digits: String get() = _rawT9Digits
    val qwertyInput: String get() = _qwertyInput
    val committedPrefix: String get() = _committedPrefix

    // Sorted snapshot for handler preview.
    val t9ManualCuts: List<Int> get() = _t9ManualCuts.toList().sorted()

    private sealed class PickRecord {
        data class Qwerty(val word: String, val consumedPrefix: String) : PickRecord()
        data class Apostrophe(val word: String, val consumedParts: List<String>) : PickRecord()
        data class T9(val word: String, val consumedPinyins: List<String>) : PickRecord()

        // NEW: 记录被消费掉的 digits 前缀，以及被消费掉/移除的切分点，便于 undo 恢复
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
        _rawT9Digits = ""
        _qwertyInput = ""
        _committedPrefix = ""
        _t9ManualCuts.clear()
        pickHistory.clear()
    }

    fun isComposing(): Boolean {
        return _committedPrefix.isNotEmpty()
            || _pinyinStack.isNotEmpty()
            || _rawT9Digits.isNotEmpty()
            || _qwertyInput.isNotEmpty()
    }

    fun appendQwerty(text: String) {
        _qwertyInput += text.lowercase()
    }

    fun appendT9Digit(digit: String) {
        _rawT9Digits += digit
        // digits 在末尾追加，不需要调整 cuts（cuts 是 index，含义不变）
    }

    /**
     * CN-T9: 在“已输入 digits 的末尾”插入一个切分点。
     * 不选拼音，不消费 digits。
     */
    fun insertT9ManualCutAtEnd() {
        val len = _rawT9Digits.length
        if (len <= 0) return
        // cut at end is meaningless; but storing it doesn't help, so ignore.
        // We store only 1..len-1; however user presses at end, so we store `len`,
        // and handler will treat it as boundary between previous segment and future input.
        // For now, store it; it will become an interior cut after next digit arrives.
        _t9ManualCuts.add(len)
    }

    private fun trimCutsToLength(newLen: Int) {
        if (_t9ManualCuts.isEmpty()) return
        val it = _t9ManualCuts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p > newLen) it.remove()
        }
    }

    /**
     * Consume digits prefix (len) => shift all remaining cut positions left by len.
     * Return all cuts removed (<= len) so undo can restore.
     */
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
        return removed
    }

    /**
     * Prepend digits prefix (len) during undo => shift existing cuts right by len, then restore removed cuts.
     */
    private fun restoreCutsOnPrepend(len: Int, restoredCuts: List<Int>) {
        if (len <= 0 && restoredCuts.isEmpty()) return

        val shifted = HashSet<Int>(_t9ManualCuts.size + restoredCuts.size)
        for (p in _t9ManualCuts) shifted.add(p + len)
        for (p in restoredCuts) shifted.add(p)

        _t9ManualCuts.clear()
        _t9ManualCuts.addAll(shifted)
    }

    fun onPinyinSidebarClick(pinyin: String, t9Code: String) {
        _pinyinStack.add(pinyin.lowercase())

        val consume = t9Code.length
        if (consume > 0) {
            consumeDigitsPrefixForCuts(consume)
        }

        _rawT9Digits =
            if (_rawT9Digits.length >= consume) _rawT9Digits.substring(consume) else ""
        trimCutsToLength(_rawT9Digits.length)
    }

    private fun repLetter(d: Char): String {
        val list = T9Lookup.charsFromDigit(d)
        return if (list.isNotEmpty()) list[0] else "?"
    }

    fun displayText(useT9Layout: Boolean): String? {
        if (!isComposing()) return null

        if (!useT9Layout) {
            val sb = StringBuilder()
            sb.append(_committedPrefix)
            sb.append(_qwertyInput)
            return sb.toString()
        }

        val stackUi = _pinyinStack.joinToString("'") { it.lowercase() }

        val previewUi = when {
            _rawT9Digits.isEmpty() -> ""
            _rawT9Digits.length == 1 -> repLetter(_rawT9Digits[0])
            else -> "…"
        }

        val sb = StringBuilder()
        sb.append(_committedPrefix)
        if (stackUi.isNotEmpty()) sb.append(stackUi)
        if (previewUi.isNotEmpty()) {
            if (stackUi.isNotEmpty()) sb.append("'")
            sb.append(previewUi)
        }
        return sb.toString()
    }

    fun backspace(useT9Layout: Boolean): Boolean {
        if (_committedPrefix.isNotEmpty()) {
            undoLastPick()
            return true
        }

        if (!isComposing()) return false

        if (!useT9Layout) {
            if (_qwertyInput.isNotEmpty()) _qwertyInput = _qwertyInput.dropLast(1)
        } else {
            if (_rawT9Digits.isNotEmpty()) {
                _rawT9Digits = _rawT9Digits.dropLast(1)
                trimCutsToLength(_rawT9Digits.length)
            } else if (_pinyinStack.isNotEmpty()) {
                _pinyinStack.removeAt(_pinyinStack.size - 1)
            }
        }

        return true
    }

    fun pickCandidate(cand: Candidate, useT9Layout: Boolean, isChinese: Boolean): PickResult {
        if (useT9Layout) {
            _committedPrefix += cand.word

            val consumePinyin = cand.pinyinCount.coerceAtLeast(0)
            val consumedPinyins = if (consumePinyin > 0) _pinyinStack.take(consumePinyin) else emptyList()

            if (consumePinyin > 0) {
                pickHistory.add(PickRecord.T9(cand.word, consumedPinyins))
                repeat(consumePinyin) { if (_pinyinStack.isNotEmpty()) _pinyinStack.removeAt(0) }

                return if (_pinyinStack.isEmpty() && _rawT9Digits.isEmpty()) {
                    PickResult.Commit(_committedPrefix)
                } else {
                    PickResult.Updated
                }
            }

            if (_rawT9Digits.isEmpty()) {
                return PickResult.Commit(_committedPrefix)
            }

            val consumeDigits = cand.input.length.coerceAtLeast(1).coerceAtMost(_rawT9Digits.length)
            val consumedDigits = _rawT9Digits.substring(0, consumeDigits)

            val consumedCuts = consumeDigitsPrefixForCuts(consumeDigits)
            _rawT9Digits = _rawT9Digits.substring(consumeDigits)
            trimCutsToLength(_rawT9Digits.length)

            pickHistory.add(PickRecord.T9Digits(cand.word, consumedDigits, consumedCuts))

            return if (_pinyinStack.isEmpty() && _rawT9Digits.isEmpty()) {
                PickResult.Commit(_committedPrefix)
            } else {
                PickResult.Updated
            }
        }

        val inputStr = _qwertyInput

        if (isChinese && inputStr.contains("'")) {
            val parts = inputStr.split("'").map { it.trim().lowercase() }
            val nonEmptyParts = parts.filter { it.isNotEmpty() }

            val consume = cand.input.length.coerceAtLeast(1).coerceAtMost(nonEmptyParts.size)
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

        val consume = cand.input.length.coerceAtLeast(1).coerceAtMost(inputStr.length)
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
            is PickRecord.Qwerty -> _qwertyInput = last.consumedPrefix + _qwertyInput
            is PickRecord.Apostrophe -> {
                val remainParts = _qwertyInput
                    .split("'")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                _qwertyInput = (last.consumedParts + remainParts).joinToString("'")
            }

            is PickRecord.T9 -> _pinyinStack.addAll(0, last.consumedPinyins)

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
