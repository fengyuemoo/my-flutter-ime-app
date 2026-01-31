package com.example.myapp.keyboard.core

interface KeyboardRegistry {
    fun get(type: KeyboardType): IKeyboardMode
}
