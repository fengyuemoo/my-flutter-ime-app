package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.mode.cn.CnT9SentencePlanner
import java.util.Locale

object CnT9PreeditFormatter {

    fun format(
        session: ComposingSession,
        dict: Dictionary,
        engineOverride: String? = null
    ): String? {
        val override = engineOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) return override

        val committedPrefix = session.committedPrefix.trim()
        val lockedSegs = session.pinyinStack
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        val rawDigits = session.rawT9Digits

        if (committedPrefix.isEmpty() && lockedSegs.isEmpty() && rawDigits.isEmpty()) return null

        val plannedSegs: List<String> = when {
            rawDigits.isEmpty() -> emptyList()
            dict.isLoaded -> {
                CnT9SentencePlanner.planAll(
                    digits = rawDigits,
                    manualCuts = session.t9ManualCuts,
                    dict = dict
                ).firstOrNull()
                    ?.segments
                    ?.map { it.trim().lowercase(Locale.ROOT) }
                    ?.filter { it.isNotEmpty() }
                    ?: fallbackLetters(rawDigits)
            }
            else -> fallbackLetters(rawDigits)
        }

        val allSegs = lockedSegs + plannedSegs

        return buildString {
            if (committedPrefix.isNotEmpty()) append(committedPrefix)
            if (allSegs.isNotEmpty()) append(allSegs.joinToString("'"))
        }.takeIf { it.isNotEmpty() }
    }

    private fun fallbackLetters(digits: String): List<String> {
        return digits.map { d ->
            T9Lookup.charsFromDigit(d).firstOrNull()?.lowercase(Locale.ROOT) ?: d.toString()
        }
    }
}
