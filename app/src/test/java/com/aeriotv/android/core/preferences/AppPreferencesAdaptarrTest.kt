package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.aeriotv.android.core.security.CredentialCipher
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPreferencesAdaptarrTest {
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
    fun reset() {
        runBlocking {
            store.edit { it.clear() }
            clearMocks(cipher)
            every { cipher.encryptStrict(any()) } answers { "encrypted:${firstArg<String>()}" }
            every { cipher.decrypt(any()) } answers { firstArg<String?>()?.removePrefix("encrypted:") }
        }
    }

    @Test
    fun `Adaptarr preferences default fail closed and remain device local`() = runBlocking {
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

        preferences.setAdaptarrEnabled(true)
        preferences.setAdaptarrBaseUrl("https://adaptarr.example.test/proxy/")
        preferences.setAdaptarrToken("t".repeat(32))
        preferences.setAdaptiveQualityMode(AdaptiveQualityMode.Auto)
        preferences.setAdaptarrLastMeasuredThroughputBps(1234L)
        preferences.setAdaptarrLastDecision("local-only")

        val snapshot = preferences.snapshotSyncablePreferences().toString()
        assertFalse(snapshot.contains("adaptarr", ignoreCase = true))
        assertFalse(snapshot.contains("local-only"))
    }

    @Test
    fun `connection save validates and encrypts before one atomic write`() = runBlocking {
        val originalToken = "o".repeat(32)
        assertEquals(
            AdaptarrConnectionSaveResult.Saved,
            preferences.saveAdaptarrConnection("https://old.example", originalToken),
        )

        assertEquals(
            AdaptarrConnectionSaveResult.InvalidBaseUrl,
            preferences.saveAdaptarrConnection("ftp://unsafe.example", "n".repeat(32)),
        )
        assertEquals("https://old.example", preferences.adaptarrBaseUrl.first())
        assertEquals(originalToken, preferences.adaptarrToken.first())

        every { cipher.encryptStrict("r".repeat(32)) } returns null
        assertEquals(
            AdaptarrConnectionSaveResult.EncryptionFailed,
            preferences.saveAdaptarrConnection("https://new.example", "r".repeat(32)),
        )
        assertEquals("https://old.example", preferences.adaptarrBaseUrl.first())
        assertEquals(originalToken, preferences.adaptarrToken.first())
        verify(exactly = 1) { cipher.encryptStrict("r".repeat(32)) }
    }

    @Test
    fun `corrupt persisted endpoint and bearer fail closed on read`() = runBlocking {
        every { cipher.decrypt(any()) } answers { firstArg<String?>() }
        store.edit {
            it[stringPreferencesKey("adaptarr_base_url")] = "ftp://user:pass@unsafe.example?q=x"
            it[stringPreferencesKey("adaptarr_token")] = "short plaintext token"
        }

        assertEquals("", preferences.adaptarrBaseUrl.first())
        assertEquals("", preferences.adaptarrToken.first())
    }

    @Test
    fun `inbound sync keys cannot overwrite local Adaptarr state`() = runBlocking {
        val token = "t".repeat(32)
        preferences.setAdaptarrEnabled(true)
        preferences.setAdaptarrBaseUrl("https://adaptarr.example.test/private")
        preferences.setAdaptarrToken(token)
        preferences.setAdaptiveQualityMode(AdaptiveQualityMode.Auto)

        preferences.applySyncedPreferences(
            mapOf(
                "adaptarrEnabled" to "false",
                "adaptarrBaseUrl" to "https://attacker.invalid",
                "adaptarrToken" to "attacker-token-${"x".repeat(32)}",
                "adaptiveQualityMode" to "off",
            ),
        )

        assertTrue(preferences.adaptarrEnabled.first())
        assertEquals("https://adaptarr.example.test/private", preferences.adaptarrBaseUrl.first())
        assertEquals(token, preferences.adaptarrToken.first())
        assertEquals(AdaptiveQualityMode.Auto, preferences.adaptiveQualityMode.first())
    }
}
