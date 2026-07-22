package com.aeriotv.android.feature.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTrackTitleTest {

    @Test
    fun cea608ChannelOneUsesReadableCc1Name() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
            .setAccessibilityChannel(1)
            .build()

        assertEquals("Closed Captions CC1", subtitleTrackTitle(format))
    }

    @Test
    fun suppliedLabelRemainsPreferred() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
            .setAccessibilityChannel(1)
            .setLabel("English Captions")
            .build()

        assertEquals("English Captions", subtitleTrackTitle(format))
    }

    @Test
    fun unknownSubtitleFallsBackToFormatId() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setId("subtitle-7")
            .build()

        assertEquals("subtitle-7", subtitleTrackTitle(format))
    }
}
