package com.example.myapp.ime.compose.cn.qwerty

import android.content.Context
import android.view.inputmethod.InputConnection
import com.example.myapp.ime.candidate.CandidateController
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.compose.common.ComposeStrategy
import com.example.myapp.ime.compose.common.StrategyResult
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.router.CnBaseInputEngine
import com.example.myapp.ime.ui.ImeUi

class CnQwertyComposeStrategy(
    private val sessionProvider: () -> ComposingSession
) : ComposeStrategy {

    private fun session(): ComposingSession = sessionProvider()

    override fun onComposingInput(text: String): StrategyResult {
        if (text.isEmpty()) return StrategyResult.Noop

        // Chinese QWERTY: Always append to session（统一小写）
        session().appendQwerty(text.lowercase())
        return StrategyResult.SessionMutated
    }

    override fun onT9Input(digit: String): StrategyResult {
        // Not handled in QWERTY mode
        return StrategyResult.Noop
    }

    override fun onPinyinSidebarClick(pinyin: String) {
        val s = session()

        // 关键：如果已有 committedPrefix（已选词但仍在 composing），这里不要 clear，避免前缀丢失
        // 当前 CN-QWERTY 正常情况下也不会出现 sidebar（sidebar 主要来自 CN-T9），保守处理即可。
        if (s.committedPrefix.isNotEmpty()) return

        s.clear()
        s.appendQwerty(pinyin.lowercase())
    }

    override fun onEnter(ic: InputConnection?): StrategyResult {
        @Suppress("UNUSED_PARAMETER")
        val ignored = ic

        val s = session()
        if (!s.isComposing()) return StrategyResult.Noop

        // 中文全键盘 composing 时，Enter 提交 raw input
        val textToCommit = (s.committedPrefix + s.qwertyInput.lowercase())
        return if (textToCommit.isNotEmpty()) {
            StrategyResult.DirectCommit(textToCommit)
        } else {
            StrategyResult.Noop
        }
    }
}

/**
 * CN-QWERTY input engine: owns session mutation + refresh + commit chain for this mode only.
 */
class CnQwertyInputEngine(
    context: Context,
    ui: ImeUi,
    keyboardController: KeyboardController,
    candidateController: CandidateController,
    session: ComposingSession,
    inputConnectionProvider: () -> InputConnection?
) : CnBaseInputEngine(
    context = context,
    ui = ui,
    keyboardController = keyboardController,
    candidateController = candidateController,
    session = session,
    inputConnectionProvider = inputConnectionProvider,
    useT9Layout = false,
    logTag = "CnQwertyInputEngine",
    strategy = CnQwertyComposeStrategy(sessionProvider = { session })
)
