package com.example.myapp.dict.impl

object T9Lookup {
    private val t9Lookup: CharArray = CharArray(128) { 0.toChar() }

    init {
        for (c in 'a'..'c') t9Lookup[c.code] = '2'
        for (c in 'd'..'f') t9Lookup[c.code] = '3'
        for (c in 'g'..'i') t9Lookup[c.code] = '4'
        for (c in 'j'..'l') t9Lookup[c.code] = '5'
        for (c in 'm'..'o') t9Lookup[c.code] = '6'
        for (c in 'p'..'s') t9Lookup[c.code] = '7'
        for (c in 't'..'v') t9Lookup[c.code] = '8'
        for (c in 'w'..'z') t9Lookup[c.code] = '9'
    }

    fun encodeLetters(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            val idx = c.code.coerceIn(0, 127)
            val num = t9Lookup[idx]
            if (num != 0.toChar()) sb.append(num)
        }
        return sb.toString()
    }

    fun charsFromDigit(digit: Char): List<String> {
        return when (digit) {
            '2' -> listOf("a", "b", "c")
            '3' -> listOf("d", "e", "f")
            '4' -> listOf("g", "h", "i")
            '5' -> listOf("j", "k", "l")
            '6' -> listOf("m", "n", "o")
            '7' -> listOf("p", "q", "r", "s")
            '8' -> listOf("t", "u", "v")
            '9' -> listOf("w", "x", "y", "z")
            else -> emptyList()
        }
    }
}
