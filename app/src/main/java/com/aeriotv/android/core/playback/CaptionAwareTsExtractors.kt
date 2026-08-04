package com.aeriotv.android.core.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * Media3 needs an explicit caption fallback when a raw MPEG-TS transport
 * preserves CEA-608 data in H.264/H.265 SEI/A53 packets but omits the PMT's
 * ATSC caption service descriptor. Keep the descriptor authoritative: this
 * fallback is consulted only when the descriptor is absent.
 */
@UnstableApi
internal fun captionFallbackFormats(): List<Format> = listOf(
    Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
        .setAccessibilityChannel(1)
        .build(),
)

/** Shared extractor configuration for every raw MPEG-TS playback path. */
@UnstableApi
internal fun captionAwareTsExtractorsFactory(): DefaultExtractorsFactory =
    DefaultExtractorsFactory()
        .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        .setTsSubtitleFormats(captionFallbackFormats())
