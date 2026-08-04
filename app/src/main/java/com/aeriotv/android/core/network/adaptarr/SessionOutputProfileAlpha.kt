package com.aeriotv.android.core.network.adaptarr

/** Fixed, manually selectable session-only output profiles. */
internal enum class ManualSessionQuality(val outputProfileId: Int) {
    P1080(7),
    P720(8),
    P480(9),
}

/**
 * Holds a transient output-profile choice for one playback session.
 *
 * [isTrustedDispatcharrChannel] is caller-established channel provenance; the URL
 * grammar transformer deliberately does not establish authority trust. The original
 * URL is retained exactly so a rejected selection or [restore] can always return
 * playback to the unmodified source.
 */
internal class SessionOutputProfileAlpha(
    val originalUrl: String,
    private val isTrustedDispatcharrChannel: Boolean,
) {
    data class State(
        val url: String,
        val outputProfileId: Int?,
    ) {
        val isActive: Boolean
            get() = outputProfileId != null
    }

    var state: State = State(url = originalUrl, outputProfileId = null)
        private set

    /**
     * Activates [outputProfileId] only for a caller-verified trusted Dispatcharr
     * channel whose original URL matches the supported stream grammar. Rejected
     * selections leave the current session state unchanged.
     */
    fun select(outputProfileId: Int): State? {
        if (!isTrustedDispatcharrChannel) return null
        val rewrittenUrl = AdaptiveStreamUrl.withOutputProfile(originalUrl, outputProfileId) ?: return null
        return State(url = rewrittenUrl, outputProfileId = outputProfileId).also { state = it }
    }

    /** Clears the active selection and returns the exact original URL. */
    fun restore(): State = State(url = originalUrl, outputProfileId = null).also { state = it }
}
