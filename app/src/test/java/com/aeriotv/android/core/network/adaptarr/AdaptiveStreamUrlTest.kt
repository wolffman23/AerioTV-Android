package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveStreamUrlTest {

    private val proxy = "https://dispatcharr.example.test:9191/proxy/ts/stream/abc-123"

    @Test
    fun `appends output profile when a trusted proxy url has no query`() {
        assertEquals(
            "$proxy?output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(proxy, 30),
        )
    }

    @Test
    fun `preserves unrelated encoded parameters and fragments byte for byte`() {
        val url = "$proxy?api_key=a%2Bb%2Fc&title=hello%20world#now"
        assertEquals(
            "$proxy?api_key=a%2Bb%2Fc&title=hello%20world&output_profile=30#now",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `replaces existing profile and collapses duplicate profile parameters`() {
        val url = "$proxy?output_profile=1&x=2&output_profile=9"
        assertEquals(
            "$proxy?output_profile=30&x=2",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `supports an IPv6 authority without parsing or recoding the url`() {
        val url = "https://[2001:db8::1]:9191/proxy/ts/stream/ch%20uid?x=1"
        assertEquals(
            "$url&output_profile=42",
            AdaptiveStreamUrl.withOutputProfile(url, 42),
        )
    }

    @Test
    fun `refuses unsupported schemes foreign paths and marker text outside the path`() {
        assertNull(AdaptiveStreamUrl.withOutputProfile("ftp://dispatcharr.example.test/proxy/ts/stream/x", 30))
        assertNull(AdaptiveStreamUrl.withOutputProfile("https://foreign.example.test/watch", 30))
        assertNull(AdaptiveStreamUrl.withOutputProfile("https://foreign.example.test/watch?x=/proxy/ts/stream/", 30))
        assertNull(AdaptiveStreamUrl.withOutputProfile("https://foreign.example.test/watch#/proxy/ts/stream/", 30))
    }

    @Test
    fun `refuses malformed session inputs without normalizing whitespace`() {
        assertNull(AdaptiveStreamUrl.withOutputProfile(" $proxy", 30))
        assertNull(AdaptiveStreamUrl.withOutputProfile("$proxy ", 30))
        assertNull(AdaptiveStreamUrl.withOutputProfile("   ", 30))
        assertNull(AdaptiveStreamUrl.withOutputProfile(proxy, 0))
        assertNull(AdaptiveStreamUrl.withOutputProfile(proxy, -1))
    }

    @Test
    fun `optional rewrite preserves source when no profile or unsupported url is supplied`() {
        assertEquals(proxy, AdaptiveStreamUrl.withOptionalOutputProfile(proxy, null))
        val foreign = "https://foreign.example.test/watch"
        assertEquals(foreign, AdaptiveStreamUrl.withOptionalOutputProfile(foreign, 30))
    }
}
