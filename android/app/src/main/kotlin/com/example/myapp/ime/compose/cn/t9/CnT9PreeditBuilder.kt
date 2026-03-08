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

        val fallbackSegments = session.pinyinStack
            .map { normalizeSyllable(it) }
            .filter { it.isNotEmpty() }

        val segments = when {
            !overrideCore.isNullOrEmpty() -> splitCoreSegments(overrideCore)
            fallbackSegments.isNotEmpty() -> fallbackSegments
            else -> emptyList()
        }

        val safeFocusedIndex = focusedSegmentIndex?.takeIf { it in segments.indices }

        val modelSegments = segments.mapIndexed { index, seg ->
            CnT9PreeditSegment(
                text = seg,
                isFocused = index == safeFocusedIndex,
                isLocked = false
            )
        }

        val fallbackText = buildFallbackText(
            session = session,
            committedPrefix = committedPrefix,
            sessionSegments = fallbackSegments
        )

        val displayText = normalizedOverride ?: fallbackText

        val coreText = when {
            !overrideCore.isNullOrEmpty() -> overrideCore
            fallbackSegments.isNotEmpty() -> fallbackSegments.joinToString("'")
            else -> null
        }

        val enterCommitText = sanitizeEnterCommitText(
            enterCommitOverride ?: coreText
        )

        return CnT9PreeditModel(
            text = displayText,
            coreText = coreText,
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

        val parts = normalized
            .split("'")
            .map { part ->
                part.filter { it in 'a'..'z' || it == 'ü' || it == 'v' }
                    .replace('v', 'ü')
            }
            .filter { it.isNotEmpty() }

        return parts
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
            .filter { it in 'a'..'z' || it == 'ü' || it == 'v' }
            .replace('ü', 'v')
            .takeIf { it.isNotEmpty() }
    }
}
