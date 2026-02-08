package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.PinyinTable
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.CandidateComposer
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.ImeModeHandler

object CnQwertyHandler : ImeModeHandler {

    override fun build(
        session: ComposingSession,
        candidateComposer: CandidateComposer,
        singleCharMode: Boolean
    ): ImeModeHandler.Output {

        val r = candidateComposer.compose(
            session = session,
            isChinese = true,
            useT9Layout = false,
            isT9Keyboard = false,
            singleCharMode = singleCharMode
        )

        val candidates = ArrayList(r.candidates)

        // CN Qwerty: only qwerty preview
        session.setT9PreviewText(null)

        val inputLower = session.qwertyInput.lowercase()

        // 缩写词组强置顶
        if (isAcronymLikeQwerty(inputLower)) {
            promoteChineseCandidateByAcronym(candidates, inputLower)
        }

        val top = candidates.firstOrNull()

        val preview = when {
            inputLower.contains("'") -> inputLower

            // 1) 首候选是英文精确词或变体：预览显示英文（不带分词符）
            top != null && isAsciiWord(top.word) -> {
                val w = top.word.lowercase()
                val variants = englishVariantsFor(inputLower)
                if (w == inputLower || w in variants) w else QwertyPreviewBuilder.build(inputLower)
            }

            else -> {
                val defaultPreview = QwertyPreviewBuilder.build(inputLower)
                val defaultPartsCount = defaultPreview
                    .split("'")
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .count()

                val canStrictlyProveTopIsAcronymHit =
                    top != null &&
                        top.acronym != null &&
                        top.acronym.lowercase() == inputLower &&
                        top.word.length == inputLower.length &&
                        inputLower.length in 2..12 &&
                        inputLower.all { it in 'a'..'z' }

                if (canStrictlyProveTopIsAcronymHit && defaultPartsCount < inputLower.length) {
                    inputLower.map { it.toString() }.joinToString("'")
                } else {
                    defaultPreview
                }
            }
        }

        session.setQwertyPreviewText(preview)

        return ImeModeHandler.Output(
            candidates = candidates,
            pinyinSidebar = r.pinyinSidebar
        )
    }

    private object PinyinUtil {
        private val pinyinSet: Set<String> = PinyinTable.allPinyins.map { it.lowercase() }.toHashSet()
        private val maxPyLen: Int = PinyinTable.allPinyins.maxOfOrNull { it.length } ?: 8

