package com.example.myapp.ime.compose.common

import com.example.myapp.dict.model.Candidate

class ComposingSession {

    // === 状态（从 SimpleIME 挪过来）===
    private val _pinyinStack = ArrayList<String>()
    private var _rawT9Digits = ""
    private var _qwertyInput = ""
    private var _committedPrefix = ""

    // NEW: T9 digits 的“预览拼音”（用于侧栏未实现前的悬浮 preedit 展示）
    private var _t9PreviewPinyin: String? = null

    val pinyinStack: List<String> get() = _pinyinStack
    val rawT9Digits: String get() = _rawT9Digits
    val qwertyInput: String get() = _qwertyInput
    val committedPrefix: String get() = _committedPrefix

    fun setT9PreviewPinyin(pinyin: String?) {
        _t9PreviewPinyin = pinyin?.trim()?.lowercase().takeUnless { it.isNullOrEmpty() }
    }

    // === 撤销栈（从 SimpleIME 挪过来）===
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
        _t9PreviewPinyin = null
        pickHistory.clear()
    }

    fun isComposing(): Boolean {
        return _committedPrefix.isNotEmpty()
                || _pinyinStack.isNotEmpty()
                || _rawT9Digits.isNotEmpty()
                || _qwertyInput.isNotEmpty()
    }

    fun appendQwerty(text: String) {
        // 中文全键盘：统一保存为小写，避免 UI/候选出现大写
        _qwertyInput += text.lowercase()
    }

    fun appendT9Digit(digit: String) {
        _rawT9Digits += digit
    }

    /**
     * @param t9Code 由外部 convertToT9(pinyin) 计算好再传入（session 本身不依赖键盘映射表）
     */
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

        // 如果用户/上层已经带了 `'`，直接按它作为音节边界（再清理空段）
        if (s.contains("'")) {
            return s.split("'")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        // 简单启发式：当“当前段已经出现过元音”且“后面出现一个可作为声母开头”的位置，就切分
        // 目的：仅用于 UI 展示的音节分隔，不改变内部 qwertyInput（避免影响候选检索/消耗逻辑）
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

    fun displayText(useT9Layout: Boolean): String? {
        if (!isComposing()) return null

        // committedPrefix 是已选中但未最终上屏的汉字前缀（仍保持显示）
        if (!useT9Layout) {
            val syllables = splitPinyinForDisplay(_qwertyInput)
            val pinyinUi = syllables.joinToString("'")
            return _committedPrefix + pinyinUi
        }

        // 中文 T9：显示“已确认拼音栈” + “当前 digits 对应的预览拼音”
        val stackUi = _pinyinStack.joinToString("'") { it.lowercase() }
        val previewUi = _t9PreviewPinyin?.lowercase()?.trim().orEmpty()

        val sb = StringBuilder()
        sb.append(_committedPrefix)

        if (stackUi.isNotEmpty()) {
            sb.append(stackUi)
        }

        if (_rawT9Digits.isNotEmpty()) {
            if (previewUi.isNotEmpty()) {
                if (stackUi.isNotEmpty()) sb.append("'")
                sb.append(previewUi)
            } else {
                // 兜底：如果词典还没给出 preview（极少情况），保留原来的占位
                if (stackUi.isNotEmpty()) sb.append("'")
                sb.append("…")
            }
        }

        return sb.toString()
    }

    /**
     * backspace 是否在 composing 场景被 session 消耗掉：
     * - true：session 已处理（撤销前缀 or 删除 composing 输入）
     * - false：说明当前不在 composing，外部应发送系统 DEL
     */
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

        // 中文 + ' 分词的那套消耗逻辑（原样搬过来）
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
