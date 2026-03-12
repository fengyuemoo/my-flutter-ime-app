package com.example.myapp.ime.mode.cn

import com.example.myapp.dict.impl.T9Lookup

/**
 * CN-T9 中英混输检测器。
 *
 * 对应规则清单「中英文混输」：
 *  - 数字串进入中文九键时，字母/URL/邮箱等模式要能快速切到直出
 *  - 切回时不破坏中文 composing
 *
 * 职责：
 *  1. detectMode()              — 识别当前输入模式（中文/英文/URL/邮箱）
 *  2. detectEnglishCandidates() — 生成英文字符串候选列表（按评分排序）
 *  3. detectUrlCandidates()     — 生成 URL 前缀候选
 *  4. detectEmailSuffixCandidates() — 生成邮箱域名后缀候选
 *
 * InputMode 语义：
 *  - CHINESE  → 正常走中文拼音候选，不注入英文候选
 *  - ENGLISH  → 优先在候选头部注入英文直出候选，同时保留中文候选
 *  - URL      → 候选头部注入 URL 直出候选（http/https/www 前缀），其余中文候选降权
 *  - EMAIL    → 候选头部注入邮箱直出候选（含 @ 映射），其余中文候选降权
 */
object CnT9MixedInputDetector {

    private const val MAX_EN_CANDIDATES = 4
    private const val MIN_DIGITS_FOR_DETECTION = 3

    // ── 输入模式枚举 ──────────────────────────────────────────────

    enum class InputMode {
        CHINESE,
        ENGLISH,
        URL,
        EMAIL
    }

    /**
     * URL 模式的 T9 数字前缀：
     *  "www"   → 999  (w=9)
     *  "http"  → 4887 (h=4, t=8, t=8, p=7)
     *  "https" → 48879 (s=7 — 注：s 在 7 上)
     */
    private val URL_T9_PREFIXES = setOf("999", "4887", "48879")

    /**
     * 常见邮箱域名后缀的 T9 编码：
     *  "com" → 266, "net" → 638, "org" → 674
     */
    private val EMAIL_DOMAIN_T9_SUFFIXES = setOf("266", "638", "674", "484", "988")

    // ── 公开 API ──────────────────────────────────────────────────

    /**
     * 识别当前数字串的输入模式。
     * 优先级：URL > EMAIL > ENGLISH > CHINESE
     */
    fun detectMode(rawDigits: String): InputMode {
        if (rawDigits.length < MIN_DIGITS_FOR_DETECTION) return InputMode.CHINESE
        if (URL_T9_PREFIXES.any { rawDigits.startsWith(it) }) return InputMode.URL
        if (rawDigits.length >= 6 &&
            EMAIL_DOMAIN_T9_SUFFIXES.any { rawDigits.endsWith(it) }
        ) return InputMode.EMAIL
        if (looksLikeEnglish(rawDigits)) return InputMode.ENGLISH
        return InputMode.CHINESE
    }

    /**
     * 检测并返回建议的英文字符串候选（最多 MAX_EN_CANDIDATES 个）。
     */
    fun detectEnglishCandidates(rawDigits: String): List<String> {
        if (rawDigits.length < MIN_DIGITS_FOR_DETECTION) return emptyList()

        // T9Lookup.charsFromDigit 接受 Char 参数
        val charSets = rawDigits.map { d ->
            T9Lookup.charsFromDigit(d)
        }
        if (charSets.any { it.isEmpty() }) return emptyList()

        val results = mutableListOf<String>()
        enumerate(charSets, StringBuilder(), results)
        if (results.isEmpty()) return emptyList()

        return results
            .sortedByDescending { scoreEnglishString(it) }
            .take(MAX_EN_CANDIDATES)
    }

    /**
     * 生成 URL 前缀候选（如 "www.", "http://", "https://"）。
     */
    fun detectUrlCandidates(rawDigits: String): List<String> {
        if (rawDigits.length < 3) return emptyList()
        val results = mutableListOf<String>()
        if (rawDigits.startsWith("999"))   results.add("www.")
        if (rawDigits.startsWith("4887"))  results.add("http://")
        if (rawDigits.startsWith("48879")) results.add("https://")
        return results
    }

    /**
     * 生成邮箱域名后缀候选（如 "@gmail.com"）。
     */
    fun detectEmailSuffixCandidates(rawDigits: String): List<String> {
        if (rawDigits.length < 6) return emptyList()
        return when {
            rawDigits.endsWith("266") -> listOf("@gmail.com", "@163.com", "@126.com")
            rawDigits.endsWith("638") -> listOf("@net.cn")
            rawDigits.endsWith("674") -> listOf("@org.cn")
            rawDigits.endsWith("484") -> listOf("@icloud.com")
            rawDigits.endsWith("988") -> listOf("@outlook.com", "@qq.com")
            else -> emptyList()
        }
    }

    // ── 私有辅助 ──────────────────────────────────────────────────

    /**
     * 判断数字串是否「看起来像英文」。
     *
     * 策略：所有数字均有字母映射（无 0/1），且含较多 7/9 键位（pqrs/wxyz）。
     * 中文拼音里 w/x/y/z 开头的音节极少，7/9 比例高时更可能是英文。
     */
    private fun looksLikeEnglish(rawDigits: String): Boolean {
        if (rawDigits.length > 8) return false

        // T9Lookup.charsFromDigit 接受 Char
        val charSets = rawDigits.map { T9Lookup.charsFromDigit(it) }
        if (charSets.any { it.isEmpty() }) return false

        val highKeyCount = rawDigits.count { it == '7' || it == '9' }
        return highKeyCount.toFloat() / rawDigits.length >= 0.4f
    }

    private fun enumerate(
        charSets: List<List<String>>,
        current: StringBuilder,
        results: MutableList<String>
    ) {
        if (results.size >= MAX_EN_CANDIDATES * 8) return

        if (current.length == charSets.size) {
            results.add(current.toString())
            return
        }

        val idx = current.length
        for (ch in charSets[idx]) {
            current.append(ch)
            enumerate(charSets, current, results)
            current.deleteCharAt(current.length - 1)
        }
    }

    /**
     * 简单英文字符串评分：全小写 + 无重复字母 + 长度适中。
     */
    private fun scoreEnglishString(s: String): Int {
        var score = 0
        if (s == s.lowercase()) score += 10
        if (s.toSet().size == s.length) score += 5
        if (s.length in 3..8) score += 3
        return score
    }
}
