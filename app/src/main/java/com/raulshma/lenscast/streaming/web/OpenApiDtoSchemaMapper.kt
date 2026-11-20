package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.BackupTarget
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.model.CameraSettingsDto
import com.raulshma.lenscast.streaming.model.MaskingZoneDto
import com.raulshma.lenscast.streaming.model.MotionZoneDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types

/**
 * The DTO-backed OpenAPI property sets, generated from the DTO value trees
 * instead of hand-listed in [OpenApiSpec]. The approach is the fixture
 * test's, pointed at the spec: serialize the defaults instance through the
 * production [AppJson] adapters (the same wire bytes
 * `DtoContractFixtureTest` pins web/contract/settings.json with), parse the
 * value tree back, and emit one property entry per key — wire type,
 * serialized default, the persisted clamp span where the bounds are
 * derivable in Kotlin ([StreamDefaults], [OverlaySettings], [MotionZone] and
 * MaskingZone's own constants — the sources SettingsDataStore's IntBounds
 * declare through), the closed value set where the wire domain is a known
 * vocabulary, and the item schema for list fields.
 *
 * A DTO field added to the wire tree therefore reaches the spec without a
 * hand edit, and a list field added without a mapped item schema — or a
 * default that strays outside its generated bounds or enum — fails loudly
 * here rather than silently weakening the document. What stays hand-written
 * in [OpenApiSpec]: the `description` strings (merged over these entries),
 * the `required` cores, and every non-DTO schema.
 */
internal object OpenApiDtoSchemaMapper {

    /** The JSON-value parser the fixture test uses on the serialized wire bytes. */
    private val mapAdapter = AppJson.moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    // ── the generated property sets ──

    /**
     * The streaming settings section: the DTO defaults instance with
     * `rtspInputFormat` at the store's default (the enumPref `AUTO` — the
     * value a fresh install really serves; the DTO field itself defaults to
     * ""), the same construction the settings fixture pins.
     */
    fun streamingSettingsProperties(): Map<String, Map<String, Any?>> =
        properties(
            AppJson.moshi.adapter(StreamingSettingsDto::class.java),
            StreamingSettingsDto().copy(rtspInputFormat = "AUTO"),
        )

    /**
     * The camera settings section: the DTO defaults with the nullable fields
     * populated at the fixture's UI fallback values (the same copy the
     * settings fixture test carries), so the generated set covers the
     * nullable fields the fixture pins too.
     */
    fun cameraSettingsProperties(): Map<String, Map<String, Any?>> =
        properties(
            AppJson.moshi.adapter(CameraSettingsDto::class.java),
            CameraSettingsDto().copy(
                iso = 800,
                exposureTime = 10_000_000L,
                focusDistance = 0f,
                colorTemperature = 5500,
                sceneMode = "",
            ),
        )

    /** The privacy-masking zone items (StreamingSettingsDto.maskingZones). */
    fun maskingZoneProperties(): Map<String, Map<String, Any?>> =
        properties(AppJson.moshi.adapter(MaskingZoneDto::class.java), MaskingZoneDto())

    /** The motion-analysis zone items (StreamingSettingsDto.motionZones). */
    fun motionZoneProperties(): Map<String, Map<String, Any?>> =
        properties(AppJson.moshi.adapter(MotionZoneDto::class.java), MotionZoneDto())

    // ── the walker ──

    /** One property entry per wire key, in DTO field order. */
    private fun <T : Any> properties(adapter: JsonAdapter<T>, instance: T): LinkedHashMap<String, Map<String, Any?>> {
        val tree = requireNotNull(mapAdapter.fromJson(adapter.toJson(instance))) {
            "The defaults instance serialized to a null document"
        }
        val entries = LinkedHashMap<String, Map<String, Any?>>()
        for ((name, value) in tree) {
            entries[name] = propertyEntry(name, value)
        }
        return entries
    }

    /** The generated entry for one wire value: type, default, and the known domain extras. */
    private fun propertyEntry(name: String, value: Any?): Map<String, Any?> = when (value) {
        is Boolean -> mapOf<String, Any?>("type" to "boolean", "default" to value)
        is Int -> withBounds(name, value, mapOf<String, Any?>("type" to "integer", "default" to value))
        is Long -> withBounds(name, value, mapOf<String, Any?>("type" to "integer", "default" to value))
        is Double -> withBounds(name, value, mapOf<String, Any?>("type" to "number", "default" to value))
        is String -> withDomain(name, value, mapOf<String, Any?>("type" to "string", "default" to value))
        is List<*> -> mapOf<String, Any?>("type" to "array", "items" to listItemSchema(name), "default" to value)
        else -> error("OpenApiDtoSchemaMapper: field $name carries an unmapped wire value type (${value?.javaClass})")
    }

