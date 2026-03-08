package com.example.myapp.ime.compose.cn.t9

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import java.util.Locale

class CnT9ComposeStrategy(
    private val sessionProvider: () -> ComposingSession,
    private val enterCommitProvider: () -> String?
) : ComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()

    override fun onComposingInput(text: String): StrategyResult {
        return StrategyResult.Noop
    }

    override fun onT9Input(digit: String): StrategyResult {
        val normalizedDigits = digit.filter { it in '0'..'9' }
        if (normalizedDigits.isEmpty()) return StrategyResult.Noop

        session().appendT9Digit(normalizedDigits)
        return StrategyResult.SessionMutated
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        val normalized = normalizePinyin(pinyin)
        if (normalized.isEmpty()) return

        val code = pinyinToT9Code(normalized)
        if (code.isEmpty()) return

        session().onPinyinSidebarClick(normalized, code)
    }

    override fun onEnter(ic: InputConnection?): StrategyResult {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic

        val commit = sanitizeEnterCommitText(enterCommitProvider())
        return if (commit.isNotEmpty()) {
            StrategyResult.DirectCommit(commit)
        } else {
            StrategyResult.Noop
        }
    }

    private fun normalizePinyin(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("’", "'")
            .replace("'", "")
            .filter { it in 'a'..'z' || it == 'ü' || it == 'v' }
            .replace('v', 'ü')
    }

    private fun sanitizeEnterCommitText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("’", "'")
            .filter { it in 'a'..'z' || it == 'ü' || it == 'v' }
            .replace('ü', 'v')
    }

    private fun pinyinToT9Code(pinyin: String): String {
        val sb = StringBuilder()
        for (ch in pinyin.lowercase(Locale.ROOT)) {
            val d = when (ch) {
                'a', 'b', 'c' -> '2'
                'd', 'e', 'f' -> '3'
                'g', 'h', 'i' -> '4'
                'j', 'k', 'l' -> '5'
                'm', 'n', 'o' -> '6'
                'p', 'q', 'r', 's' -> '7'
                't', 'u', 'v', 'ü' -> '8'
                'w', 'x', 'y', 'z' -> '9'
                else -> null
            }
            if (d != null) sb.append(d)
        }
        return sb.toString()
    }
}
