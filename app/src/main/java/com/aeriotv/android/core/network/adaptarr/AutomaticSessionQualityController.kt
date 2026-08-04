package com.aeriotv.android.core.network.adaptarr

/**
 * Opaque, caller-derived identity for the playback facts that make an automatic decision valid.
 * [baseSourceFingerprint] must be a stable identifier or digest; it is deliberately not a URL.
 */
data class AutomaticSessionQualityFingerprint(
    val channelId: String,
    val baseSourceFingerprint: String,
    val mode: AutomaticSessionQualityMode,
    val eligibility: AutomaticSessionEligibility,
    val transport: AutomaticSessionTransport,
)

/** The actor whose successful re-prime established the current session output. */
enum class AutomaticSessionQualityOwnership {
    None,
    Manual,
    Automatic,
}

data class AutomaticSessionQualitySnapshot(
    val fingerprint: AutomaticSessionQualityFingerprint?,
    val epoch: Long,
    val ownership: AutomaticSessionQualityOwnership,
    val automaticBlocked: Boolean,
)

/**
 * Pure session ownership and epoch gate for quality re-primes.
 *
 * Callers prepare an authorization before starting a re-prime and commit it only when that
 * re-prime succeeds. A changed fingerprint or a manual action advances [epoch], so late
 * automatic probe/configuration work cannot commit into the newer session.
 */
class AutomaticSessionQualityController {
    private var fingerprint: AutomaticSessionQualityFingerprint? = null
    private var epoch = 0L
    private var ownership = AutomaticSessionQualityOwnership.None
    private var automaticBlocked = false

    val snapshot: AutomaticSessionQualitySnapshot
        get() = AutomaticSessionQualitySnapshot(
            fingerprint = fingerprint,
            epoch = epoch,
            ownership = ownership,
            automaticBlocked = automaticBlocked,
        )

    /**
     * Establishes the active session. A changed channel, base source, mode, eligibility, or
     * transport begins a new epoch and clears ownership/latching from the old session.
     */
    fun beginSession(newFingerprint: AutomaticSessionQualityFingerprint) {
        if (fingerprint == newFingerprint) return

        fingerprint = newFingerprint
        advanceEpoch()
        ownership = AutomaticSessionQualityOwnership.None
        automaticBlocked = false
    }

    /**
     * Carries an automatic output selection across a transport-only transition.
     *
     * The epoch always advances, rejecting work from the old network. Ownership is
     * preserved only for an unblocked automatic session whose non-transport facts
     * are identical; manual and unrelated changes use [beginSession] and reset.
     */
    fun beginAutomaticTransportTransition(newFingerprint: AutomaticSessionQualityFingerprint): Boolean {
        val current = fingerprint
        val isTransportOnly = current != null &&
            current.copy(transport = newFingerprint.transport) == newFingerprint
        if (!isTransportOnly || ownership != AutomaticSessionQualityOwnership.Automatic || automaticBlocked) {
            beginSession(newFingerprint)
            return false
        }
        if (current == newFingerprint) return true

        fingerprint = newFingerprint
        advanceEpoch()
        return true
    }

    /**
     * Authorizes an automatic decision for the current epoch. Source restoration is permitted
     * only when automatic work currently owns the output; manual ownership is never overridden.
     */
    fun prepareAutomatic(
        fingerprint: AutomaticSessionQualityFingerprint,
        decision: AutomaticSessionQualityDecision,
    ): Authorization? {
        if (this.fingerprint != fingerprint || automaticBlocked) return null
        if (ownership == AutomaticSessionQualityOwnership.Manual) return null

        val action = when (decision) {
            AutomaticSessionQualityDecision.NoChange -> return null
            AutomaticSessionQualityDecision.Source -> {
                if (ownership != AutomaticSessionQualityOwnership.Automatic) return null
                Action.AutomaticSource
            }
            is AutomaticSessionQualityDecision.OutputProfile -> Action.AutomaticOutput
        }
        return authorization(action)
    }

    /** Invalidates automatic work before a manual profile selection is re-primed. */
    fun prepareManualSelect(fingerprint: AutomaticSessionQualityFingerprint): Authorization? {
        if (this.fingerprint != fingerprint) return null
        advanceEpoch()
        return authorization(Action.ManualSelect)
    }

    /**
     * Invalidates automatic work and latches it off for this fingerprint before manual source
     * restoration. The latch intentionally remains even if the caller's re-prime fails.
     */
    fun prepareManualRestore(fingerprint: AutomaticSessionQualityFingerprint): Authorization? {
        if (this.fingerprint != fingerprint) return null
        advanceEpoch()
        automaticBlocked = true
        return authorization(Action.ManualRestore)
    }

    /**
     * Returns whether an authorization is still valid immediately before a player-side re-prime.
     * Call this from the serialized playback path; checking only [commit] is too late because
     * `playUrl` has already changed the singleton player's session by then.
     */
    fun isCurrentForReprime(authorization: Authorization): Boolean =
        authorization.controller === this &&
            authorization.epoch == epoch &&
            authorization.fingerprint == fingerprint

    /**
     * Applies ownership only for a successful, still-current caller re-prime.
     * Returns false for failed, stale, or foreign authorizations without moving ownership.
     */
    fun commit(authorization: Authorization, rePrimeSucceeded: Boolean): Boolean {
        if (!rePrimeSucceeded || !isCurrentForReprime(authorization)) {
            return false
        }

        ownership = when (authorization.action) {
            Action.AutomaticOutput -> AutomaticSessionQualityOwnership.Automatic
            Action.AutomaticSource -> AutomaticSessionQualityOwnership.None
            Action.ManualSelect,
            Action.ManualRestore -> AutomaticSessionQualityOwnership.Manual
        }
        return true
    }

    class Authorization internal constructor(
        internal val controller: AutomaticSessionQualityController,
        internal val fingerprint: AutomaticSessionQualityFingerprint,
        internal val epoch: Long,
        internal val action: Action,
    )

    internal enum class Action {
        AutomaticOutput,
        AutomaticSource,
        ManualSelect,
        ManualRestore,
    }

    private fun authorization(action: Action): Authorization = Authorization(
        controller = this,
        fingerprint = requireNotNull(fingerprint),
        epoch = epoch,
        action = action,
    )

    private fun advanceEpoch() {
        check(epoch != Long.MAX_VALUE) { "Automatic session epoch exhausted" }
        epoch += 1
    }
}
