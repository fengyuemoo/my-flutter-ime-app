package com.example.myapp.ime.compose.common

import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate

class ComposingSession {

    private val _pinyinStack = ArrayList<String>()
    private var _rawT9Digits = ""
    private var _qwertyInput = ""
    private var _committedPrefix = ""

    // NEW: 由候选/词频驱动的 T9 预览（例如 yi'ge / yi'g / w）
    private var _t9PreviewText: String? = null

    val pinyinStack: List<String> get() = _pinyinStack
    val rawT9Digits: String get() = _rawT9Digits
    val qwertyInput: String get() = _qwertyInput
    val committedPrefix: String get() = _committedPrefix

    fun setT9PreviewText(text: String?) {
        _t9PreviewText = text?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() }
    }

    private sealed class PickRecord {
        data class Qwerty(val word: String, val consumedPrefix: String) : PickRecord()
        data class Apostrophe(val word: String, val consumedParts: List<String>) : PickRecord()
        data class T9(val word: String, val consumedPinyins: List<String>) : PickRecord()
        data class T9Digits(val word: String, val consumedDigits: String) : PickRecord()
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
        _t9PreviewText = null
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
    }

    fun onPinyinSidebarClick(pinyin: String, t9Code: String) {
        _pinyinStack.add(pinyin.lowercase())
        _rawT9Digits =
            if (_rawT9Digits.length >= t9Code.length) _rawT9Digits.substring(t9Code.length) else ""
    }

    private fun isVowel(ch: Char): Boolean {
        return ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u' || ch == 'v' || ch == 'ü'
    }

    private fun splitPinyinForDisplay(raw: String): List<String> {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return emptyList()

        if (s.contains("'")) {
            return s.split("'")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val initials = arrayOf(
            "zh", "ch", "sh",
            "b", "p", "m", "f",
            "d", "t", "n", "l",
            "g", "k", "h",
            "j", "q", "x",
            "r",
            "z", "c", "s",
            "y", "w"
        )

        fun matchInitialAt(str: String, index: Int): Int {
            for (ini in initials) {
                if (index + ini.length <= str.length && str.regionMatches(index, ini, 0, ini.length)) {
                    return ini.length
                }
            }
            return 0
        }

        val result = ArrayList<String>()
        var start = 0
        var hasVowel = false
        var i = 0

        while (i < s.length) {
            val ch = s[i]
            if (isVowel(ch)) hasVowel = true

            val next = i + 1
            if (hasVowel && next < s.length) {
                val initLen = matchInitialAt(s, next)
                if (initLen > 0) {
                    val part = s.substring(start, next).trim()
                    if (part.isNotEmpty()) result.add(part)
                    start = next
                    hasVowel = false
                    i = start
                    continue
                }
            }
            i++
        }

        val tail = s.substring(start).trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    private fun repLetter(d: Char): String {
        val list = T9Lookup.charsFromDigit(d)
        return if (list.isNotEmpty()) list[0] else "?"
    }

    fun displayText(useT9Layout: Boolean): String? {
        if (!isComposing()) return null

        if (!useT9Layout) {
            val syllables = splitPinyinForDisplay(_qwertyInput)
            val pinyinUi = syllables.joinToString("'")
            return _committedPrefix + pinyinUi
        }

        val stackUi = _pinyinStack.joinToString("'") { it.lowercase() }

        val previewUi = when {
            _rawT9Digits.isEmpty() -> ""
            !_t9PreviewText.isNullOrEmpty() -> _t9PreviewText!!
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
            if (_rawT9Digits.isNotEmpty()) _rawT9Digits = _rawT9Digits.dropLast(1)
            else if (_pinyinStack.isNotEmpty()) _pinyinStack.removeAt(_pinyinStack.size - 1)
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
            _rawT9Digits = _rawT9Digits.substring(consumeDigits)

            pickHistory.add(PickRecord.T9Digits(cand.word, consumedDigits))

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
            is PickRecord.T9Digits -> _rawT9Digits = last.consumedDigits + _rawT9Digits
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
