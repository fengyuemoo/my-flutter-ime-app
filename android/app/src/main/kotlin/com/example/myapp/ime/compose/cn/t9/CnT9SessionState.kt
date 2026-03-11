package com.example.myapp.ime.compose.cn.t9

import java.util.Locale

data class CnT9MaterializedSegment(
    val syllable: String,
    val digitChunk: String,
    val locked: Boolean = false,
    val localCuts: Set<Int> = emptySet()
) {
    val normalizedSyllable: String
        get() = syllable.trim().lowercase(Locale.ROOT)

    val normalizedDigitChunk: String
        get() = digitChunk.filter { it in '0'..'9' }
}

data class CnT9SessionState(
    val rawDigits: String = "",
    val committedPrefix: String = "",
    val materializedSegments: List<CnT9MaterializedSegment> = emptyList(),
    val manualCuts: Set<Int> = emptySet(),
    val focusedSegmentIndex: Int? = null,
    val selectedCandidateIndex: Int = 0,
    val isCandidatesExpanded: Boolean = false,
    val revision: Long = 0L
) {
    val normalizedRawDigits: String
        get() = rawDigits.filter { it in '0'..'9' }

    /** 已锁定（物化）的音节数量，即 materializedSegments.size */
    val lockedSegmentCount: Int
        get() = materializedSegments.size

    fun hasRawDigits(): Boolean = normalizedRawDigits.isNotEmpty()

    fun hasMaterializedSegments(): Boolean = materializedSegments.isNotEmpty()

    fun isComposing(): Boolean {
        return committedPrefix.isNotEmpty() ||
            hasRawDigits() ||
            hasMaterializedSegments()
    }

    fun totalMaterializedDigitCount(): Int {
        return materializedSegments.sumOf { it.normalizedDigitChunk.length }
    }

    fun safeFocusedSegmentIndex(): Int? {
        val idx = focusedSegmentIndex ?: return null
        return idx.takeIf { it in materializedSegments.indices }
    }

    fun normalizedManualCuts(): Set<Int> {
        val raw = normalizedRawDigits
        if (raw.isEmpty()) return emptySet()
        return manualCuts.filter { it in 1..raw.length }.toSet()
    }

    fun sanitized(): CnT9SessionState {
        val cleanRawDigits = normalizedRawDigits
        val cleanSegments = materializedSegments.map {
            it.copy(
                syllable = it.normalizedSyllable,
                digitChunk = it.normalizedDigitChunk,
                localCuts = it.localCuts.filter { cut ->
                    cut in 1..it.normalizedDigitChunk.length
                }.toSet()
            )
        }

        val base = copy(
            rawDigits = cleanRawDigits,
            materializedSegments = cleanSegments,
            manualCuts = manualCuts.filter { it in 1..cleanRawDigits.length }.toSet(),
            focusedSegmentIndex = focusedSegmentIndex?.takeIf { it in cleanSegments.indices },
            selectedCandidateIndex = selectedCandidateIndex.coerceAtLeast(0)
        )

        if (!base.isComposing()) {
            return base.copy(
                focusedSegmentIndex = null,
                selectedCandidateIndex = 0,
                isCandidatesExpanded = false
            )
        }

        return base
    }
}
