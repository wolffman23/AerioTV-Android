package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOutputProfileAlphaTest {

    private val originalUrl = "https://dispatcharr.example.test/proxy/ts/stream/abc?token=opaque#player"

    @Test
    fun `preparing a valid selection does not change session state`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)

        val prepared = requireNotNull(session.prepareSelect(8))

        assertEquals(
            "https://dispatcharr.example.test/proxy/ts/stream/abc?token=opaque&output_profile=8#player",
            prepared.url,
        )
        assertFalse(session.state.isActive)
        assertEquals(originalUrl, session.state.url)
    }

    @Test
    fun `successful selection commit activates only the prepared profile`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        val prepared = requireNotNull(session.prepareSelect(8))

        val committed = requireNotNull(session.commit(prepared, rePrimeSucceeded = true))

        assertTrue(committed.isActive)
        assertEquals(8, committed.outputProfileId)
        assertEquals(committed, session.state)
    }

    @Test
    fun `failed selection reprime preserves prior state`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        val first = requireNotNull(session.prepareSelect(8))
        val active = requireNotNull(session.commit(first, rePrimeSucceeded = true))
        val second = requireNotNull(session.prepareSelect(9))

        assertNull(session.commit(second, rePrimeSucceeded = false))
        assertEquals(active, session.state)
        assertEquals(8, session.state.outputProfileId)
    }

    @Test
    fun `foreign prepared change cannot commit into another session`() {
        val first = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        val second = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        val prepared = requireNotNull(first.prepareSelect(8))

        assertNull(second.commit(prepared, rePrimeSucceeded = true))
        assertFalse(second.state.isActive)
    }

    @Test
    fun `restore commits the exact original url only after success`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        requireNotNull(session.commit(requireNotNull(session.prepareSelect(8)), rePrimeSucceeded = true))
        val restore = session.prepareRestore()

        assertNull(session.commit(restore, rePrimeSucceeded = false))
        assertTrue(session.state.isActive)
        val restored = requireNotNull(session.commit(restore, rePrimeSucceeded = true))
        assertFalse(restored.isActive)
        assertNull(restored.outputProfileId)
        assertEquals(originalUrl, restored.url)
    }

    @Test
    fun `invalid or untrusted requests cannot prepare a state change`() {
        val trusted = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        assertNull(trusted.prepareSelect(0))
        assertFalse(trusted.state.isActive)

        val untrusted = SessionOutputProfileAlpha(
            "https://forged.example/proxy/ts/stream/abc",
            isTrustedDispatcharrChannel = false,
        )
        assertNull(untrusted.prepareSelect(8))
        assertFalse(untrusted.state.isActive)
    }

    @Test
    fun `each typed manual quality maps to its fixed output profile id`() {
        assertEquals(7, ManualSessionQuality.P1080.outputProfileId)
        assertEquals(8, ManualSessionQuality.P720.outputProfileId)
        assertEquals(9, ManualSessionQuality.P480.outputProfileId)
    }
}
