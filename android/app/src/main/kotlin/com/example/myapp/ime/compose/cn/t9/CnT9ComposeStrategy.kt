package com.example.myapp.ime.compose.cn.t9

import android.view.inputmethod.InputConnection
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult

class CnT9ComposeStrategy(
    private val sessionProvider: () -> ComposingSession
) : ComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()

    override fun onComposingInput(text: String): StrategyResult {
        // Not handled in T9 mode for Chinese
        return StrategyResult.Noop
    }

    override fun onT9Input(digit: String): StrategyResult {
        session().appendT9Digit(digit)
        return StrategyResult.SessionMutated
    }

    private fun pinyinToT9Code(pinyin: String): String {
        // 标准电话键盘映射：abc=2, def=3, ghi=4, jkl=5, mno=6, pqrs=7, tuv=8, wxyz=9
        // v/ü 作为 ü 的常见占位，按 u 归到 8
        val s = pinyin.lowercase()
        val sb = StringBuilder()
        for (ch in s) {
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

    override fun onPinyinSidebarClick(pinyin: String) {
        // 关键修复：侧栏点击应进入 pinyinStack，并消耗对应的 digits
        // 不能清空 session，否则会导致 composing 丢失与显示异常
        val code = pinyinToT9Code(pinyin)
        session().onPinyinSidebarClick(pinyin.lowercase(), code)
    }

    override fun onEnter(ic: InputConnection?): Boolean {
        // Similar logic to CnQwertyComposeStrategy
        if (session().isComposing()) {
            return true // Consumed
        }
        return false
    }
}
