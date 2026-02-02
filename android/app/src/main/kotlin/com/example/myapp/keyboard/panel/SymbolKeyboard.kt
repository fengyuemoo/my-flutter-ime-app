package com.example.myapp.keyboard.panel

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.ime.keyboard.model.KeyboardMode
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.PanelMode
import com.example.myapp.keyboard.core.RawCommitMode

class SymbolKeyboard(
    context: Context,
    ime: ImeActions,
    modeProvider: () -> KeyboardMode
) : BaseKeyboard(
    context,
    ime,
    if (modeProvider().isChinese) R.layout.symbols_cn else R.layout.symbols_en
), PanelMode, RawCommitMode {

    override fun handleKeyPress(button: Button) {
        val id = button.id
        val label = button.text.toString()

        if (id == R.id.btnsymback) {
            ime.closeSymbolPanel()
            return
        }

        ime.commitText(label)
    }

    override fun applyTheme(themeMode: Int) {
        super.applyTheme(themeMode)

        val bgLight = Color.parseColor("#DDDDDD")
        val bgDark = Color.parseColor("#222222")

        rootView.findViewById<View>(R.id.symbolscrollroot)
            ?.setBackgroundColor(if (themeMode == 1) bgDark else bgLight)
    }
}
