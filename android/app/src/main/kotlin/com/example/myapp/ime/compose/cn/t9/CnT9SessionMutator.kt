package com.example.myapp.ime.compose.cn.t9

class CnT9SessionMutator(
    initialState: CnT9SessionState = CnT9SessionState()
) {
    var state: CnT9SessionState = initialState.sanitized()
        private set

    fun replaceState(newState: CnT9SessionState): CnT9SessionState {
        state = newState.sanitized().copy(revision = state.revision + 1)
        return state
    }

    fun clearAll(): CnT9SessionState {
        state = CnT9SessionState(revision = state.revision + 1)
        return state
    }

    fun clearComposingContent(): CnT9SessionState {
        return replaceState(
            state.copy(
                rawDigits = "",
                materializedSegments = emptyList(),
                manualCuts = emptySet(),
                focusedSegmentIndex = null,
                selectedCandidateIndex = 0,
                isCandidatesExpanded = false
            )
        )
    }

    fun appendDigit(digit: Char): CnT9SessionState {
        return appendDigits(digit.toString())
    }

    fun appendDigits(digits: String): CnT9SessionState {
        val clean = digits.filter { it in '0'..'9' }
        if (clean.isEmpty()) return state

        return replaceState(
            state.copy(
                rawDigits = state.rawDigits + clean,
                selectedCandidateIndex = 0,
                isCandidatesExpanded = false
            )
        )
    }

    fun insertManualCutAtEnd(): CnT9SessionState {
        val len = state.rawDigits.length
        if (len <= 0) return state

        return replaceState(
            state.copy(
                manualCuts = state.manualCuts + len
            )
        )
    }

    fun setFocusedSegment(index: Int?): CnT9SessionState {
        val safeIndex = index?.takeIf { it in state.materializedSegments.indices }
        return replaceState(
            state.copy(
                focusedSegmentIndex = safeIndex
            )
        )
    }

    fun setSelectedCandidateIndex(index: Int): CnT9SessionState {
        return replaceState(
            state.copy(
                selectedCandidateIndex = index.coerceAtLeast(0)
            )
        )
    }

    fun setCandidatesExpanded(expanded: Boolean): CnT9SessionState {
        return replaceState(
            state.copy(
                isCandidatesExpanded = expanded
            )
        )
    }

    fun pushMaterializedSegment(
        syllable: String,
        digitChunk: String,
        locked: Boolean = false
    ): CnT9SessionState {
        val cleanSyllable = syllable.trim()
        val cleanDigits = digitChunk.filter { it in '0'..'9' }
        if (cleanSyllable.isEmpty()) return state

        val consume = cleanDigits.length.coerceAtMost(state.rawDigits.length)
        val consumedDigits = state.rawDigits.take(consume)

        val (consumedCuts, remainingCuts) = splitCutsByConsumedPrefix(
            manualCuts = state.manualCuts,
            consumedPrefixLength = consume
        )

        val newSegment = CnT9MaterializedSegment(
            syllable = cleanSyllable,
            digitChunk = consumedDigits,
            locked = locked,
            localCuts = consumedCuts.toSet()
        )

        return replaceState(
            state.copy(
                rawDigits = state.rawDigits.drop(consume),
                materializedSegments = state.materializedSegments + newSegment,
                manualCuts = remainingCuts.toSet(),
                focusedSegmentIndex = (state.materializedSegments.size).takeIf { locked },
                selectedCandidateIndex = 0,
                isCandidatesExpanded = false
            )
        )
    }

    fun replaceMaterializedSegment(
        index: Int,
        syllable: String,
        digitChunk: String,
        locked: Boolean = false
    ): CnT9SessionState {
        if (index !in state.materializedSegments.indices) return state

        val cleanSyllable = syllable.trim()
        val cleanDigits = digitChunk.filter { it in '0'..'9' }
        if (cleanSyllable.isEmpty()) return state

        val updated = state.materializedSegments.toMutableList()
        val old = updated[index]

        updated[index] = old.copy(
            syllable = cleanSyllable,
            digitChunk = cleanDigits,
            locked = locked
        )

        return replaceState(
            state.copy(
                materializedSegments = updated
            )
        )
    }

    fun lockSegment(index: Int, locked: Boolean = true): CnT9SessionState {
        if (index !in state.materializedSegments.indices) return state

        val updated = state.materializedSegments.toMutableList()
        val old = updated[index]
        updated[index] = old.copy(locked = locked)

        return replaceState(
            state.copy(
                materializedSegments = updated,
                focusedSegmentIndex = index
            )
        )
    }

    fun dematerializeFocusedOrLastSegment(): CnT9SessionState {
        if (state.materializedSegments.isEmpty()) return state

        val index = state.focusedSegmentIndex
            ?.takeIf { it in state.materializedSegments.indices }
            ?: state.materializedSegments.lastIndex

        val target = state.materializedSegments[index]
        val remainingSegments = state.materializedSegments.toMutableList().also {
            it.removeAt(index)
        }

        val restoredDigits = target.normalizedDigitChunk
        val restoredCuts = target.localCuts.filter { it in 1..restoredDigits.length }.toSet()

        val shiftedExistingCuts = state.manualCuts.map { it + restoredDigits.length }.toSet()
        val mergedCuts = shiftedExistingCuts + restoredCuts

        return replaceState(
            state.copy(
                rawDigits = restoredDigits + state.rawDigits,
                materializedSegments = remainingSegments,
                manualCuts = mergedCuts,
                focusedSegmentIndex = index
                    .takeIf { remainingSegments.isNotEmpty() }
                    ?.coerceAtMost(remainingSegments.lastIndex),
                selectedCandidateIndex = 0,
                isCandidatesExpanded = false
            )
        )
    }

    fun backspace(): CnT9SessionState {
        if (state.rawDigits.isNotEmpty()) {
            val newRaw = state.rawDigits.dropLast(1)
            val newCuts = state.manualCuts.filter { it in 1..newRaw.length }.toSet()

            return replaceState(
                state.copy(
                    rawDigits = newRaw,
                    manualCuts = newCuts,
                    selectedCandidateIndex = 0,
                    isCandidatesExpanded = false
                )
            )
        }

        if (state.materializedSegments.isNotEmpty()) {
            return dematerializeFocusedOrLastSegment()
        }

        if (state.committedPrefix.isNotEmpty()) {
            return replaceState(
                state.copy(
                    committedPrefix = state.committedPrefix.dropLast(1)
                )
            )
        }

        return state
    }

    private fun splitCutsByConsumedPrefix(
        manualCuts: Set<Int>,
        consumedPrefixLength: Int
    ): Pair<List<Int>, List<Int>> {
        if (manualCuts.isEmpty() || consumedPrefixLength <= 0) {
            return emptyList<Int>() to manualCuts.sorted()
        }

        val consumed = ArrayList<Int>()
        val remaining = ArrayList<Int>()

        for (cut in manualCuts) {
            when {
                cut <= consumedPrefixLength -> consumed.add(cut)
                else -> remaining.add(cut - consumedPrefixLength)
            }
        }

        consumed.sort()
        remaining.sort()
        return consumed to remaining
    }
}
