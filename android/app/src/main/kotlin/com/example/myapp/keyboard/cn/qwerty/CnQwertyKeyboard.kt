package com.example.myapp.keyboard.cn.qwerty

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.EnterCommitPolicy

/**
 * 中文全键盘逻辑
 */
class CnQwertyKeyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.kbdqwerty), EnterCommitPolicy {

    private val charLayout = "qwertyuiopasdfghjklzxcvbnm"
    private val numberLayout = "1234567890@#$%&-+()*\\\"':;!?"
    private val symbolLayout = "~`|•√π÷×{}[]<>^°=£€¥¢©®™"

    // 0: 字母, 1: 数字, 2: 符号
    private var subMode = 0

    override fun onActivate() {
        updateKeyLabels()
    }

    override fun handleKeyPress(button: Button) {
        val id = button.id
        val keyLabel = button.text.toString()

        when (id) {
            // 分词：这里沿用你原逻辑，输入一个 apostrophe
            R.id.btnshift -> ime.handleComposingInput("'")

            R.id.btnmodenum -> {
                subMode = if (subMode == 1) 0 else 1
                updateKeyLabels()
            }

            R.id.btnmodesym -> {
                subMode = if (subMode == 2) 0 else 2
                updateKeyLabels()
            }

            R.id.btnlang -> ime.switchToEnglishMode()

            R.id.btndot -> ime.commitText("。")
            R.id.btncomma -> ime.commitText("，")

            else -> {
                if (isSpecialKey(keyLabel)) {
                    ime.handleSpecialKey(keyLabel)
                } else {
                    if (subMode != 0) {
                        ime.commitText(keyLabel)
                    } else {
                        ime.handleComposingInput(keyLabel)
                    }
                }
            }
        }
    }

    /**
     * Enter 在 composing 状态下的提交策略（用于让 ImeActionDispatcher 不再特判 CnQwertyKeyboard）。
     */
    override fun getTextToCommitOnEnterWhileComposing(session: ComposingSession): String? {
        if (!session.isComposing()) return null
        return session.committedPrefix + session.qwertyInput.lowercase()
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

            // 固定功能键
            when (view.id) {
                R.id.btnshift -> {
                    view.text = "分词"
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
                    view.text = "中"
                    return
                }
                R.id.btnengpredict -> {
                    view.visibility = View.GONE
                    return
                }
                R.id.btncomma -> {
                    view.text = "，"
                    return
                }
                R.id.btndot -> {
                    view.text = "。"
                    return
                }
            }

            // 删除键/空格/回车不改
            if (view.id == R.id.btndelqwerty || view.text == "SPACE" || view.text == "⏎") return

            if (keyIndex >= charLayout.length) return

            val newLabel = when (subMode) {
                1 -> if (keyIndex < numberLayout.length) numberLayout[keyIndex].toString() else ""
                2 -> if (keyIndex < symbolLayout.length) symbolLayout[keyIndex].toString() else ""
                else -> charLayout[keyIndex].toString().uppercase()
            }

            if (newLabel.isNotEmpty()) {
                view.text = newLabel
                keyIndex++
            }
        }

        refresh(rootView)
    }
}
