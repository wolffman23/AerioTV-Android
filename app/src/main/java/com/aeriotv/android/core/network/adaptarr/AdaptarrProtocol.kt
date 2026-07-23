package com.aeriotv.android.core.network.adaptarr

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

internal val adaptarrJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    explicitNulls = true
    encodeDefaults = true
}

private const val PROTOCOL_VERSION = 1
private val NETWORK_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val CANONICAL_TIMESTAMP_PATTERN =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z$")

internal object StrictWireIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AdaptarrStrictInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected integer")
        if (primitive.isString) throw SerializationException("Expected integer")
        return primitive.content.toIntOrNull()
            ?: throw SerializationException("Expected integer")
    }
}

internal object StrictWireLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AdaptarrStrictLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected integer")
        if (primitive.isString) throw SerializationException("Expected integer")
        return primitive.content.toLongOrNull()
            ?: throw SerializationException("Expected integer")
    }
}

internal object StrictWireBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AdaptarrStrictBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        if (decoder !is JsonDecoder) return decoder.decodeBoolean()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Expected boolean")
        if (primitive.isString) throw SerializationException("Expected boolean")
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> throw SerializationException("Expected boolean")
        }
    }
}

internal typealias StrictWireInt = @Serializable(with = StrictWireIntSerializer::class) Int
internal typealias StrictWireLong = @Serializable(with = StrictWireLongSerializer::class) Long
internal typealias StrictWireBoolean = @Serializable(with = StrictWireBooleanSerializer::class) Boolean

private fun requireProtocolOne(value: Int) {
    require(value == PROTOCOL_VERSION) { "unsupported protocol" }
}

private fun requirePositive(value: Long) {
    require(value > 0L) { "invalid positive value" }
}

private fun requireCanonicalTimestamp(value: String) {
    require(CANONICAL_TIMESTAMP_PATTERN.matches(value)) { "invalid timestamp" }
    runCatching { Instant.parse(value) }.getOrElse { throw IllegalArgumentException("invalid timestamp") }
}

private fun requireBoundedText(value: String, minCodePoints: Int, maxCodePoints: Int) {
    val count = value.codePointCount(0, value.length)
    require(count in minCodePoints..maxCodePoints) { "invalid text" }
    var index = 0
    while (index < value.length) {
        val point = value.codePointAt(index)
        val category = Character.getType(point)
        require(
            category != Character.CONTROL.toInt() &&
                category != Character.FORMAT.toInt() &&
                category != Character.SURROGATE.toInt() &&
                category != Character.PRIVATE_USE.toInt() &&
                category != Character.UNASSIGNED.toInt(),
        ) { "invalid text" }
        index += Character.charCount(point)
    }
}

private fun requireRecommendedProfileName(value: String) {
    require(value.trim() == value) { "invalid profile name" }
    requireBoundedText(value, 1, 200)
}

private fun requireNetworkKey(value: String) {
    require(NETWORK_KEY_PATTERN.matches(value)) { "invalid network key" }
}

private fun requireProbeValues(bytesTransferred: Long, durationMs: Int, latencyMs: Int) {
    require(bytesTransferred in 65_536L..4_194_304L) { "invalid probe bytes" }
    require(durationMs in 1..120_000) { "invalid probe duration" }
    require(latencyMs in 0..60_000) { "invalid probe latency" }
}

@Serializable
internal class AdaptarrHealthResponse(
    val status: String,
    @SerialName("protocol_version") val protocolVersion: StrictWireInt,
) {
    init {
        require(status == "ok") { "invalid health status" }
        requireProtocolOne(protocolVersion)
    }
}

@Serializable
internal class AdaptarrProfile(
    val id: StrictWireInt,
    val name: String,
    val width: StrictWireInt,
    val height: StrictWireInt,
    val mode: AdaptarrProfileMode,
    @SerialName("estimated_bitrate_bps") val estimatedBitrateBps: StrictWireLong,
    @SerialName("minimum_throughput_bps") val minimumThroughputBps: StrictWireLong,
) {
    init {
        require(id > 0 && width > 0 && height > 0) { "invalid profile" }
        requireRecommendedProfileName(name)
        requirePositive(estimatedBitrateBps)
        requirePositive(minimumThroughputBps)
    }
}

