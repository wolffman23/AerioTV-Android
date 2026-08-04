package com.aeriotv.android.core.network.adaptarr

/**
 * Debug-only canary trigger state. It accepts at most one pending request and
 * consumes it before the caller performs eligibility checks, so a transiently
 * ineligible state cannot queue a later, stale test re-prime.
 */
class OneShotDebugCanaryRequest {
    private var pending = false

    /** Returns false when a request is already pending. */
    fun request(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    /** Returns and clears the pending request. */
    fun consume(): Boolean {
        val accepted = pending
        pending = false
        return accepted
    }
}
