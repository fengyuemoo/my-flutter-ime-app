package com.example.myapp.ime.mode

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession

interface ImeModeHandler {

    data class Output(
        val candidates: ArrayList<Candidate>,
        val pinyinSidebar: List<String> = emptyList(),
        /**
         * 当前焦点段对应的原始数字串，供 UI 在 sidebar 顶部展示（如 "94664"）。
         * null 表示处于正常输入模式（无焦点段，展示 rawDigits 前段）。
         */
        val sidebarTitle: String? = null,
        /**
         * 当前焦点段的所有合法拼音切分路径，供 UI「重切分」功能展示。
         *
         * 对应规则清单「音节栏的重新切分」：
         *  - 每一项是一条完整路径（音节列表），如 [["zhong"], ["zhi","ong"]]
         *  - 第 0 条是最优路径（最长匹配贪婪优先）
         *  - 正常输入模式（无焦点）或非 T9 模式下为空列表
         */
        val resegmentPaths: List<List<String>> = emptyList(),
        /**
         * Optional UI composing preview override (mainly for CN preedit segmentation / T9 preview line).
         * When null, UI should fall back to session.displayText(...).
         */
        val composingPreviewText: String? = null,
        /**
         * Optional direct-commit text on Enter for specific modes (CN-T9 preview letters commit).
         * When null, Enter falls back to strategy.onEnter()/default behavior.
         */
        val enterCommitText: String? = null
    )

    fun build(
        session: ComposingSession,
        dictEngine: Dictionary,
        singleCharMode: Boolean
    ): Output
}
