package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler

object CnT9Handler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        candidateComposer: CandidateComposer,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val r = candidateComposer.compose(
            session = session,
            isChinese = true,
            useT9Layout = true,
            isT9Keyboard = true,
            singleCharMode = singleCharMode
        )

        val candidates = ArrayList(r.candidates)

        val t9PreviewText =
            if (session.rawT9Digits.isNotEmpty()) {
                T9PreviewBuilder.buildPreview(session.rawT9Digits, candidates)
            } else {
                null
            }

        session.setT9PreviewText(t9PreviewText)
        session.setQwertyPreviewText(null)

        promoteCandidateMatchingPreview(candidates, t9PreviewText)

        return ImeModeHandler.Output(
            candidates = candidates,
            pinyinSidebar = r.pinyinSidebar
        )
    }

    private object T9PreviewBuilder {
        private val pinyinSet: Set<String> = PinyinTable.allPinyins.map { it.lowercase() }.toHashSet()
        private val maxPyLen: Int = PinyinTable.allPinyins.maxOfOrNull { it.length } ?: 8

        private fun repLetter(d: Char): Char {
            val list = T9Lookup.charsFromDigit(d)
            if (list.isNotEmpty() && list[0].isNotEmpty()) return list[0][0]
            return '?'
        }

        private fun lettersOnly(raw: String): String {
            val s = raw.lowercase()
            val sb = StringBuilder()
            for (ch in s) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }

        private fun segmentLettersForUi(letters: String): String {
            val s = letters.lowercase()
            if (s.isEmpty()) return ""

            val dp: ArrayList<List<String>?> = ArrayList<List<String>?>(s.length + 1)
            repeat(s.length + 1) { dp.add(null) }
            dp[0] = emptyList()

            for (i in 0..s.length) {
                val base = dp[i] ?: continue
                val remain = s.length - i
                val tryMax = minOf(maxPyLen, remain)
                for (l in 1..tryMax) {
                    val sub = s.substring(i, i + l)
                    if (!pinyinSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    if (old == null || cand.size > old.size) {
                        dp[i + l] = cand
                    }
                }
            }

            var bestCut = 0
            for (k in s.length downTo 0) {
                if (dp[k] != null) {
                    bestCut = k
                    break
                }
            }

            val parts = ArrayList<String>()
            val left = dp[bestCut] ?: emptyList()
            parts.addAll(left)

            if (bestCut < s.length) {
                parts.add(s.substring(bestCut))
            }

            return parts.joinToString("'")
        }

        fun buildPreview(digits: String, candidates: List<Candidate>): String? {
            if (digits.isEmpty()) return null

            if (digits.length == 1) {
                return repLetter(digits[0]).toString()
            }

            val bestPinyin = candidates.asSequence()
                .mapNotNull { it.pinyin }
                .firstOrNull { it.isNotBlank() }
                ?.lowercase()
                ?.trim()

            val prefixLetters = if (!bestPinyin.isNullOrEmpty()) {
                val letters = lettersOnly(bestPinyin)
                val sb = StringBuilder()
                sb.append(letters.take(digits.length))
                var i = sb.length
                while (i < digits.length) {
                    sb.append(repLetter(digits[i]))
                    i++
                }
                sb.toString()
            } else {
                buildString {
                    for (ch in digits) append(repLetter(ch))
                }
            }

            return segmentLettersForUi(prefixLetters)
        }

        fun normalizePreviewLetters(previewText: String): String {
            val sb = StringBuilder()
            for (ch in previewText.lowercase()) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }

        fun normalizePinyinLetters(pinyin: String): String {
            val sb = StringBuilder()
            for (ch in pinyin.lowercase()) {
                if (ch in 'a'..'z') sb.append(ch)
            }
            return sb.toString()
        }
    }

    private fun promoteCandidateMatchingPreview(
        candidates: ArrayList<Candidate>,
        previewText: String?
    ) {
        if (previewText.isNullOrEmpty()) return
        if (candidates.isEmpty()) return

        val key = T9PreviewBuilder.normalizePreviewLetters(previewText)
        if (key.isEmpty()) return

        var bestIdx = -1
        var bestScore = Int.MIN_VALUE

        for (i in candidates.indices) {
            val c = candidates[i]
            val py = c.pinyin ?: continue
            val pyLetters = T9PreviewBuilder.normalizePinyinLetters(py)
            if (!pyLetters.startsWith(key)) continue

            val lenScore = -kotlin.math.abs(pyLetters.length - key.length)
            val freqScore = c.priority
            val score = lenScore * 1_000_000 + freqScore

            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        if (bestIdx <= 0) return

        val matched = candidates.removeAt(bestIdx)
        candidates.add(0, matched)
    }
}
