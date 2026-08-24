package com.aeriotv.android.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialCipherStrictTest {

    @Test
    fun `strict encryption preserves an empty credential without invoking AndroidKeyStore`() {
        assertEquals("", CredentialCipher().encryptStrict(""))
    }
}
