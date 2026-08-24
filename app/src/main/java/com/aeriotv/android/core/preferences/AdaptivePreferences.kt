package com.aeriotv.android.core.preferences

import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Device-local adaptive quality behavior. Unknown persisted values fail closed. */
enum class AdaptiveQualityMode(val wire: String) {
    Off("off"),
    Recommend("recommend"),
    Auto("auto"),
    ;

    companion object {
        fun fromWire(wire: String?): AdaptiveQualityMode = when (wire) {
            Off.wire -> Off
            Recommend.wire -> Recommend
            Auto.wire -> Auto
            else -> Off
        }
    }
}

enum class AdaptarrConnectionSaveResult { Saved, InvalidBaseUrl, InvalidToken, EncryptionFailed }

internal fun normalizeAdaptarrBaseUrl(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length > ADAPTARR_BASE_URL_MAX_LENGTH) return null
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null

    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrEmpty()) return null
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.rawAuthority?.endsWith(':') == true) return null
    if (uri.port == 0 || uri.port > 65_535) return null
    return trimmed.trimEnd('/')
}

internal fun normalizeAdaptarrToken(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.codePoints().anyMatch { Character.isWhitespace(it) || Character.isISOControl(it) }) return null
    if (trimmed.codePointCount(0, trimmed.length) !in ADAPTARR_TOKEN_MIN_CODE_POINTS..ADAPTARR_TOKEN_MAX_CODE_POINTS) return null
    val utf8Bytes = runCatching {
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(trimmed))
            .remaining()
    }.getOrNull() ?: return null
    return trimmed.takeIf { utf8Bytes <= ADAPTARR_TOKEN_MAX_UTF8_BYTES }
}

internal fun normalizeAdaptiveHeight(value: Int): Int =
    if (value <= ADAPTIVE_HEIGHT_LOW) ADAPTIVE_HEIGHT_LOW else ADAPTIVE_HEIGHT_HIGH

internal fun sanitizeAdaptarrDecision(value: String): String {
    val withoutControls = buildString(value.length) {
        value.codePoints().forEach { if (!Character.isISOControl(it)) appendCodePoint(it) }
    }.trim()
    val end = withoutControls.offsetByCodePoints(
        0,
        minOf(ADAPTARR_DECISION_MAX_CODE_POINTS, withoutControls.codePointCount(0, withoutControls.length)),
    )
    return withoutControls.substring(0, end)
}

private const val ADAPTARR_BASE_URL_MAX_LENGTH = 2048
private const val ADAPTARR_TOKEN_MIN_CODE_POINTS = 32
private const val ADAPTARR_TOKEN_MAX_CODE_POINTS = 512
private const val ADAPTARR_TOKEN_MAX_UTF8_BYTES = 2048
private const val ADAPTARR_DECISION_MAX_CODE_POINTS = 200
private const val ADAPTIVE_HEIGHT_LOW = 720
private const val ADAPTIVE_HEIGHT_HIGH = 1080
