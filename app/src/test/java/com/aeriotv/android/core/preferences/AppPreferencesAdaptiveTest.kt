package com.aeriotv.android.core.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.aeriotv.android.core.security.CredentialCipher
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class AppPreferencesAdaptiveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher: CredentialCipher = mockk()
    private val preferences = AppPreferences(context, cipher)

    @Suppress("UNCHECKED_CAST")
    private val store: DataStore<Preferences> by lazy {
        AppPreferences::class.java.getDeclaredMethod("getStore")
            .apply { isAccessible = true }
            .invoke(preferences) as DataStore<Preferences>
    }

    @Before
    fun reset() = runTest {
        store.edit { it.clear() }
        clearMocks(cipher)
        every { cipher.encryptStrict(any()) } answers { "encrypted:${firstArg<String>()}" }
        every { cipher.decrypt(any()) } answers {
            firstArg<String?>()?.removePrefix("encrypted:")
        }
        ShadowLog.clear()
    }

    @Test
    fun `adaptive preferences have fail-safe device defaults`() = runTest {
        assertFalse(preferences.adaptarrEnabled.first())
        assertEquals("", preferences.adaptarrBaseUrl.first())
        assertEquals("", preferences.adaptarrToken.first())
        assertEquals(AdaptiveQualityMode.Off, preferences.adaptiveQualityMode.first())
        assertEquals(1080, preferences.adaptiveMaxHeight.first())
        assertEquals(720, preferences.adaptiveCellularMaxHeight.first())
        assertEquals(720, preferences.adaptiveFallbackHeight.first())
        assertEquals(0L, preferences.adaptarrLastMeasuredThroughputBps.first())
        assertEquals("", preferences.adaptarrLastDecision.first())
        assertFalse(preferences.adaptarrTelemetryDryRunConsent.first())
    }

    @Test
    fun `telemetry dry-run consent defaults to off and round trips locally`() = runTest {
        assertFalse(preferences.adaptarrTelemetryDryRunConsent.first())

        preferences.setAdaptarrTelemetryDryRunConsent(true)
        assertTrue(preferences.adaptarrTelemetryDryRunConsent.first())

        preferences.setAdaptarrTelemetryDryRunConsent(false)
        assertFalse(preferences.adaptarrTelemetryDryRunConsent.first())
    }

    @Test
    fun `adaptive mode wire values round trip and unknown values fail closed`() {
        assertEquals("off", AdaptiveQualityMode.Off.wire)
        assertEquals("recommend", AdaptiveQualityMode.Recommend.wire)
        assertEquals("auto", AdaptiveQualityMode.Auto.wire)
        assertEquals(AdaptiveQualityMode.Off, AdaptiveQualityMode.fromWire(null))
        assertEquals(AdaptiveQualityMode.Off, AdaptiveQualityMode.fromWire("future"))
        assertEquals(AdaptiveQualityMode.Off, AdaptiveQualityMode.fromWire("auto"))
    }

    @Test
    fun `automatic mode remains unavailable and fails closed at persistence boundary`() = runTest {
        preferences.setAdaptiveQualityMode(AdaptiveQualityMode.Auto)
        assertEquals(AdaptiveQualityMode.Off, preferences.adaptiveQualityMode.first())
    }

    @Test
    fun `base URL normalization accepts local hosts IP addresses ports and proxy paths`() {
        val valid = mapOf(
            " https://example.com/ " to "https://example.com",
            "http://localhost:9191///" to "http://localhost:9191",
            "http://127.0.0.1:8080/adaptarr///" to "http://127.0.0.1:8080/adaptarr",
            "https://[::1]:443/reverse/proxy/" to "https://[::1]:443/reverse/proxy",
        )
        valid.forEach { (raw, expected) -> assertEquals(raw, expected, normalizeAdaptarrBaseUrl(raw)) }
        assertEquals("", normalizeAdaptarrBaseUrl("  \t "))
    }

    @Test
    fun `base URL normalization rejects unsafe or malformed values`() {
        val invalid = listOf(
            "example.com", "ftp://example.com", "https:///path", "https://",
            "https://user@example.com", "https://user:pass@example.com",
            "https://example.com?q=secret", "https://example.com/#fragment",
            "https://example.com:0", "https://example.com:65536",
            "https://exa mple.com", "https://example.com/white space",
            "https://example.com/line\nbreak", "https://example.com/\u0000",
            "https://[::1", "https://example.com:abc",
        )
        invalid.forEach { raw -> assertNull(raw, normalizeAdaptarrBaseUrl(raw)) }
    }

    @Test
    fun `base URL normalization enforces the length boundary`() {
        val prefix = "https://example.com/"
        val maximum = prefix + "a".repeat(2048 - prefix.length)
        assertEquals(2048, maximum.length)
        assertEquals(maximum, normalizeAdaptarrBaseUrl(maximum))
        assertNull(normalizeAdaptarrBaseUrl(prefix + "a".repeat(2049 - prefix.length)))
    }

    @Test
    fun `corrupt persisted adaptive strings fail closed or are sanitized on read`() = runTest {
        every { cipher.decrypt(any()) } answers { firstArg<String?>() }
        store.edit {
            it[stringPreferencesKey("adaptarr_base_url")] =
                "ftp://user:pass@attacker.invalid?q=secret"
            it[stringPreferencesKey("adaptarr_token")] = "short plaintext token"
            it[stringPreferencesKey("adaptarr_last_decision")] =
                "  chose\n1080p\u0000 " + "x".repeat(300)
        }

        assertEquals("", preferences.adaptarrBaseUrl.first())
        assertEquals("", preferences.adaptarrToken.first())
        val decision = preferences.adaptarrLastDecision.first()
        assertEquals(200, decision.codePointCount(0, decision.length))
        assertFalse(decision.any(Char::isISOControl))
        assertTrue(decision.startsWith("chose1080p"))
    }

    @Test
    fun `base URL setter rejects invalid input without replacing the previous value`() = runTest {
        assertTrue(preferences.setAdaptarrBaseUrl("https://example.com/proxy/"))
        assertFalse(preferences.setAdaptarrBaseUrl("https://user:secret@example.com"))
        assertEquals("https://example.com/proxy", preferences.adaptarrBaseUrl.first())
        assertTrue(preferences.setAdaptarrBaseUrl("  "))
        assertEquals("", preferences.adaptarrBaseUrl.first())
    }

    @Test
    fun `height setters and getters always use the deterministic 720 or 1080 policy`() = runTest {
        preferences.setAdaptiveMaxHeight(Int.MIN_VALUE)
        preferences.setAdaptiveCellularMaxHeight(721)
        preferences.setAdaptiveFallbackHeight(720)
        assertEquals(720, preferences.adaptiveMaxHeight.first())
        assertEquals(1080, preferences.adaptiveCellularMaxHeight.first())
        assertEquals(720, preferences.adaptiveFallbackHeight.first())

        store.edit {
            it[intPreferencesKey("adaptive_max_height")] = -1
            it[intPreferencesKey("adaptive_cellular_max_height")] = Int.MAX_VALUE
            it[intPreferencesKey("adaptive_fallback_height")] = 0
        }
        assertEquals(720, preferences.adaptiveMaxHeight.first())
        assertEquals(1080, preferences.adaptiveCellularMaxHeight.first())
        assertEquals(720, preferences.adaptiveFallbackHeight.first())
    }

    @Test
    fun `throughput and diagnostic setters clamp bound and remove controls`() = runTest {
        preferences.setAdaptarrLastMeasuredThroughputBps(-99)
        assertEquals(0L, preferences.adaptarrLastMeasuredThroughputBps.first())
        preferences.setAdaptarrLastDecision("  chose\n1080p\u0000 " + "x".repeat(300))
        val decision = preferences.adaptarrLastDecision.first()
        assertEquals(200, decision.codePointCount(0, decision.length))
        assertFalse(decision.any(Char::isISOControl))
        assertTrue(decision.startsWith("chose1080p"))
    }

    @Test
    fun `token validation accepts exact boundaries including 512 astral code points`() {
        assertEquals("a".repeat(32), normalizeAdaptarrToken("  ${"a".repeat(32)}  "))
        val astral512 = "😀".repeat(512)
        assertEquals(512, astral512.codePointCount(0, astral512.length))
        assertEquals(2048, astral512.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(astral512, normalizeAdaptarrToken(astral512))
        assertNull(normalizeAdaptarrToken("😀".repeat(513)))
        assertNull(normalizeAdaptarrToken("a".repeat(31)))
        assertNull(normalizeAdaptarrToken("a".repeat(513)))
        assertNull(normalizeAdaptarrToken("a".repeat(31) + "\n"))
        assertNull(normalizeAdaptarrToken("a".repeat(16) + " " + "a".repeat(16)))
        val malformedHighSurrogate = "\uD800" + "a".repeat(31)
        val malformedLowSurrogate = "\uDC00" + "a".repeat(31)
        assertEquals(32, malformedHighSurrogate.codePointCount(0, malformedHighSurrogate.length))
        assertNull(normalizeAdaptarrToken(malformedHighSurrogate))
        assertNull(normalizeAdaptarrToken(malformedLowSurrogate))
        assertEquals("", normalizeAdaptarrToken(" \t "))
    }

    @Test
    fun `invalid token leaves ciphertext unchanged while blank clears`() = runTest {
        val original = "o".repeat(32)
        assertTrue(preferences.setAdaptarrToken(original))
        assertFalse(preferences.setAdaptarrToken("short"))
        assertEquals(original, preferences.adaptarrToken.first())
        verify(exactly = 0) { cipher.encryptStrict("short") }
        assertTrue(preferences.setAdaptarrToken("  "))
        assertEquals("", preferences.adaptarrToken.first())
    }

    @Test
    fun `valid token is normalized encrypted at rest and decrypted for callers`() = runTest {
        val clear = "t".repeat(32)
        val ciphertext = "ciphertext-not-the-token"
        every { cipher.encryptStrict(clear) } returns ciphertext
        every { cipher.decrypt(ciphertext) } returns clear

        assertTrue(preferences.setAdaptarrToken("  $clear  "))
        assertEquals(clear, preferences.adaptarrToken.first())
        verify(exactly = 1) { cipher.encryptStrict(clear) }
        verify(atLeast = 1) { cipher.decrypt(ciphertext) }
    }

    @Test
    fun `token encryption failure preserves prior ciphertext and fails closed`() = runTest {
        val original = "o".repeat(32)
        val replacement = "r".repeat(32)
        assertTrue(preferences.setAdaptarrToken(original))
        every { cipher.encryptStrict(replacement) } returns null

        assertFalse(preferences.setAdaptarrToken(replacement))
        assertEquals(original, preferences.adaptarrToken.first())
        verify(exactly = 1) { cipher.encryptStrict(replacement) }
    }

    @Test
    fun `cipher prefix in a clear token is still strictly encrypted`() = runTest {
        val clear = "aerioenc:v1:" + "x".repeat(32)
        assertTrue(preferences.setAdaptarrToken(clear))
        assertEquals(clear, preferences.adaptarrToken.first())
        verify(exactly = 1) { cipher.encryptStrict(clear) }
    }

    @Test
    fun `connection save is atomic across validation encryption and persistence`() = runTest {
        val oldToken = "o".repeat(32)
        assertEquals(
            AdaptarrConnectionSaveResult.Saved,
            preferences.saveAdaptarrConnection("https://old.example", oldToken),
        )

        assertEquals(
            AdaptarrConnectionSaveResult.InvalidBaseUrl,
            preferences.saveAdaptarrConnection("ftp://new.example", "n".repeat(32)),
        )
        assertEquals("https://old.example", preferences.adaptarrBaseUrl.first())
        assertEquals(oldToken, preferences.adaptarrToken.first())

        assertEquals(
            AdaptarrConnectionSaveResult.InvalidToken,
            preferences.saveAdaptarrConnection("https://new.example", "short"),
        )
        assertEquals("https://old.example", preferences.adaptarrBaseUrl.first())
        assertEquals(oldToken, preferences.adaptarrToken.first())

        val replacement = "r".repeat(32)
        every { cipher.encryptStrict(replacement) } returns null
        assertEquals(
            AdaptarrConnectionSaveResult.EncryptionFailed,
            preferences.saveAdaptarrConnection("https://new.example", replacement),
        )
        assertEquals("https://old.example", preferences.adaptarrBaseUrl.first())
        assertEquals(oldToken, preferences.adaptarrToken.first())
    }

    @Test
    fun `all adaptive preferences are device local and incoming sync keys are ignored`() = runTest {
        val token = "secret-token-" + "x".repeat(32)
        preferences.setAdaptarrEnabled(true)
        preferences.setAdaptarrBaseUrl("https://adaptarr.local:9191/private-path")
        preferences.setAdaptarrToken(token)
        preferences.setAdaptiveQualityMode(AdaptiveQualityMode.Auto)
        preferences.setAdaptiveMaxHeight(720)
        preferences.setAdaptiveCellularMaxHeight(1080)
        preferences.setAdaptiveFallbackHeight(1080)
        preferences.setAdaptarrLastMeasuredThroughputBps(987654321)
        preferences.setAdaptarrLastDecision("adaptive-private-decision")

        val snapshot = preferences.snapshotSyncablePreferences()
        val serialized = snapshot.entries.joinToString("|") { "${it.key}=${it.value}" }
        listOf(
            "adaptarr", "adaptive", token, "adaptarr.local", "private-path",
            "987654321", "adaptive-private-decision",
        ).forEach { forbidden -> assertFalse(forbidden, serialized.contains(forbidden, ignoreCase = true)) }

        preferences.applySyncedPreferences(
            mapOf(
                "adaptarrEnabled" to "false",
                "adaptarrBaseUrl" to "https://attacker.invalid",
                "adaptarrToken" to "attacker-token-${"z".repeat(32)}",
                "adaptiveQualityMode" to "off",
                "adaptiveMaxHeight" to "1080",
                "adaptiveCellularMaxHeight" to "720",
                "adaptiveFallbackHeight" to "720",
                "adaptarrLastMeasuredThroughputBps" to "1",
                "adaptarrLastDecision" to "remote",
            ),
        )
        assertTrue(preferences.adaptarrEnabled.first())
        assertEquals("https://adaptarr.local:9191/private-path", preferences.adaptarrBaseUrl.first())
        assertEquals(token, preferences.adaptarrToken.first())
        assertEquals(AdaptiveQualityMode.Off, preferences.adaptiveQualityMode.first())
        assertEquals(720, preferences.adaptiveMaxHeight.first())
        assertEquals(1080, preferences.adaptiveCellularMaxHeight.first())
        assertEquals(1080, preferences.adaptiveFallbackHeight.first())
        assertEquals(987654321L, preferences.adaptarrLastMeasuredThroughputBps.first())
        assertEquals("adaptive-private-decision", preferences.adaptarrLastDecision.first())
    }

    @Test
    fun `preference callers never log tokens or full URLs`() = runTest {
        val token = "never-log-this-token-" + "x".repeat(32)
        val url = "https://adaptarr.local/private-reverse-proxy"
        preferences.setAdaptarrToken(token)
        preferences.setAdaptarrBaseUrl(url)

        val logText = ShadowLog.getLogs().joinToString("\n") { entry ->
            listOf(entry.tag, entry.msg, entry.throwable?.message.orEmpty()).joinToString(" ")
        }
        assertFalse(logText.contains(token))
        assertFalse(logText.contains(url))
        assertEquals(0, ShadowLog.getLogsForTag("AppPreferences").count { it.type >= Log.INFO })
    }
}
