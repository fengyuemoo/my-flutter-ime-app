package com.example.myapp.keyboard

import android.content.Context
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.keyboard.cn.qwerty.CnQwertyKeyboard
import com.example.myapp.keyboard.cn.t9.CnT9Keyboard
import com.example.myapp.keyboard.core.IKeyboardMode
import com.example.myapp.keyboard.core.KeyboardRegistry
import com.example.myapp.keyboard.core.KeyboardType
import com.example.myapp.keyboard.en.qwerty.EnQwertyKeyboard
import com.example.myapp.keyboard.en.t9.EnT9Keyboard
import com.example.myapp.keyboard.panel.NumericKeyboard
import com.example.myapp.keyboard.panel.SymbolKeyboard

class DefaultKeyboardRegistry(
    context: Context,
    ime: ImeActions,
    modeProvider: () -> KeyboardMode
) : KeyboardRegistry {

    private val map: Map<KeyboardType, IKeyboardMode> = mapOf(
        KeyboardType.CNQWERTY to CnQwertyKeyboard(context, ime),
        KeyboardType.ENQWERTY to EnQwertyKeyboard(context, ime),
        KeyboardType.CNT9 to CnT9Keyboard(context, ime),
        KeyboardType.ENT9 to EnT9Keyboard(context, ime),
        KeyboardType.NUMERIC to NumericKeyboard(context, ime),
        KeyboardType.SYMBOL to SymbolKeyboard(context, ime, modeProvider)
    )

    override fun get(type: KeyboardType): IKeyboardMode =
        map[type] ?: error("Keyboard not registered: $type")
}