    /** Adds the persisted clamp span when it is derivable for this field. */
    private fun withBounds(name: String, default: Number, entry: Map<String, Any?>): Map<String, Any?> {
        val bounds = numericBounds[name] ?: return entry
        require(default.toDouble() in bounds.first.toDouble()..bounds.second.toDouble()) {
            "OpenApiDtoSchemaMapper: the $name default $default sits outside its generated bounds $bounds"
        }
        return entry + mapOf("minimum" to bounds.first, "maximum" to bounds.second)
    }

    /** Adds the closed value set when the wire domain is a known vocabulary for this field. */
    private fun withDomain(name: String, default: String, entry: Map<String, Any?>): Map<String, Any?> {
        val domain = stringDomains[name] ?: return entry
        require(default in domain) {
            "OpenApiDtoSchemaMapper: the $name default \"$default\" is outside its generated enum $domain"
        }
        return entry + mapOf("enum" to domain)
    }

    /** The item schema for a list field; a new list field fails here until mapped. */
    private fun listItemSchema(name: String): Map<String, Any?> =
        requireNotNull(listItemSchemas[name]) {
            "OpenApiDtoSchemaMapper: no item schema mapped for the list field $name — add it to listItemSchemas"
        }

    // ── the derivable domains, one table per extra ──

    /**
     * The persisted clamp spans, named by wire field: every entry cites the
     * constant pair SettingsDataStore's IntBounds clamps through (never a
     * re-copied literal). Fields whose only bound is a device capability
     * (the camera ranges) or a sentinel (mlModelProgress's -1 = none) stay
     * out — where a bound is not derivable it is omitted, not invented.
     */
    private val numericBounds: Map<String, Pair<Number, Number>> = mapOf(
        "port" to (StreamDefaults.WEB_PORT_MIN to StreamDefaults.WEB_PORT_MAX),
        "jpegQuality" to (StreamDefaults.JPEG_QUALITY_MIN to StreamDefaults.JPEG_QUALITY_MAX),
        "streamAudioBitrateKbps" to (StreamDefaults.AUDIO_BITRATE_MIN_KBPS to StreamDefaults.AUDIO_BITRATE_MAX_KBPS),
        "streamAudioChannels" to (StreamDefaults.AUDIO_CHANNELS_MIN to StreamDefaults.AUDIO_CHANNELS_MAX),
        "rtspPort" to (StreamDefaults.RTSP_PORT_MIN to StreamDefaults.RTSP_PORT_MAX),
        "hlsDvrSegments" to (0 to StreamDefaults.HLS_DVR_SEGMENTS_MAX),
        "watchdogMaxRetries" to (StreamDefaults.WATCHDOG_MAX_RETRIES_MIN to StreamDefaults.WATCHDOG_MAX_RETRIES_MAX),
        "watchdogCheckIntervalSeconds" to (
            StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS to
                StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS
            ),
        "motionSensitivityPercent" to (StreamDefaults.MOTION_SENSITIVITY_MIN to StreamDefaults.MOTION_SENSITIVITY_MAX),
        "motionPostRollSeconds" to (
            StreamDefaults.MOTION_POST_ROLL_MIN_SECONDS to StreamDefaults.MOTION_POST_ROLL_MAX_SECONDS
            ),
        "motionArmStartMinute" to (0 to StreamDefaults.MINUTES_PER_DAY - 1),
        "motionArmEndMinute" to (0 to StreamDefaults.MINUTES_PER_DAY - 1),
        "motionArmDaysMask" to (StreamDefaults.MOTION_ARM_DAYS_MASK_MIN to StreamDefaults.MOTION_ARM_DAYS_MASK_MAX),
        "soundThresholdPercent" to (StreamDefaults.SOUND_THRESHOLD_MIN to StreamDefaults.SOUND_THRESHOLD_MAX),
        "alertQuietHoursStartMinute" to (0 to StreamDefaults.MINUTES_PER_DAY - 1),
        "alertQuietHoursEndMinute" to (0 to StreamDefaults.MINUTES_PER_DAY - 1),
        "motionCooldownSeconds" to (
            StreamDefaults.MOTION_COOLDOWN_MIN_SECONDS to StreamDefaults.MOTION_COOLDOWN_MAX_SECONDS
            ),
        "soundCooldownSeconds" to (
            StreamDefaults.SOUND_COOLDOWN_MIN_SECONDS to StreamDefaults.SOUND_COOLDOWN_MAX_SECONDS
            ),
        "soundClassificationConfidencePercent" to (
            StreamDefaults.SOUND_CLASSIFICATION_MIN_PERCENT to StreamDefaults.SOUND_CLASSIFICATION_MAX_PERCENT
            ),
        "mlMinScorePercent" to (StreamDefaults.ML_SCORE_MIN_PERCENT to StreamDefaults.ML_SCORE_MAX_PERCENT),
        "continuousSegmentMinutes" to (
            StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES to StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES
            ),
        "sirenDurationSeconds" to (StreamDefaults.SIREN_DURATION_MIN_SECONDS to StreamDefaults.SIREN_DURATION_MAX_SECONDS),
        "autoDeterrenceCooldownSeconds" to (
            StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MIN_SECONDS to StreamDefaults.AUTO_DETERRENCE_COOLDOWN_MAX_SECONDS
            ),
        "mqttBrokerPort" to (StreamDefaults.MQTT_PORT_MIN to StreamDefaults.MQTT_PORT_MAX),
        "captureRetentionDays" to (StreamDefaults.RETENTION_DAYS_MIN to StreamDefaults.RETENTION_DAYS_MAX),
        "eventRetentionDays" to (StreamDefaults.RETENTION_DAYS_MIN to StreamDefaults.RETENTION_DAYS_MAX),
        "storageQuotaMb" to (StreamDefaults.STORAGE_QUOTA_MB_MIN to StreamDefaults.STORAGE_QUOTA_MB_MAX),
        // The overlay block's wire clamps (OverlaySettings.normalized).
        "overlayFontSize" to (OverlaySettings.FONT_SIZE_MIN to OverlaySettings.FONT_SIZE_MAX),
        "overlayPadding" to (OverlaySettings.PADDING_MIN to OverlaySettings.PADDING_MAX),
        "overlayLineHeight" to (OverlaySettings.LINE_HEIGHT_MIN to OverlaySettings.LINE_HEIGHT_MAX),
        // The zone rectangles' clamps (MaskingZone.normalized / MotionZone.normalized).
        "x" to (MotionZone.COORD_MIN.toDouble() to MotionZone.COORD_MAX.toDouble()),
        "y" to (MotionZone.COORD_MIN.toDouble() to MotionZone.COORD_MAX.toDouble()),
        "width" to (MotionZone.SIZE_MIN.toDouble() to MotionZone.SIZE_MAX.toDouble()),
        "height" to (MotionZone.SIZE_MIN.toDouble() to MotionZone.SIZE_MAX.toDouble()),
        "pixelateSize" to (MaskingZone.PIXELATE_SIZE_MIN to MaskingZone.PIXELATE_SIZE_MAX),
        "blurRadius" to (
            MaskingZone.BLUR_RADIUS_MIN.toDouble() to MaskingZone.BLUR_RADIUS_MAX.toDouble()
            ),
    )

