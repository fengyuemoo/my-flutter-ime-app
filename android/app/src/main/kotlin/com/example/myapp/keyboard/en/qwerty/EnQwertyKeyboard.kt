package com.example.myapp.keyboard.en.qwerty

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.ui.EnglishPredictUi
import com.example.myapp.keyboard.core.BaseKeyboard

/**
 * 英文全键盘逻辑
 *
 * 说明（硬边界）：
 * - 英文预测开关状态不在 Keyboard 内保存，状态源在对应的 EN-QWERTY compose 策略里。[file:31]
 * - Keyboard 只负责：发事件（toggle / 输入），以及渲染 UI（由 router/controller 推送状态）。[file:1]
 */
class EnQwertyKeyboard(
    context: Context,
    actions: ImeActions
) : BaseKeyboard(context, actions, R.layout.kbdqwerty), EnglishPredictUi {

    private var isCaps = false

    private val charLayout = "qwertyuiopasdfghjklzxcvbnm"

    /**
     * 数字/常用符号布局。
     * 使用原始字符串避免转义问题；并显式包含双引号 "。
     */
    private val numberLayout = """1234567890@#$%&-+()*\\':;!?"\""""

    /**
     * 另一套符号布局（你可以按喜好增删字符）。
     */
    private val symbolLayout = "~`|•√π÷×{}[]<>^°=£€¥¢©®™"

    // 0=Normal, 1=Num, 2=Sym
    private var subMode = 0

    /**
     * UI-only cache.
     *
     * Not a source of truth. It exists only to render button highlight without querying IME
     * from inside updateKeyLabels() (which may be called frequently).
     */
    private var englishPredictEnabledUi: Boolean = false

    override fun onActivate() {
        updateKeyLabels()
        // Do not call ime.getEnglishPredictEnabled() here.
        // The router/controller will push the correct state via setEnglishPredictEnabled(...)
        // on keyboard change (see ImeActionDispatcher.onKeyboardChanged hook). [file:1]
    }

    override fun setEnglishPredictEnabled(enabled: Boolean) {
        // UI only; state source lives in strategy. [file:31]
        englishPredictEnabledUi = enabled
        rootView.findViewById<Button>(R.id.btnengpredict)
            ?.setTextColor(if (enabled) Color.CYAN else Color.BLACK)
    }

    override fun handleKeyPress(button: Button) {
        val id = button.id
        val keyLabel = button.text.toString()

        when (id) {
            R.id.btnshift -> {
                isCaps = !isCaps
                updateKeyLabels()
            }

            R.id.btnmodenum -> {
                subMode = if (subMode == 1) 0 else 1
                updateKeyLabels()
            }

            R.id.btnmodesym -> {
                subMode = if (subMode == 2) 0 else 2
                updateKeyLabels()
            }

            R.id.btnlang -> ime.switchToChineseMode()

            R.id.btnengpredict -> {
                // Toggle is routed to the current English main-mode strategy (per-mode independent). [file:1]
                ime.toggleEnglishPredict()
                // UI highlight will be refreshed by router->controller push (syncEnglishPredictUi). [file:1]
            }

            R.id.btndot -> ime.commitText(".")
            R.id.btncomma -> ime.commitText(",")

            else -> {
                if (isSpecialKey(keyLabel)) {
                    ime.handleSpecialKey(keyLabel)
                } else {
                    // Route everything through composing input.
                    // Strategy decides: predict on -> mutate session; predict off -> direct commit. [file:31]
                    ime.handleComposingInput(keyLabel)
                }
            }
        }
    }

    private fun isSpecialKey(text: String): Boolean {
        return text == "SPACE" || text == "⏎" || text == "ABC" || text == "?#"
    }

    private fun updateKeyLabels() {
        var keyIndex = 0

        fun refresh(view: View) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) refresh(view.getChildAt(i))
                return
            }

            if (view !is Button) return

            when (view.id) {
                R.id.btnshift -> {
                    view.text = if (isCaps) "⬆" else "⇧"
                    return
                }

                R.id.btnmodenum -> {
                    view.text = if (subMode == 1) "ABC" else "123"
                    return
                }

                R.id.btnmodesym -> {
                    view.text = if (subMode == 2) "ABC" else "?#"
                    return
                }

                R.id.btnlang -> {
                    view.text = "En"
                    return
                }

                R.id.btnengpredict -> {
                    view.visibility = View.VISIBLE
                    view.setTextColor(if (englishPredictEnabledUi) Color.CYAN else Color.BLACK)
                    return
                }

                R.id.btncomma -> {
                    view.text = ","
                    return
                }

                R.id.btndot -> {
                    view.text = "."
                    return
                }
            }

            // btndelqwerty: handled by BaseKeyboard; also skip SPACE / ENTER placeholders
            if (view.id == R.id.btndelqwerty || view.text == "SPACE" || view.text == "⏎") return

            if (keyIndex >= charLayout.length) return

            val newLabel = when (subMode) {
                1 -> numberLayout.getOrNull(keyIndex)?.toString().orEmpty()
                2 -> symbolLayout.getOrNull(keyIndex)?.toString().orEmpty()
                else -> {
                    var s = charLayout[keyIndex].toString()
                    if (isCaps) s = s.uppercase()
                    s
                }
            }

            if (newLabel.isNotEmpty()) {
                view.text = newLabel
            }
            keyIndex++
        }

        refresh(rootView)
    }
}
