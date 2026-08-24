package com.aeriotv.android.core.network.adaptarr

/**
 * Debug-only trigger state. Callers consume before eligibility checks, preventing a
 * transiently ineligible tap from becoming a stale future re-prime request.
 */
class OneShotDebugCanaryRequest {
    private var pending = false

    /** Returns false when a request is already pending. */
    fun request(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    /** Returns the pending request and clears it. */
    fun consume(): Boolean = pending.also { pending = false }
}
