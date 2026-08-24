package com.aeriotv.android.feature.player

internal data class SessionQualityMenuOption(
    val label: String,
    val profileId: Int?,
    val isActive: Boolean,
    val isDebug: Boolean = false,
)

/** The one session-local quality submenu shared by phone and TV player chrome. */
internal fun sessionQualityMenuOptions(
    activeProfileId: Int?,
    includeDebugCanary: Boolean,
): List<SessionQualityMenuOption> = buildList {
    add(SessionQualityMenuOption("Source", profileId = null, isActive = activeProfileId == null))
    add(SessionQualityMenuOption("1080p", profileId = 7, isActive = activeProfileId == 7))
    add(SessionQualityMenuOption("720p", profileId = 8, isActive = activeProfileId == 8))
    add(SessionQualityMenuOption("480p", profileId = 9, isActive = activeProfileId == 9))
    if (includeDebugCanary) {
        add(
            SessionQualityMenuOption(
                label = "Debug: run 480p Auto canary (3 Mbps)",
                profileId = null,
                isActive = false,
                isDebug = true,
            ),
        )
    }
}
