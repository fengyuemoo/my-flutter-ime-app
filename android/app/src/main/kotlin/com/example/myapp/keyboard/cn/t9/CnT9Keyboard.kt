package com.example.myapp.keyboard.cn.t9

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapp.R
import com.example.myapp.SidePinyinAdapter
import com.example.myapp.ime.api.ImeActions
import com.example.myapp.keyboard.core.BaseKeyboard
import com.example.myapp.keyboard.core.PinyinSidebarHost
import com.example.myapp.keyboard.core.T9Mode

class CnT9Keyboard(
    context: Context,
    ime: ImeActions
) : BaseKeyboard(context, ime, R.layout.kbd_t9_cn), PinyinSidebarHost, T9Mode {

    private val recyclerSidebar: RecyclerView =
        rootView.findViewById(R.id.recyclert9pinyinsidebar)

    private val puncSidebar: LinearLayout =
        rootView.findViewById(R.id.t9puncsidebar)

    private val adapterSidebar = SidePinyinAdapter { pinyin ->
        this.ime.onPinyinSidebarClick(pinyin)
    }

    init {
        recyclerSidebar.layoutManager = LinearLayoutManager(context)
        recyclerSidebar.adapter = adapterSidebar
    }

    override fun onActivate() {
        updatePinyinSidebar(emptyList())

        rootView.findViewById<Button>(R.id.t9btnlang)?.text = "中"
        rootView.findViewById<Button>(R.id.t9btnengpredict)?.visibility = View.GONE

        rootView.findViewById<Button>(R.id.t9punccomma)?.text = "，"
        rootView.findViewById<Button>(R.id.t9puncperiod)?.text = "。"
        rootView.findViewById<Button>(R.id.t9puncquestion)?.text = "？"
        rootView.findViewById<Button>(R.id.t9puncexclaim)?.text = "！"
    }

    override fun updatePinyinSidebar(pinyins: List<String>) {
        if (pinyins.isEmpty()) {
            recyclerSidebar.visibility = View.GONE
            puncSidebar.visibility = View.VISIBLE
            adapterSidebar.submitList(emptyList())
        } else {
            recyclerSidebar.visibility = View.VISIBLE
            puncSidebar.visibility = View.GONE
            adapterSidebar.submitList(pinyins)
        }
    }

    override fun handleKeyPress(button: Button) {
        when (button.id) {
            R.id.t9btnlang -> ime.switchToEnglishMode()

            R.id.t9btn123 -> ime.switchToNumericMode()
            R.id.t9btnsym -> ime.openSymbolPanel()

            // 1 = 分词（切分点）：不再把 "1" 混入 digits
            R.id.t9key1 -> ime.handleSpecialKey("分词")

            R.id.t9key2 -> ime.handleT9Input("2")
            R.id.t9key3 -> ime.handleT9Input("3")
            R.id.t9key4 -> ime.handleT9Input("4")
            R.id.t9key5 -> ime.handleT9Input("5")
            R.id.t9key6 -> ime.handleT9Input("6")
            R.id.t9key7 -> ime.handleT9Input("7")
            R.id.t9key8 -> ime.handleT9Input("8")
            R.id.t9key9 -> ime.handleT9Input("9")

            R.id.t9key0 -> ime.commitText("0")
            R.id.t9space -> ime.handleSpaceKey()
            R.id.t9btnreenter -> ime.clearComposing()

            R.id.t9punccomma -> ime.commitText("，")
            R.id.t9puncperiod -> ime.commitText("。")
            R.id.t9puncquestion -> ime.commitText("？")
            R.id.t9puncexclaim -> ime.commitText("！")

            else -> {
                if (button.text.toString().contains("⏎")) {
                    ime.handleSpecialKey("⏎")
                }
            }
        }
    }
}
