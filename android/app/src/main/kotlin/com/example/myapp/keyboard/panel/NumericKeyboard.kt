package com.example.myapp.keyboard.panel

import android.content.Context
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.PanelMode
import com.example.myapp.keyboard.core.RawCommitMode

/**
 * 纯数字键盘逻辑 (123)
 *
 * - RawCommitMode：表示处于该模式时，候选点击应直接 commitRaw（由 CandidateController 用接口判断）。
 * - PanelMode：表示它属于“面板键盘”（KeyboardController.isPanelMode 可用接口判断，不再依赖具体类名）。
 *
 * 依赖拆分：
 * - 这里统一依赖 ImeActions（键盘侧需要的动作集合）。
 */
class NumericKeyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.kbdnumeric), RawCommitMode, PanelMode {

    override fun handleKeyPress(button: Button) {
        val id = button.id
        val label = button.text.toString()

        // 返回/关闭面板：id 以 XML 为准（无下划线：t9numback）。
        if (id == R.id.t9numback) {
            ime.exitNumericMode()
            return
        }

        if (label == "SPACE" || label == "␣") {
            ime.handleSpaceKey()
            return
        }

        if (label.contains("⏎")) {
            ime.handleSpecialKey("⏎")
            return
        }

        ime.commitText(label)
    }
}
