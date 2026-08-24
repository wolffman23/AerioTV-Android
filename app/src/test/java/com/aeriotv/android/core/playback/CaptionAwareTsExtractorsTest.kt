package com.aeriotv.android.core.playback

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionAwareTsExtractorsTest {
    @Test
    fun `descriptorless MPEG TS fallback advertises CEA 608 CC1`() {
        val formats = captionFallbackFormats()
        assertEquals(1, formats.size)
        assertEquals(MimeTypes.APPLICATION_CEA608, formats.single().sampleMimeType)
        assertEquals(1, formats.single().accessibilityChannel)
    }
}
