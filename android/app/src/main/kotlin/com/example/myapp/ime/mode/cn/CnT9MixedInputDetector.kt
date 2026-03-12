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
        /** 正常中文拼音模式。*/
        CHINESE,
        /** 英文直出模式（数字串更可能是英文字母组合）。*/
        ENGLISH,
        /** URL 模式（数字串以 www/http/https 对应的 T9 前缀开头）。*/
        URL,
        /** 邮箱模式（数字串中含有 @ 键位对应序列，且结构符合邮箱格式）。*/
        EMAIL
    }

    /**
     * URL 模式的 T9 数字前缀：
     *  - "www"   → 999-999  (9=wxyz)
     *  - "http"  → 4884     (4=ghi, 8=tuv, 8=tuv, 7=pqrs — 注：p 在 7)
     *  - "https" → 48847    (s=7)
     *
     * 实际 T9 映射：
     *  h=4, t=8, t=8, p=7 → "http" = 4887
     *  w=9, w=9, w=9      → "www"  = 999
     */
    private val URL_T9_PREFIXES = setOf(
        "999",   // www
        "4887",  // http
        "48879"  // https
    )

    /**
     * @ 符号在九宫格上通常通过特殊键或长按触发；
     * 邮箱模式识别：数字串长度 >= 5 且能拼出 "com"/"net"/"org" 等常见后缀。
     * "com" → 266, "net" → 638, "org" → 674
     */
    private val EMAIL_DOMAIN_T9_SUFFIXES = setOf("266", "638", "674", "484", "988")

    // ── 公开 API ──────────────────────────────────────────────────

    /**
     * 识别当前数字串的输入模式。
     *
     * 优先级：URL > EMAIL > ENGLISH > CHINESE
     *
     * @param rawDigits 当前未物化的纯数字串
     * @return 识别到的 InputMode
     */
    fun detectMode(rawDigits: String): InputMode {
        if (rawDigits.length < MIN_DIGITS_FOR_DETECTION) return InputMode.CHINESE

        // URL 模式
        if (URL_T9_PREFIXES.any { rawDigits.startsWith(it) }) return InputMode.URL

        // 邮箱模式：长度足够 + 含常见域名后缀 T9 序列
        if (rawDigits.length >= 6 &&
            EMAIL_DOMAIN_T9_SUFFIXES.any { rawDigits.endsWith(it) }
        ) return InputMode.EMAIL

        // 英文模式：每个数字都有字母映射且综合评分偏英文
        if (looksLikeEnglish(rawDigits)) return InputMode.ENGLISH

        return InputMode.CHINESE
    }

    /**
     * 检测并返回建议的英文字符串候选（最多 MAX_EN_CANDIDATES 个）。
     * 若不像英文直出则返回空列表。
     *
     * @param rawDigits 当前未物化的纯数字串
     */
    fun detectEnglishCandidates(rawDigits: String): List<String> {
        if (rawDigits.length < MIN_DIGITS_FOR_DETECTION) return emptyList()

        val charSets = rawDigits.map { d ->
            T9Lookup.charsFromDigit(d.toString())
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
     * 供调用方在 URL 模式下注入候选头部。
     */
    fun detectUrlCandidates(rawDigits: String): List<String> {
        if (rawDigits.length < 3) return emptyList()
        val results = mutableListOf<String>()
        if (rawDigits.startsWith("999")) results.add("www.")
        if (rawDigits.startsWith("4887")) results.add("http://")
        if (rawDigits.startsWith("48879")) results.add("https://")
        return results
    }

    /**
     * 生成邮箱域名后缀候选（如 "@gmail.com", "@qq.com"）。
     * 供调用方在 EMAIL 模式下注入候选末尾。
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
     * 策略：
     *  - 所有数字均有字母映射（无 0/1）
     *  - 数字串中不含过多 9（9=wxyz，中文少用 w/x/y/z 开头）
     *  - 长度在 3–8 之间（太短不判断，太长更可能是拼音）
     */
    private fun looksLikeEnglish(rawDigits: String): Boolean {
        if (rawDigits.length > 8) return false

        val charSets = rawDigits.map { T9Lookup.charsFromDigit(it.toString()) }
        if (charSets.any { it.isEmpty() }) return false

        // 若数字串中有超过 40% 的位是 7 或 9（对应 pqrs/wxyz），
        // 中文拼音里这些字母出现率较低，更可能是英文
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
     * 简单英文字符串评分：优先全小写 + 无重复字母 + 长度适中。
     * 后续可替换为真实英文词频字典。
     */
    private fun scoreEnglishString(s: String): Int {
        var score = 0
        if (s == s.lowercase()) score += 10
        if (s.toSet().size == s.length) score += 5
        if (s.length in 3..8) score += 3
        return score
    }
}
