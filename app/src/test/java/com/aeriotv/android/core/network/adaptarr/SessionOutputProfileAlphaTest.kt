package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOutputProfileAlphaTest {

    private val originalUrl =
        "http://10.0.0.176:9191/proxy/ts/stream/abc-123?api_key=a%2Bb%2Fc#player"

    @Test
    fun `selecting a positive profile activates the rewritten dispatcharr url`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)

        val state = requireNotNull(session.select(8))

        assertTrue(state.isActive)
        assertEquals(8, state.outputProfileId)
        assertEquals(
            "http://10.0.0.176:9191/proxy/ts/stream/abc-123?api_key=a%2Bb%2Fc&output_profile=8#player",
            state.url,
        )
        assertEquals(state, session.state)
        assertEquals(originalUrl, session.originalUrl)
    }

    @Test
    fun `each typed manual quality maps to its fixed output profile id`() {
        assertEquals(7, ManualSessionQuality.P1080.outputProfileId)
        assertEquals(8, ManualSessionQuality.P720.outputProfileId)
        assertEquals(9, ManualSessionQuality.P480.outputProfileId)
    }

    @Test
    fun `selecting each supported manual tier tracks its exact output profile id`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)

        ManualSessionQuality.entries.forEach { quality ->
            val state = requireNotNull(session.select(quality.outputProfileId))
            assertTrue(state.isActive)
            assertEquals(quality.outputProfileId, state.outputProfileId)
            assertTrue(state.url.contains("output_profile=${quality.outputProfileId}"))
        }
    }

    @Test
    fun `selecting a nonpositive profile is rejected without changing the session`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)

        val state = session.select(0)

        assertNull(state)
        assertEquals(originalUrl, session.originalUrl)
        assertFalse(session.state.isActive)
        assertNull(session.state.outputProfileId)
        assertEquals(originalUrl, session.state.url)
    }

    @Test
    fun `selecting a foreign url is rejected without changing the original url`() {
        val originalUrl = "https://evil.example/watch?x=1"
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)

        val state = session.select(8)

        assertNull(state)
        assertEquals(originalUrl, session.originalUrl)
        assertFalse(session.state.isActive)
        assertEquals(originalUrl, session.state.url)
    }

    @Test
    fun `selecting a malformed url is rejected without changing the original url`() {
        val originalUrl = "not a url /proxy/ts/stream/abc"
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)

        val state = session.select(8)

        assertNull(state)
        assertEquals(originalUrl, session.originalUrl)
        assertFalse(session.state.isActive)
        assertEquals(originalUrl, session.state.url)
    }

    @Test
    fun `selecting an authority forged url is rejected without trusted provenance`() {
        val forgedUrl = "https://evil.example/proxy/ts/stream/abc"
        val session = SessionOutputProfileAlpha(forgedUrl, isTrustedDispatcharrChannel = false)

        val state = session.select(8)

        assertNull(state)
        assertEquals(forgedUrl, session.state.url)
        assertNull(session.state.outputProfileId)
    }

    @Test
    fun `a rejected selection preserves an existing active selection`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        val activeState = requireNotNull(session.select(8))

        val rejectedState = session.select(0)

        assertNull(rejectedState)
        assertEquals(activeState, session.state)
        assertEquals(8, session.state.outputProfileId)
        assertEquals(
            "http://10.0.0.176:9191/proxy/ts/stream/abc-123?api_key=a%2Bb%2Fc&output_profile=8#player",
            session.state.url,
        )
    }

    @Test
    fun `restore returns the exact original url and clears the active selection`() {
        val session = SessionOutputProfileAlpha(originalUrl, isTrustedDispatcharrChannel = true)
        session.select(8)

        val state = session.restore()

        assertFalse(state.isActive)
        assertNull(state.outputProfileId)
        assertEquals(originalUrl, state.url)
        assertEquals(state, session.state)
        assertEquals(originalUrl, session.originalUrl)
    }
}
