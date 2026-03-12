package com.example.myapp.ime.mode.cn

/**
 * 模糊音规则表与匹配工具。
 *
 * 覆盖中文输入法最常见的模糊音对：
 *   z  ↔ zh     c  ↔ ch     s  ↔ sh
 *   n  ↔ l      f  ↔ h      r  ↔ l
 *   an ↔ ang    en ↔ eng    in ↔ ing    ian ↔ iang
 *
 * 使用方式：
 *   - isFuzzyMatch(a, b)   判断两个音节是否模糊等价
 *   - expandSyllable(s)    返回该音节所有模糊等价变体（含自身）
 *
 * 规则默认全开；如需用户可配置，外部可在调用前过滤 RULES。
 */
object CnT9FuzzyPinyin {

    /**
     * 模糊音规则：每条规则是一对可互换的前缀或后缀。
     * Pair<声母/韵母A, 声母/韵母B>
     */
    private val INITIAL_RULES: List<Pair<String, String>> = listOf(
        "z"  to "zh",
        "c"  to "ch",
        "s"  to "sh",
        "n"  to "l",
        "f"  to "h",
        "r"  to "l"
    )

    private val FINAL_RULES: List<Pair<String, String>> = listOf(
        "an"  to "ang",
        "en"  to "eng",
        "in"  to "ing",
        "ian" to "iang"
    )

    /**
     * 判断两个音节（已 normalized：lowercase、无隔音符）是否模糊等价。
     * 包括精确相等的情况。
     */
    fun isFuzzyMatch(a: String, b: String): Boolean {
        if (a == b) return true
        return expandSyllable(a).contains(b) || expandSyllable(b).contains(a)
    }

    /**
     * 返回某音节的所有模糊等价变体（含自身）。
     * 例如 "zi" → ["zi", "zhi"]，"zhi" → ["zhi", "zi"]。
     */
    fun expandSyllable(syllable: String): Set<String> {
        val result = mutableSetOf(syllable)

        // 声母替换
        for ((a, b) in INITIAL_RULES) {
            when {
                syllable.startsWith(a) && !syllable.startsWith(b) -> {
                    result.add(b + syllable.removePrefix(a))
                }
                syllable.startsWith(b) && !syllable.startsWith(a) -> {
                    result.add(a + syllable.removePrefix(b))
                }
            }
        }

        // 韵母替换（对声母+韵母结构）
        for ((a, b) in FINAL_RULES) {
            when {
                syllable.endsWith(a) && !syllable.endsWith(b) -> {
                    result.add(syllable.removeSuffix(a) + b)
                }
                syllable.endsWith(b) && !syllable.endsWith(a) -> {
                    result.add(syllable.removeSuffix(b) + a)
                }
            }
        }

        return result
    }
}
