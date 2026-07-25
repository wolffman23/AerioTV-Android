package com.aeriotv.android.core.network.adaptarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveStreamUrlTest {

    private val proxy = "http://10.0.0.176:9191/proxy/ts/stream/abc-123"

    @Test
    fun `appends output_profile with question mark when no query present`() {
        assertEquals(
            "$proxy?output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(proxy, 30),
        )
    }

    @Test
    fun `appends output_profile with ampersand when a query already exists`() {
        val url = "$proxy?api_key=SECRET123"
        assertEquals(
            "$url&output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `replaces an existing output_profile exactly once and preserves other params`() {
        val url = "$proxy?api_key=SECRET123&output_profile=99&x=1"
        assertEquals(
            "$proxy?api_key=SECRET123&output_profile=30&x=1",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `replaces a trailing output_profile without leaving a dangling separator`() {
        val url = "$proxy?api_key=SECRET123&output_profile=99"
        assertEquals(
            "$proxy?api_key=SECRET123&output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `replaces a sole output_profile query`() {
        val url = "$proxy?output_profile=99"
        assertEquals(
            "$proxy?output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `preserves api key and unrelated params byte for byte`() {
        val url = "$proxy?api_key=a%2Bb%2Fc&foo=bar%20baz&n=2"
        assertEquals(
            "$url&output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `preserves ipv6 host https port and encoded path`() {
        val url = "https://[2001:db8::1]:9191/proxy/ts/stream/ch%20uid?api_key=K"
        assertEquals(
            "$url&output_profile=42",
            AdaptiveStreamUrl.withOutputProfile(url, 42),
        )
    }

    @Test
    fun `preserves a url fragment after the rewritten query`() {
        val url = "$proxy?api_key=K#frag"
        assertEquals(
            "$proxy?api_key=K&output_profile=30#frag",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `does not match a look-alike param whose name merely ends with output_profile`() {
        val url = "$proxy?not_output_profile=99"
        assertEquals(
            "$url&output_profile=30",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `refuses a non dispatcharr url`() {
        assertNull(AdaptiveStreamUrl.withOutputProfile("https://evil.example/watch?x=1", 30))
    }

    @Test
    fun `refuses a foreign url carrying the marker only in its query or fragment`() {
        assertNull(
            AdaptiveStreamUrl.withOutputProfile("https://evil.example/watch?x=/proxy/ts/stream/", 30),
        )
        assertNull(
            AdaptiveStreamUrl.withOutputProfile("https://evil.example/watch#/proxy/ts/stream/", 30),
        )
    }

    @Test
    fun `marker match is case sensitive to dispatcharr lowercase path`() {
        assertNull(
            AdaptiveStreamUrl.withOutputProfile(
                "http://10.0.0.176:9191/PROXY/TS/STREAM/abc-123",
                30,
            ),
        )
    }

    @Test
    fun `replaces exactly once collapsing duplicate output_profile params`() {
        val url = "$proxy?output_profile=1&x=2&output_profile=9"
        assertEquals(
            "$proxy?output_profile=30&x=2",
            AdaptiveStreamUrl.withOutputProfile(url, 30),
        )
    }

    @Test
    fun `refuses a non http or https scheme`() {
        assertNull(
            AdaptiveStreamUrl.withOutputProfile("ftp://10.0.0.176/proxy/ts/stream/x", 30),
        )
    }

    @Test
    fun `refuses a blank url`() {
        assertNull(AdaptiveStreamUrl.withOutputProfile("   ", 30))
    }

    @Test
    fun `refuses a non positive output profile id`() {
        assertNull(AdaptiveStreamUrl.withOutputProfile(proxy, 0))
        assertNull(AdaptiveStreamUrl.withOutputProfile(proxy, -1))
    }

    @Test
    fun `null decision leaves the original url unchanged`() {
        assertEquals(proxy, AdaptiveStreamUrl.withOptionalOutputProfile(proxy, null))
    }

    @Test
    fun `optional rewrite falls back to the original url when rewrite refuses`() {
        val foreign = "https://evil.example/watch"
        assertEquals(foreign, AdaptiveStreamUrl.withOptionalOutputProfile(foreign, 30))
    }
}
