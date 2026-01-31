package com.example.myapp.ime.ui

import android.view.View
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.keyboard.KeyboardController

class ToolbarController(
    private val rootView: View,
    private val keyboardControllerProvider: () -> KeyboardController?
) {

    fun refresh() {
        val kc = keyboardControllerProvider() ?: return

        // 布局按钮：Qwerty / T9
        // 资源 id 以 ime_container.xml 中的命名为准：btntoollayout（无下划线）[file:9][file:32]
        val btnLayout = rootView.findViewById<Button?>(R.id.btntoollayout)
        btnLayout?.text = if (!kc.useT9Layout) "⌨" else "▦"

        // 语言按钮：不放在顶部工具栏（语言切换由键盘内部 btnlang / t9btnlang 完成）
        // 所以这里不再查找/更新 btn_tool_lang。
    }
}
