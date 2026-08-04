package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotDebugCanaryRequestTest {
    @Test
    fun `repeated requests never queue more than one canary`() {
        val request = OneShotDebugCanaryRequest()

        assertTrue(request.request())
        assertFalse(request.request())
        assertTrue(request.consume())
        assertFalse(request.consume())
    }

    @Test
    fun `consuming before eligibility discards an ineligible canary`() {
        val request = OneShotDebugCanaryRequest()

        assertTrue(request.request())
        // The effect consumes immediately, before checking eligibility.
        assertTrue(request.consume())
        assertFalse(request.consume())
    }
}
