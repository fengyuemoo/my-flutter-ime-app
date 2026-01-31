package com.example.myapp.keyboard.en.t9

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.ui.EnglishPredictUi
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.T9Mode

class EnT9Keyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.kbdt9), T9Mode, EnglishPredictUi {

    // UI-only cache (NOT a source of truth).
    // The source of truth lives in EnT9ComposeStrategy. [file:32]
    private var englishPredictEnabledUi: Boolean = true

    override fun onActivate() {
        rootView.findViewById<View>(R.id.recyclert9pinyinsidebar)?.visibility = View.GONE
        rootView.findViewById<View>(R.id.t9puncsidebar)?.visibility = View.VISIBLE

        rootView.findViewById<Button>(R.id.t9punccomma)?.text = ","
        rootView.findViewById<Button>(R.id.t9puncperiod)?.text = "."
        rootView.findViewById<Button>(R.id.t9puncquestion)?.text = "?"
        rootView.findViewById<Button>(R.id.t9puncexclaim)?.text = "!"

        updateLangButton()

        // Do NOT pull predict state from IME here.
        // Router will push the correct state via:
        // - KeyboardController.onKeyboardChanged -> dispatcher.syncEnglishPredictUi()
        // - dispatcher.setEnglishPredict/toggleEnglishPredict -> syncEnglishPredictUi()
    }

    override fun setEnglishPredictEnabled(enabled: Boolean) {
        englishPredictEnabledUi = enabled
        val predictButton = rootView.findViewById<Button>(R.id.t9btnengpredict)
        predictButton?.setTextColor(if (enabled) Color.CYAN else Color.BLACK)
    }

    override fun handleKeyPress(button: Button) {
        val id = button.id

        when (id) {
            R.id.t9btnlang -> ime.switchToChineseMode()
            R.id.t9btn123 -> ime.switchToNumericMode()
            R.id.t9btnsym -> ime.openSymbolPanel()

            R.id.t9btnengpredict -> {
                // Only trigger toggle; state lives in strategy (per English main mode). [file:31][file:32]
                ime.toggleEnglishPredict()
                // No manual UI sync needed: dispatcher will push state to setEnglishPredictEnabled(...).
            }

            R.id.t9space -> ime.handleSpaceKey()

            R.id.t9punccomma -> ime.commitText(",")
            R.id.t9puncperiod -> ime.commitText(".")
            R.id.t9puncquestion -> ime.commitText("?")
            R.id.t9puncexclaim -> ime.commitText("!")

            // Digits: always forward to IME; strategy decides predict vs multi-tap. [file:32]
            R.id.t9key1 -> ime.handleT9Input("1")
            R.id.t9key2 -> ime.handleT9Input("2")
            R.id.t9key3 -> ime.handleT9Input("3")
            R.id.t9key4 -> ime.handleT9Input("4")
            R.id.t9key5 -> ime.handleT9Input("5")
            R.id.t9key6 -> ime.handleT9Input("6")
            R.id.t9key7 -> ime.handleT9Input("7")
            R.id.t9key8 -> ime.handleT9Input("8")
            R.id.t9key9 -> ime.handleT9Input("9")
            R.id.t9key0 -> ime.handleT9Input("0")

            // Keep: "re-enter" clears composing (strategy will clear session + span). [file:32]
            R.id.t9btnreenter -> ime.clearComposing()

            R.id.t9btndel -> ime.handleBackspace()

            else -> {
                val label = button.text?.toString().orEmpty()
                // Preserve the old "special key" passthrough behavior.
                if (label.contains("⏎") || label.contains("\\n")) {
                    ime.handleSpecialKey(label)
                }
            }
        }

        // Optional: If you want immediate visual feedback without waiting for push,
        // you could re-render from IME here, but we intentionally avoid that to keep
        // the boundary strict (keyboard view never reads predict state).
        //
        // if (id == R.id.t9btnengpredict) {
        //     setEnglishPredictEnabled(ime.getEnglishPredictEnabled())
        // }
    }

    private fun updateLangButton() {
        rootView.findViewById<Button>(R.id.t9btnlang)?.text = "En"
        rootView.findViewById<Button>(R.id.t9btnengpredict)?.visibility = View.VISIBLE

        // Keep the color consistent with last known UI value (until IME pushes fresh state).
        val predictButton = rootView.findViewById<Button>(R.id.t9btnengpredict)
        predictButton?.setTextColor(if (englishPredictEnabledUi) Color.CYAN else Color.BLACK)
    }
}