@Serializable
internal enum class AdaptarrProfileMode {
    @SerialName("passthrough") Passthrough,
    @SerialName("transcode") Transcode,
}

@Serializable
internal class AdaptarrConfigResponse(
    @SerialName("schema_version") val schemaVersion: StrictWireInt,
    @SerialName("protocol_version") val protocolVersion: StrictWireInt,
    @SerialName("generation_id") val generationId: String,
    @SerialName("generated_at") val generatedAt: String,
    val profiles: Map<String, AdaptarrProfile>,
) {
    init {
        requireProtocolOne(schemaVersion)
        requireProtocolOne(protocolVersion)
        require(UUID_PATTERN.matches(generationId)) { "invalid generation id" }
        requireCanonicalTimestamp(generatedAt)
        require(profiles.isNotEmpty() && profiles.size <= 32) { "invalid profile set" }
        profiles.keys.forEach { requireBoundedText(it, 1, 64) }
    }
}

@Serializable
internal class AdaptarrProbeReport(
    @SerialName("bytes_transferred") val bytesTransferred: StrictWireLong,
    @SerialName("duration_ms") val durationMs: StrictWireInt,
    @SerialName("latency_ms") val latencyMs: StrictWireInt,
) {
    init {
        requireProbeValues(bytesTransferred, durationMs, latencyMs)
    }
}

@Serializable
internal class AdaptarrProbeObservation(
    val status: String,
    @SerialName("bytes_transferred") val bytesTransferred: StrictWireLong,
    @SerialName("duration_ms") val durationMs: StrictWireInt,
    @SerialName("latency_ms") val latencyMs: StrictWireInt,
    @SerialName("throughput_bps") val throughputBps: StrictWireLong,
    @SerialName("observed_at") val observedAt: String,
) {
    init {
        require(status == "observed") { "invalid observation status" }
        requireProbeValues(bytesTransferred, durationMs, latencyMs)
        requirePositive(throughputBps)
        requireCanonicalTimestamp(observedAt)
    }
}

@Serializable
internal class AdaptarrTelemetryReport(
    @SerialName("network_key") val networkKey: String,
    @SerialName("bytes_transferred") val bytesTransferred: StrictWireLong,
    @SerialName("duration_ms") val durationMs: StrictWireInt,
    @SerialName("latency_ms") val latencyMs: StrictWireInt,
) {
    init {
        requireNetworkKey(networkKey)
        requireProbeValues(bytesTransferred, durationMs, latencyMs)
    }
}

@Serializable
internal class AdaptarrTelemetryLookup(
    @SerialName("network_key") val networkKey: String,
) {
    init {
        requireNetworkKey(networkKey)
    }
}

@Serializable
internal enum class AdaptarrConfidence {
    @SerialName("none") None,
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
}

@Serializable
internal enum class AdaptarrTelemetryConfidence {
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
}

@Serializable
internal class AdaptarrTelemetryAggregate(
    val status: String,
    @SerialName("sample_count") val sampleCount: StrictWireInt,
    @SerialName("conservative_throughput_bps") val conservativeThroughputBps: StrictWireLong,
    @SerialName("median_latency_ms") val medianLatencyMs: StrictWireInt,
    val confidence: AdaptarrTelemetryConfidence,
    @SerialName("last_observed_at") val lastObservedAt: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    init {
        require(status == "aggregated") { "invalid aggregate status" }
        require(sampleCount in 1..20) { "invalid sample count" }
        requirePositive(conservativeThroughputBps)
        require(medianLatencyMs in 0..60_000) { "invalid latency" }
        require(confidence == expectedTelemetryConfidence(sampleCount)) { "invalid confidence" }
        requireCanonicalTimestamp(lastObservedAt)
        requireCanonicalTimestamp(expiresAt)
    }
}

@Serializable
internal class AdaptarrRecommendationRequest(
    @SerialName("network_key") val networkKey: String,
    @SerialName("max_height") val maxHeight: StrictWireInt,
) {
    init {
        requireNetworkKey(networkKey)
        require(maxHeight == 720 || maxHeight == 1080) { "invalid max height" }
    }
}

