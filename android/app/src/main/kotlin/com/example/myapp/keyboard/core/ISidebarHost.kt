package com.example.myapp.keyboard.core

/**
 * Optional capability for keyboards that can show a sidebar list (e.g. CN T9 pinyin list).
 * Keeping this as an interface avoids coupling candidate/session logic to a concrete keyboard class.
 */
interface ISidebarHost {
    /**
     * @param items           sidebar 展示的拼音音节列表
     * @param title           当前焦点段对应的原始数字串（如 "94664"），用于消歧模式顶部标题；
     *                        null 表示正常输入模式，不展示标题
     * @param resegmentPaths  当前焦点段所有合法切分路径，供「重切分」UI 展示；
     *                        每项是一条路径（音节列表），如 [["zhong"], ["zhi","ong"]]；
     *                        正常输入模式或非 T9 模式下为空列表
     */
    fun updateSideBar(
        items: List<String>,
        title: String? = null,
        resegmentPaths: List<List<String>> = emptyList()
    )
}
