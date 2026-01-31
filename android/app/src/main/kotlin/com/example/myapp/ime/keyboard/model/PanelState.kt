package com.example.myapp.ime.keyboard.model

import com.example.myapp.keyboard.core.PanelType

sealed class PanelState {
    data object None : PanelState()
    data class Open(val type: PanelType) : PanelState()
}
