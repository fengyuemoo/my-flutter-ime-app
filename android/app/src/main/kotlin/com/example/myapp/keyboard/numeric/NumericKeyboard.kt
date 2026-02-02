package com.example.myapp.keyboard.numeric

import android.content.Context
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.keyboard.core.BaseKeyboard

class NumericKeyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.kbd_numeric) {

    override fun handleKeyPress(button: Button) {
        when (button.id) {
            R.id.btn_num_back -> ime.switchToPreviousKeyboard()
            R.id.btn_num_del -> ime.handleBackspace()
            else -> {
                val label = button.text.toString()
                if (label.isNotEmpty()) {
                    ime.commitText(label)
                }
            }
        }
    }
}