    /**
     * The closed wire vocabularies, named by wire field — each cited from the
     * enum or constant set that owns the spelling. Fields whose domain is a
     * free-form string stay out.
     */
    private val stringDomains: Map<String, List<String>> = mapOf(
        "rtspInputFormat" to RtspInputFormat.entries.map { it.name },
        "rtspResolution" to RtspResolution.entries.map { it.wireName },
        "rtspVideoCodec" to RtspVideoCodec.entries.map { it.wireName },
        "backupTarget" to BackupTarget.entries.map { it.wireName },
        "overlayPosition" to OverlayPosition.entries.map { it.name },
        "mlModelState" to listOf(
            DetectionModelStore.STATE_NOT_DOWNLOADED,
            DetectionModelStore.STATE_DOWNLOADING,
            DetectionModelStore.STATE_READY,
            DetectionModelStore.STATE_FAILED,
        ),
        "type" to MaskingType.entries.map { it.name },
    )

    /**
     * The item schemas for the generated list properties. Empty by default
     * on the wire, so the element type is declared here, not derived from a
     * first element; an unmapped list field fails generation loudly.
     */
    private val listItemSchemas: Map<String, Map<String, Any?>> = mapOf(
        "maskingZones" to mapOf("\$ref" to "#/components/schemas/MaskingZoneDto"),
        "motionZones" to mapOf("\$ref" to "#/components/schemas/MotionZoneDto"),
        "soundClassificationAllowedClasses" to mapOf("type" to "string"),
        "soundTriggerClasses" to mapOf("type" to "string"),
    )
}
