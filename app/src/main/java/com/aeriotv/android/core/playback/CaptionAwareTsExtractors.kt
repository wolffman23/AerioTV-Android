package com.aeriotv.android.core.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor

/** CEA-608 fallback for descriptorless raw MPEG-TS caption streams. */
@UnstableApi
internal fun captionFallbackFormats(): List<Format> = listOf(
    Format.Builder()
        .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
        .setAccessibilityChannel(1)
        .build(),
)

/** Shared raw-TS extractor setup; PMT descriptors remain authoritative. */
@UnstableApi
internal fun captionAwareTsExtractorsFactory(): DefaultExtractorsFactory =
    DefaultExtractorsFactory()
        .setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
        .setTsSubtitleFormats(captionFallbackFormats())
