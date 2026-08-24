package com.aeriotv.android.core.network.adaptarr

/** Caller-derived identity only; baseSourceFingerprint must never contain a raw URL. */
data class AutomaticSessionQualityFingerprint(
    val channelId: String,
    val baseSourceFingerprint: String,
    val mode: AutomaticSessionQualityMode,
    val eligibility: AutomaticSessionEligibility,
    val transport: AutomaticSessionTransport,
)

enum class AutomaticSessionQualityOwnership { None, Manual, Automatic }

data class AutomaticSessionQualitySnapshot(
    val fingerprint: AutomaticSessionQualityFingerprint?,
    val epoch: Long,
    val ownership: AutomaticSessionQualityOwnership,
    val automaticBlocked: Boolean,
)

/**
 * Pure prepare/commit arbiter for session-only re-primes. Preparation happens before
 * player work; commit is permitted only after successful player work and only while
 * the original authorization is still current.
 */
class AutomaticSessionQualityController {
    private var fingerprint: AutomaticSessionQualityFingerprint? = null
    private var epoch = 0L
    private var ownership = AutomaticSessionQualityOwnership.None
    private var automaticBlocked = false

    val snapshot: AutomaticSessionQualitySnapshot
        get() = AutomaticSessionQualitySnapshot(fingerprint, epoch, ownership, automaticBlocked)

    fun beginSession(next: AutomaticSessionQualityFingerprint) {
        if (fingerprint == next) return
        fingerprint = next
        advanceEpoch()
        ownership = AutomaticSessionQualityOwnership.None
        automaticBlocked = false
    }

    fun beginAutomaticTransportTransition(next: AutomaticSessionQualityFingerprint): Boolean {
        val current = fingerprint
        val transportOnly = current != null && current.copy(transport = next.transport) == next
        if (!transportOnly || ownership != AutomaticSessionQualityOwnership.Automatic || automaticBlocked) {
            beginSession(next)
            return false
        }
        if (current == next) return true
        fingerprint = next
        advanceEpoch()
        return true
    }

    fun prepareAutomatic(
        fingerprint: AutomaticSessionQualityFingerprint,
        decision: AutomaticSessionQualityDecision,
    ): Authorization? {
        if (this.fingerprint != fingerprint || automaticBlocked || ownership == AutomaticSessionQualityOwnership.Manual) {
            return null
        }
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

    fun prepareManualSelect(fingerprint: AutomaticSessionQualityFingerprint): Authorization? {
        if (this.fingerprint != fingerprint) return null
        advanceEpoch()
        return authorization(Action.ManualSelect)
    }

    fun prepareManualRestore(fingerprint: AutomaticSessionQualityFingerprint): Authorization? {
        if (this.fingerprint != fingerprint) return null
        advanceEpoch()
        automaticBlocked = true
        return authorization(Action.ManualRestore)
    }

    fun isCurrentForReprime(authorization: Authorization): Boolean =
        authorization.controller === this &&
            authorization.epoch == epoch &&
            authorization.fingerprint == fingerprint

    fun commit(authorization: Authorization, rePrimeSucceeded: Boolean): Boolean {
        if (!rePrimeSucceeded || !isCurrentForReprime(authorization)) return false
        ownership = when (authorization.action) {
            Action.AutomaticOutput -> AutomaticSessionQualityOwnership.Automatic
            Action.AutomaticSource -> AutomaticSessionQualityOwnership.None
            Action.ManualSelect, Action.ManualRestore -> AutomaticSessionQualityOwnership.Manual
        }
        return true
    }

    class Authorization internal constructor(
        internal val controller: AutomaticSessionQualityController,
        internal val fingerprint: AutomaticSessionQualityFingerprint,
        internal val epoch: Long,
        internal val action: Action,
    )

    internal enum class Action { AutomaticOutput, AutomaticSource, ManualSelect, ManualRestore }

    private fun authorization(action: Action) = Authorization(
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
