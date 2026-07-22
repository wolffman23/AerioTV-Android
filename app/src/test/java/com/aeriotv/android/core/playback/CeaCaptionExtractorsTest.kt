package com.aeriotv.android.core.playback

import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class CeaCaptionExtractorsTest {

    @Test
    fun fallbackFormatsExposeCea608Cc1() {
        val formats = fallbackCeaCaptionFormats()

        assertEquals(1, formats.size)
        assertEquals(MimeTypes.APPLICATION_CEA608, formats.single().sampleMimeType)
        assertEquals(1, formats.single().accessibilityChannel)
    }

    @Test
    fun factoryUsesSinglePmtModeWithCaptionFallback() {
        val factory = captionAwareTsExtractorsFactory()

        assertEquals(TsExtractor.MODE_SINGLE_PMT, configuredValue(factory, "tsMode"))
        assertEquals(fallbackCeaCaptionFormats(), configuredValue(factory, "tsSubtitleFormats"))
    }

    private fun configuredValue(factory: DefaultExtractorsFactory, fieldName: String): Any? {
        val field = DefaultExtractorsFactory::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(factory)
    }
}
