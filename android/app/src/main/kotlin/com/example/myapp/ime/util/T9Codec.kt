package com.example.myapp.ime.util

import com.example.myapp.dict.impl.T9Lookup

object T9Codec {
    fun encode(input: String): String {
        return T9Lookup.encodeLetters(input.lowercase())
    }
}
