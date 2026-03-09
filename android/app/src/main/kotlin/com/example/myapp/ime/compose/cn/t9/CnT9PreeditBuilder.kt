package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.dict.impl.T9Lookup
import com.example.myapp.ime.compose.common.ComposingSession
import java.util.Locale

class CnT9PreeditBuilder {

    fun build(
        session: ComposingSession,
        composingPreviewOverride: String?,
        enterCommitOverride: String?,
        focusedSegmentIndex: Int? = null
    ): CnT9PreeditModel {
        val committedPrefix = session.committedPrefix
            .trim()
            .takeIf { it.isNotEmpty() }

        val normalizedOverride = normalizeDisplayText(composingPreviewOverride)
        val overrideCore = extractCoreText(
            fullText = normalizedOverride,
            committedPrefix = committedPrefix
        )

        val lockedPrefixSegments = session.pinyinStack
            .map { normalizeSyllable(it) }
            .filter { it.isNotEmpty() }

        val overrideSegments = when {
            !overrideCore.isNullOrEmpty() -> splitCoreSegments(overrideCore)
            else -> emptyList()
        }

        val mergedSegments = mergeLockedPrefixWithPlannedSuffix(
            lockedPrefixSegments = lockedPrefixSegments,
            plannedSegments = overrideSegments
        )

        val safeFocusedIndex = focusedSegmentIndex?.takeIf { it in mergedSegments.indices }
        val lockedPrefixCount = lockedPrefixSegments.size.coerceAtMost(mergedSegments.size)

        val modelSegments = mergedSegments.mapIndexed { index, seg ->
            CnT9PreeditSegment(
                text = seg,
                isFocused = index == safeFocusedIndex,
                isLocked = index < lockedPrefixCount
            )
        }

        val mergedCoreText = mergedSegments
            .joinToString("'")
            .takeIf { it.isNotEmpty() }

        val fallbackText = buildFallbackText(
            session = session,
            committedPrefix = committedPrefix,
            sessionSegments = lockedPrefixSegments
        )

        val displayText = when {
            !mergedCoreText.isNullOrEmpty() -> buildDisplayText(
                committedPrefix = committedPrefix,
                coreText = mergedCoreText
            )
            else -> normalizedOverride ?: fallbackText
        }

        val enterCommitText = sanitizeEnterCommitText(
            enterCommitOverride ?: mergedCoreText
        )

        return CnT9PreeditModel(
            text = displayText,
            coreText = mergedCoreText,
            segments = modelSegments,
            focusedSegmentIndex = safeFocusedIndex,
            enterCommitText = enterCommitText
        )
    }

    private fun normalizeDisplayText(raw: String?): String? {
        return raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractCoreText(
        fullText: String?,
        committedPrefix: String?
    ): String? {
        if (fullText.isNullOrEmpty()) return null
        if (committedPrefix.isNullOrEmpty()) return fullText.takeIf { it.isNotBlank() }

        return if (fullText.startsWith(committedPrefix)) {
            fullText.removePrefix(committedPrefix)
                .trim()
                .takeIf { it.isNotEmpty() }
        } else {
            fullText.takeIf { it.isNotBlank() }
        }
    }

    private fun normalizeSyllable(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("’", "'")
            .replace("'", "")
            .replace('v', 'ü')
            .filter { it in 'a'..'z' || it == 'ü' }
    }

    private fun splitCoreSegments(core: String): List<String> {
        val normalized = core
            .trim()
            .lowercase(Locale.ROOT)
            .replace("’", "'")

        if (normalized.isEmpty()) return emptyList()

        return normalized
            .split("'")
            .map { part ->
                part.filter { it in 'a'..'z' || it == 'ü' || it == 'v' }
                    .replace('v', 'ü')
            }
            .filter { it.isNotEmpty() }
    }

    private fun mergeLockedPrefixWithPlannedSuffix(
        lockedPrefixSegments: List<String>,
        plannedSegments: List<String>
    ): List<String> {
        if (lockedPrefixSegments.isEmpty()) return plannedSegments
        if (plannedSegments.isEmpty()) return lockedPrefixSegments

        if (plannedSegments.size <= lockedPrefixSegments.size) {
            return lockedPrefixSegments
        }

        return lockedPrefixSegments + plannedSegments.drop(lockedPrefixSegments.size)
    }

    private fun buildDisplayText(
        committedPrefix: String?,
        coreText: String?
    ): String? {
        if (committedPrefix.isNullOrEmpty() && coreText.isNullOrEmpty()) return null

        return buildString {
            if (!committedPrefix.isNullOrEmpty()) {
                append(committedPrefix)
            }
            if (!coreText.isNullOrEmpty()) {
                append(coreText)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun buildFallbackText(
        session: ComposingSession,
        committedPrefix: String?,
        sessionSegments: List<String>
    ): String? {
        if (sessionSegments.isNotEmpty()) {
            return buildString {
                if (!committedPrefix.isNullOrEmpty()) {
                    append(committedPrefix)
                }
                append(sessionSegments.joinToString("'"))
            }.takeIf { it.isNotEmpty() }
        }

        if (session.rawT9Digits.length == 1) {
            val fallback = T9Lookup.charsFromDigit(session.rawT9Digits.first())
                .firstOrNull()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }

            if (fallback != null) {
                return buildString {
                    if (!committedPrefix.isNullOrEmpty()) {
                        append(committedPrefix)
                    }
                    append(fallback)
                }.takeIf { it.isNotEmpty() }
            }
        }

        return committedPrefix
            ?.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeEnterCommitText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace("’", "'")
            .filter { it in 'a'..'z' || it == 'ü' || it == 'v' || it == '\'' }
            .replace('ü', 'v')
            .takeIf { it.isNotEmpty() }
    }
}
