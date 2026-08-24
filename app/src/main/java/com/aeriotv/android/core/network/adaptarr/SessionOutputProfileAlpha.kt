package com.aeriotv.android.core.network.adaptarr

/** Fixed, manually selectable session-only output profiles. */
internal enum class ManualSessionQuality(val outputProfileId: Int) {
    P1080(7),
    P720(8),
    P480(9),
}

/**
 * Holds transient output-profile state for one playback session.
 *
 * Changes follow a two-phase protocol: prepare an immutable candidate, let the
 * caller re-prime playback, then commit only if that re-prime succeeded. The
 * caller establishes channel provenance; URL grammar alone is not trust.
 */
internal class SessionOutputProfileAlpha(
    val originalUrl: String,
    private val isTrustedDispatcharrChannel: Boolean,
) {
    data class State(
        val url: String,
        val outputProfileId: Int?,
    ) {
        val isActive: Boolean get() = outputProfileId != null
    }

    class PreparedChange internal constructor(
        internal val session: SessionOutputProfileAlpha,
        internal val expectedState: State,
        val url: String,
        private val outputProfileId: Int?,
    ) {
        internal fun resultingState(): State = State(url, outputProfileId)
    }

    var state: State = State(url = originalUrl, outputProfileId = null)
        private set

    fun prepareSelect(outputProfileId: Int): PreparedChange? {
        if (!isTrustedDispatcharrChannel) return null
        val rewritten = AdaptiveStreamUrl.withOutputProfile(originalUrl, outputProfileId) ?: return null
        return PreparedChange(this, state, rewritten, outputProfileId)
    }

    fun prepareRestore(): PreparedChange = PreparedChange(this, state, originalUrl, null)

    /** Commits only a successful, current candidate created by this session. */
    fun commit(change: PreparedChange, rePrimeSucceeded: Boolean): State? {
        if (!rePrimeSucceeded || change.session !== this || change.expectedState != state) return null
        return change.resultingState().also { state = it }
    }
}
