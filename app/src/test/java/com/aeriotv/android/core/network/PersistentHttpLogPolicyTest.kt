package com.aeriotv.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistentHttpLogPolicyTest {
    @Test
    fun `drops request and source URLs completely`() {
        assertNull(PersistentHttpLogPolicy.safeMessage("REQUEST: https://example.test/private?token=secret"))
        assertNull(PersistentHttpLogPolicy.safeMessage("FROM: https://example.test/private"))
    }

    @Test
    fun `normalizes allowlisted methods without retaining other text`() {
        assertEquals("HTTP method=GET", PersistentHttpLogPolicy.safeMessage("METHOD: GET"))
        assertEquals("HTTP method=POST", PersistentHttpLogPolicy.safeMessage("  METHOD: POST  "))
        assertNull(PersistentHttpLogPolicy.safeMessage("METHOD: CUSTOM https://example.test/private"))
    }

    @Test
    fun `normalizes response to numeric status only`() {
        assertEquals("HTTP status=200", PersistentHttpLogPolicy.safeMessage("RESPONSE: 200 OK"))
        assertEquals(
            "HTTP status=503",
            PersistentHttpLogPolicy.safeMessage("RESPONSE: 503 https://example.test/private"),
        )
        assertNull(PersistentHttpLogPolicy.safeMessage("RESPONSE: not-a-status"))
    }

    @Test
    fun `multiline messages retain only allowlisted method and status`() {
        val message = """
            REQUEST: https://example.test/private?token=secret
            METHOD: GET
            RESPONSE: 204 No Content
            FROM: https://example.test/private
            Authorization: Bearer secret-secret
        """.trimIndent()

        assertEquals(
            "HTTP method=GET\nHTTP status=204",
            PersistentHttpLogPolicy.safeMessage(message),
        )
    }

    @Test
    fun `accepts every allowlisted method and rejects method lookalikes`() {
        listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").forEach { method ->
            assertEquals(
                "HTTP method=$method",
                PersistentHttpLogPolicy.safeMessage("METHOD: $method"),
            )
        }
        listOf(
            "METHOD: get",
            "METHOD: GeT",
            "METHOD: CONNECT",
            "METHOD: GET extra",
            "prefix METHOD: GET",
            "METHOD: GET https://example.test/private",
        ).forEach { line -> assertNull(PersistentHttpLogPolicy.safeMessage(line)) }
    }

    @Test
    fun `accepts bounded status codes and rejects malformed status lookalikes`() {
        assertEquals("HTTP status=100", PersistentHttpLogPolicy.safeMessage("RESPONSE: 100 Continue"))
        assertEquals("HTTP status=599", PersistentHttpLogPolicy.safeMessage("RESPONSE: 599 private suffix"))
        listOf(
            "RESPONSE: 99",
            "RESPONSE: 600",
            "RESPONSE: 020",
            "RESPONSE: +200",
            "RESPONSE: 20.0",
            "RESPONSE: ２００",
            "prefix RESPONSE: 200",
        ).forEach { line -> assertNull(PersistentHttpLogPolicy.safeMessage(line)) }
    }

    @Test
    fun `handles CRLF blanks and duplicates while preserving safe source order`() {
        val message = "REQUEST: https://private.test\r\nMETHOD: GET\r\n\r\n" +
            "RESPONSE: 200 OK\r\nMETHOD: POST\r\nBODY: secret"

        assertEquals(
            "HTTP method=GET\nHTTP status=200\nHTTP method=POST",
            PersistentHttpLogPolicy.safeMessage(message),
        )
    }

    @Test
    fun `drops unknown and credential-bearing lines fail closed`() {
        assertNull(PersistentHttpLogPolicy.safeMessage("Authorization: Bearer ***"))
        assertNull(PersistentHttpLogPolicy.safeMessage("BODY: {\"token\":\"secret\"}"))
        assertNull(PersistentHttpLogPolicy.safeMessage("unexpected diagnostics"))
        assertNull(PersistentHttpLogPolicy.safeMessage("payload mentions METHOD: GET"))
        assertNull(PersistentHttpLogPolicy.safeMessage("payload mentions RESPONSE: 200"))
    }
}
