package com.example.myapp.keyboard.core

/**
 * Optional capability for keyboards that can show a sidebar list (e.g. CN T9 pinyin list).
 * Keeping this as an interface avoids coupling candidate/session logic to a concrete keyboard class.
 */
interface ISidebarHost {
    fun updateSideBar(items: List<String>)
}
