package com.example.myapp.ime.api

import com.example.myapp.keyboard.core.PanelType

interface PanelActions {
    fun openPanel(type: PanelType)
    fun closePanel()
}
