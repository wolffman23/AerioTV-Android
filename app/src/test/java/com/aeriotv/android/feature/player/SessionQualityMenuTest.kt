package com.aeriotv.android.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionQualityMenuTest {
    @Test
    fun `offers only fixed source and trusted live manual tiers with active option marked`() {
        val options = sessionQualityMenuOptions(activeProfileId = 8, includeDebugCanary = false)

        assertEquals(listOf(null, 7, 8, 9), options.map { it.profileId })
        assertEquals(listOf("Source", "1080p", "720p", "480p"), options.map { it.label })
        assertTrue(options.single { it.profileId == 8 }.isActive)
        assertFalse(options.single { it.profileId == 7 }.isActive)
        assertFalse(options.single { it.profileId == null }.isActive)
    }
}
