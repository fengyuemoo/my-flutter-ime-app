package com.example.myapp.keyboard

import android.content.Context
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.KeyboardType
import com.example.myapp.keyboard.cn.qwerty.CnQwertyKeyboard
import com.example.myapp.keyboard.cn.t9.CnT9Keyboard
import com.example.myapp.keyboard.en.qwerty.EnQwertyKeyboard
import com.example.myapp.keyboard.en.t9.EnT9Keyboard
import com.example.myapp.keyboard.numeric.NumericKeyboard
import com.example.myapp.keyboard.panel.SymbolKeyboard

class KeyboardRouter(private val context: Context, private val ime: ImeActions) {

    private var currentKeyboard: BaseKeyboard? = null
    private var previousKeyboardType: KeyboardType = KeyboardType.CNT9

    fun createKeyboard(keyboardType: KeyboardType): BaseKeyboard {
        val newKeyboard = when (keyboardType) {
            KeyboardType.CNT9 -> CnT9Keyboard(context, ime)
            KeyboardType.ENT9 -> EnT9Keyboard(context, ime)
            Keyboard.CNQWERTY -> CnQwertyKeyboard(context, ime)
            KeyboardType.ENQWERTY -> EnQwertyKeyboard(context, ime)
            KeyboardType.NUMERIC -> NumericKeyboard(context, ime)
            KeyboardType.SYMBOL -> SymbolKeyboard(context, ime)
        }
        currentKeyboard = newKeyboard
        return newKeyboard
    }

    fun switchToPreviousKeyboard() {
        // You may want to enhance this logic, e.g., by keeping a history stack
        val keyboard = createKeyboard(previousKeyboardType)
        ime.onKeyboardChanged(keyboard)
    }

    fun onKeyboardChanged(keyboard: BaseKeyboard) {
        // Update previous keyboard type if we are not switching to a temporary keyboard
        if (keyboard !is NumericKeyboard && keyboard !is SymbolKeyboard) {
            previousKeyboardType = keyboard.getKeyboardType()
        }
        currentKeyboard = keyboard
    }

    private fun BaseKeyboard.getKeyboardType(): KeyboardType {
        return when (this) {
            is CnT9Keyboard -> KeyboardType.CNT9
            is EnT9Keyboard -> KeyboardType.ENT9
            is CnQwertyKeyboard -> KeyboardType.CNQWERTY
            is EnQwertyKeyboard -> KeyboardType.ENQWERTY
            is NumericKeyboard -> KeyboardType.NUMERIC
            is SymbolKeyboard -> KeyboardType.SYMBOL
            else -> throw IllegalStateException("Unknown keyboard type")
        }
    }
}