        fun normalizeLetters(s: String): String {
            val sb = StringBuilder()
            for (ch in s.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }

        fun splitToSyllablesBest(letters: String): List<String> {
            val s = normalizeLetters(letters)
            if (s.isEmpty()) return emptyList()

            val dp: ArrayList<List<String>?> = ArrayList<List<String>?>(s.length + 1)
            repeat(s.length + 1) { dp.add(null) }
            dp[0] = emptyList()

            for (i in 0..s.length) {
                val base = dp[i] ?: continue
                val remain = s.length - i
                val tryMax = minOf(maxPyLen, remain)
                for (l in tryMax downTo 1) {
                    val sub = s.substring(i, i + l)
                    if (!pinyinSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    if (old == null || cand.size < old.size) {
                        dp[i + l] = cand
                    }
                }
            }

            return dp[s.length] ?: emptyList()
        }

        fun acronymOfPinyin(pinyin: String): String {
            val syl = splitToSyllablesBest(pinyin)
            if (syl.isEmpty()) return ""
            val sb = StringBuilder()
            for (s in syl) if (s.isNotEmpty()) sb.append(s[0])
            return sb.toString()
        }
    }

    private object QwertyPreviewBuilder {
        private val all = PinyinTable.allPinyins.map { it.lowercase() }
        private val syllableSet: Set<String> = all.toHashSet()
        private val maxLen: Int = all.maxOfOrNull { it.length } ?: 8

        private val prefixSet: Set<String> by lazy {
            val set = HashSet<String>(all.size * 3)
            for (py in all) {
                for (i in 1..py.length) set.add(py.substring(0, i))
            }
            set
        }

        private fun normalizeLetters(s: String): String {
            val sb = StringBuilder()
            for (ch in s.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }

        private fun splitBestExactPrefix(s: String): Pair<List<String>, Int> {
            if (s.isEmpty()) return emptyList<String>() to 0

            val dp = arrayOfNulls<List<String>>(s.length + 1)
            dp[0] = emptyList()

            for (i in 0..s.length) {
                val base = dp[i] ?: continue
                val remain = s.length - i
                val tryMax = minOf(maxLen, remain)
                for (l in tryMax downTo 1) {
                    val sub = s.substring(i, i + l)
                    if (!syllableSet.contains(sub)) continue
                    val cand = base + sub
                    val old = dp[i + l]
                    if (old == null || cand.size < old.size) dp[i + l] = cand
                }
            }

            var bestCut = 0
            for (k in s.length downTo 0) {
                if (dp[k] != null) {
                    bestCut = k
                    break
                }
            }
            return (dp[bestCut] ?: emptyList()) to bestCut
        }

        private fun longestPrefix(rem: String): String {
            if (rem.isEmpty()) return ""
            val max = minOf(maxLen, rem.length)
            for (l in max downTo 1) {
                val sub = rem.substring(0, l)
                if (prefixSet.contains(sub)) return sub
            }
            return rem.take(1)
        }

        fun build(rawLower: String): String {
            val s = rawLower.trim().lowercase()
            if (s.isEmpty()) return s
            if (s.contains("'")) return s

            val letters = normalizeLetters(s)
            if (letters.isEmpty() || letters != s) return s

            val (parts, cut) = splitBestExactPrefix(letters)
            val out = ArrayList<String>()
            out.addAll(parts)

            if (cut < letters.length) {
                val rem = letters.substring(cut)
                val p = longestPrefix(rem)
                out.add(p)
                val rest = rem.substring(p.length)
                if (rest.isNotEmpty()) out.add(rest)
            }

            if (out.isEmpty()) out.add(letters)
            return out.joinToString("'")
        }
    }

    private fun isAcronymLikeQwerty(inputLower: String): Boolean {
        if (inputLower.length !in 2..6) return false
        if (!inputLower.all { it in 'a'..'z' }) return false
        if (inputLower == "zh" || inputLower == "ch" || inputLower == "sh") return false
        return inputLower.none { it == 'a' || it == 'e' || it == 'i' || it == 'o' || it == 'u' || it == 'v' }
    }

    private fun isAsciiWord(word: String): Boolean {
        if (word.isEmpty()) return false
        return word.all { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun englishVariantsFor(inputLower: String): Set<String> {
        if (inputLower.length < 2) return emptySet()
        val out = LinkedHashSet<String>()
        out.add("${inputLower}s")
        out.add("${inputLower}es")
        return out
    }

    private fun promoteChineseCandidateByAcronym(
        candidates: ArrayList<Candidate>,
        acronymKey: String
    ) {
        if (acronymKey.isEmpty()) return
        if (candidates.isEmpty()) return

        var bestIdx = -1
        var bestScore = Long.MIN_VALUE

        for (i in candidates.indices) {
            val c = candidates[i]

            // 优先用 DB 的 acronym；缺失才回退到从 pinyin 反算
            val ac = c.acronym
                ?: run {
                    val py = c.pinyin ?: return@run null
                    PinyinUtil.acronymOfPinyin(py).takeIf { it.isNotEmpty() }
                }
                ?: continue

            if (ac != acronymKey) continue

            val wordLenBoost = if (c.word.length == acronymKey.length) 200_000_000L else 0L
            val syllablesBoost = if (c.syllables > 0 && c.syllables == acronymKey.length) 100_000_000L else 0L
            val freqScore = c.priority.toLong()

            val score = wordLenBoost + syllablesBoost + freqScore
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