@Serializable
internal class AdaptarrRecommendedProfile(
    @SerialName("output_profile_id") val outputProfileId: StrictWireInt,
    val name: String,
    val height: StrictWireInt,
    @SerialName("minimum_throughput_bps") val minimumThroughputBps: StrictWireLong,
) {
    init {
        require(outputProfileId > 0 && height > 0) { "invalid recommended profile" }
        requireRecommendedProfileName(name)
        requirePositive(minimumThroughputBps)
    }
}

@Serializable
internal enum class AdaptarrRecommendationStatus {
    @SerialName("recommended") Recommended,
    @SerialName("constrained") Constrained,
    @SerialName("insufficient_data") InsufficientData,
    @SerialName("unavailable") Unavailable,
}

@Serializable
internal enum class AdaptarrRecommendationReason {
    @SerialName("threshold_met") ThresholdMet,
    @SerialName("below_lowest_threshold") BelowLowestThreshold,
    @SerialName("no_telemetry") NoTelemetry,
    @SerialName("insufficient_samples") InsufficientSamples,
    @SerialName("no_approved_profile") NoApprovedProfile,
}

@Serializable
internal class AdaptarrRecommendationResponse(
    val status: AdaptarrRecommendationStatus,
    @SerialName("dry_run") val dryRun: StrictWireBoolean,
    val profile: AdaptarrRecommendedProfile?,
    @SerialName("sample_count") val sampleCount: StrictWireInt,
    @SerialName("conservative_throughput_bps") val conservativeThroughputBps: StrictWireLong?,
    val confidence: AdaptarrConfidence,
    val reason: AdaptarrRecommendationReason,
) {
    init {
        require(dryRun) { "recommendation was not dry-run" }
        require(sampleCount in 0..20) { "invalid sample count" }
        conservativeThroughputBps?.let(::requirePositive)
        require(isConsistent()) { "inconsistent recommendation" }
    }

    private fun isConsistent(): Boolean {
        val expected = if (sampleCount == 0) AdaptarrConfidence.None else expectedConfidence(sampleCount)
        if (confidence != expected) return false
        return when (reason) {
            AdaptarrRecommendationReason.NoTelemetry ->
                status == AdaptarrRecommendationStatus.InsufficientData &&
                    profile == null && sampleCount == 0 && conservativeThroughputBps == null
            AdaptarrRecommendationReason.InsufficientSamples ->
                status == AdaptarrRecommendationStatus.InsufficientData &&
                    profile == null && sampleCount in 1..2 && conservativeThroughputBps != null
            AdaptarrRecommendationReason.ThresholdMet ->
                status == AdaptarrRecommendationStatus.Recommended &&
                    profile != null && sampleCount in 3..20 &&
                    conservativeThroughputBps != null &&
                    conservativeThroughputBps >= profile.minimumThroughputBps
            AdaptarrRecommendationReason.BelowLowestThreshold ->
                status == AdaptarrRecommendationStatus.Constrained &&
                    profile != null && sampleCount in 3..20 &&
                    conservativeThroughputBps != null &&
                    conservativeThroughputBps < profile.minimumThroughputBps
            AdaptarrRecommendationReason.NoApprovedProfile ->
                status == AdaptarrRecommendationStatus.Unavailable &&
                    profile == null && sampleCount in 3..20 && conservativeThroughputBps != null
        }
    }
}

private fun expectedConfidence(sampleCount: Int): AdaptarrConfidence = when (sampleCount) {
    0 -> AdaptarrConfidence.None
    in 1..2 -> AdaptarrConfidence.Low
    in 3..5 -> AdaptarrConfidence.Medium
    else -> AdaptarrConfidence.High
}

private fun expectedTelemetryConfidence(sampleCount: Int): AdaptarrTelemetryConfidence =
    when (sampleCount) {
        in 1..2 -> AdaptarrTelemetryConfidence.Low
        in 3..5 -> AdaptarrTelemetryConfidence.Medium
        else -> AdaptarrTelemetryConfidence.High
    }
