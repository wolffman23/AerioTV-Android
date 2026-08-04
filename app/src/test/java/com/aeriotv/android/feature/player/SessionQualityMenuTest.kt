package com.aeriotv.android.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionQualityMenuTest {
    @Test
    fun `quality submenu presents source and known profiles with the active session marked`() {
        assertEquals(
            listOf(
                SessionQualityMenuOption("Source", null, false),
                SessionQualityMenuOption("1080p", 7, false),
                SessionQualityMenuOption("720p", 8, true),
                SessionQualityMenuOption("480p", 9, false),
            ),
            sessionQualityMenuOptions(activeProfileId = 8, includeDebugCanary = false),
        )
    }

    @Test
    fun `debug canary is absent unless explicitly enabled for debug`() {
        assertEquals(
            emptyList<SessionQualityMenuOption>(),
            sessionQualityMenuOptions(activeProfileId = null, includeDebugCanary = false)
                .filter { it.isDebug },
        )
        assertEquals(
            listOf("Debug: run 480p Auto canary (3 Mbps)"),
            sessionQualityMenuOptions(activeProfileId = null, includeDebugCanary = true)
                .filter { it.isDebug }
                .map { it.label },
        )
    }
}
