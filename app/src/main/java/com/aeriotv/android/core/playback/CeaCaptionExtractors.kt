package com.aeriotv.android.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor

/**
 * Caption formats to use when an MPEG-TS PMT omits its ATSC
 * caption_service_descriptor even though H.264/H.265 SEI carries CEA data.
 *
 * CC1 is the conservative fallback for US broadcast/IPTV streams. Valid PMT
 * descriptors remain authoritative because FLAG_OVERRIDE_CAPTION_DESCRIPTORS
 * is deliberately not enabled.
 */
@OptIn(UnstableApi::class)
internal fun fallbackCeaCaptionFormats(): List<Format> = listOf(
    Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
        .setAccessibilityChannel(1)
        .build(),
)

/** Shared raw MPEG-TS extractor configuration for live, timeshift, Auto, and multiview. */
@OptIn(UnstableApi::class)
internal fun captionAwareTsExtractorsFactory(): DefaultExtractorsFactory =
    DefaultExtractorsFactory()
        .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        .setTsSubtitleFormats(fallbackCeaCaptionFormats())
