package com.raulshma.lenscast.streaming.web

import androidx.datastore.preferences.core.Preferences
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.BackupTargetPolicy
import com.raulshma.lenscast.core.Secret
import com.raulshma.lenscast.core.SecretUpdate
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.core.parseEnumOrNull
import com.raulshma.lenscast.data.SettingEntry
import com.raulshma.lenscast.data.SettingPref
import com.raulshma.lenscast.data.adaptiveBitrateEnabledPref
import com.raulshma.lenscast.data.alertQuietHoursEnabledPref
import com.raulshma.lenscast.data.alertQuietHoursEndMinutePref
import com.raulshma.lenscast.data.alertQuietHoursStartMinutePref
import com.raulshma.lenscast.data.apiTokenEnabledPref
import com.raulshma.lenscast.data.apiTokenHashPref
import com.raulshma.lenscast.data.audioDeviceIdPref
import com.raulshma.lenscast.data.autoDeterrenceCooldownSecondsPref
import com.raulshma.lenscast.data.autoSirenPref
import com.raulshma.lenscast.data.autoTorchPref
import com.raulshma.lenscast.data.backupEnabledPref
import com.raulshma.lenscast.data.backupTargetPref
import com.raulshma.lenscast.data.backupWebdavPasswordPref
import com.raulshma.lenscast.data.backupWebdavUsernamePref
import com.raulshma.lenscast.data.backupWebdavUrlPref
import com.raulshma.lenscast.data.backupWifiOnlyPref
import com.raulshma.lenscast.data.captureRetentionDaysPref
import com.raulshma.lenscast.data.continuousRecordingPref
import com.raulshma.lenscast.data.continuousSegmentMinutesPref
import com.raulshma.lenscast.data.detectionNotificationsEnabledPref
import com.raulshma.lenscast.data.ecoIdleFpsEnabledPref
import com.raulshma.lenscast.data.encodedAdaptiveBitrateEnabledPref
import com.raulshma.lenscast.data.eventRetentionDaysPref
import com.raulshma.lenscast.data.fsiCameraResumePref
import com.raulshma.lenscast.data.hlsDvrSegmentsPref
import com.raulshma.lenscast.data.httpsEnabledPref
import com.raulshma.lenscast.data.jpegQualityPref
import com.raulshma.lenscast.data.keepAwakeEnabledPref
import com.raulshma.lenscast.data.mdnsEnabledPref
import com.raulshma.lenscast.data.mediaEncryptionEnabledPref
import com.raulshma.lenscast.data.mlDetectionEnabledPref
import com.raulshma.lenscast.data.mlIncludePetsPref
import com.raulshma.lenscast.data.mlIncludePersonPref
import com.raulshma.lenscast.data.mlIncludeVehiclesPref
import com.raulshma.lenscast.data.mlMinScorePercentPref
import com.raulshma.lenscast.data.motionArmDaysMaskPref
import com.raulshma.lenscast.data.motionArmEndMinutePref
import com.raulshma.lenscast.data.motionArmScheduleEnabledPref
import com.raulshma.lenscast.data.motionArmStartMinutePref
import com.raulshma.lenscast.data.motionCooldownSecondsPref
import com.raulshma.lenscast.data.motionDetectionEnabledPref
import com.raulshma.lenscast.data.motionPostRollSecondsPref
import com.raulshma.lenscast.data.motionRecordingEnabledPref
import com.raulshma.lenscast.data.motionSensitivityPref
import com.raulshma.lenscast.data.motionZonesPref
import com.raulshma.lenscast.data.mqttBrokerHostPref
import com.raulshma.lenscast.data.mqttBrokerPortPref
import com.raulshma.lenscast.data.mqttDiscoveryPrefixPref
import com.raulshma.lenscast.data.mqttEnabledPref
import com.raulshma.lenscast.data.mqttPasswordPref
import com.raulshma.lenscast.data.mqttTelemetryEnabledPref
import com.raulshma.lenscast.data.mqttTlsPref
import com.raulshma.lenscast.data.mqttUsernamePref
import com.raulshma.lenscast.data.onvifEnabledPref
import com.raulshma.lenscast.data.overlaySettingsPref
import com.raulshma.lenscast.data.pushEnabledPref
import com.raulshma.lenscast.data.recordingAudioEnabledPref
import com.raulshma.lenscast.data.rtmpEnabledPref
import com.raulshma.lenscast.data.rtmpUrlPref
import com.raulshma.lenscast.data.rtspBasicAuthCompatPref
import com.raulshma.lenscast.data.rtspEnabledPref
import com.raulshma.lenscast.data.rtspInputFormatPref
import com.raulshma.lenscast.data.rtspPortPref
import com.raulshma.lenscast.data.rtspResolutionPref
import com.raulshma.lenscast.data.rtspSubStreamEnabledPref
import com.raulshma.lenscast.data.rtspTlsEnabledPref
import com.raulshma.lenscast.data.rtspVideoCodecPref
import com.raulshma.lenscast.data.showPreviewPref
import com.raulshma.lenscast.data.sirenDurationSecondsPref
import com.raulshma.lenscast.data.soundAdaptiveNoiseFloorPref
import com.raulshma.lenscast.data.soundClassificationAllowedClassesPref
import com.raulshma.lenscast.data.soundClassificationConfidencePercentPref
import com.raulshma.lenscast.data.soundClassificationEnabledPref
import com.raulshma.lenscast.data.soundCooldownSecondsPref
import com.raulshma.lenscast.data.soundDetectionEnabledPref
import com.raulshma.lenscast.data.soundRecordingEnabledPref
import com.raulshma.lenscast.data.soundThresholdPercentPref
import com.raulshma.lenscast.data.soundTriggerClassesPref
import com.raulshma.lenscast.data.soundTriggerEnabledPref
import com.raulshma.lenscast.data.storageQuotaMbPref
import com.raulshma.lenscast.data.streamingPortPref
import com.raulshma.lenscast.data.streamAudioBitrateKbpsPref
import com.raulshma.lenscast.data.streamAudioChannelsPref
import com.raulshma.lenscast.data.streamAudioEchoCancellationPref
import com.raulshma.lenscast.data.streamAudioEnabledPref
import com.raulshma.lenscast.data.telegramBotTokenPref
import com.raulshma.lenscast.data.telegramChatIdPref
import com.raulshma.lenscast.data.tamperDetectionEnabledPref
import com.raulshma.lenscast.data.unifiedPushEnabledPref
import com.raulshma.lenscast.data.watchdogCheckIntervalSecondsPref
import com.raulshma.lenscast.data.watchdogEnabledPref
import com.raulshma.lenscast.data.watchdogMaxRetriesPref
import com.raulshma.lenscast.data.webStreamingEnabledPref
import com.raulshma.lenscast.data.webhookEnabledPref
import com.raulshma.lenscast.data.webhookHeadersPref
import com.raulshma.lenscast.data.webhookUrlPref
import com.raulshma.lenscast.data.whipEnabledPref
import com.raulshma.lenscast.data.whipStunServerPref
import com.raulshma.lenscast.data.whipTokenPref
import com.raulshma.lenscast.data.whipUrlPref
import com.raulshma.lenscast.data.srtEnabledPref
import com.raulshma.lenscast.data.srtUrlPref
import com.raulshma.lenscast.streaming.model.MaskingZoneDto
import com.raulshma.lenscast.streaming.model.MotionZoneDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.squareup.moshi.Types

/**
 * The streaming settings section's field catalog — the ONE home of the
 * wire↔pref↔convention mapping the section's decode and encode both derive
 * from (the catalog-carried precedent: [com.raulshma.lenscast.automation.schedule.AutomationActionCatalog]).
 * Each field is declared once as a [Convention] entry carrying its wire key(s),
 * its [SettingPref] descriptor, its DTO accessor and its per-field convention;
 * [decode] folds the entries into a JSON-ready wire tree and runs it through
 * the production [StreamingSettingsDto] adapter (the exact machinery
 * `mergeSection` uses on every PUT), and [encode] interprets the touched wire
 * keys into the [SettingEntry] list the ONE atomic section save persists.
 *
 * A new streaming field is one entry here (plus its descriptor and DTO field):
 * the completeness gate ([StreamingFieldCatalogTest]) derives the DTO wire key
 * set from the adapter and asserts the catalog covers it both directions, so a
 * field added to the DTO without an entry — the silent drift the old
 * hand-written decode/encode ladders allowed (every DTO param defaults, the
 * encode `when` had no else obligation) — fails a JVM test instead of answering
 * the default or never persisting.
 *
 * Response-only facts (`mlModelState`/`mlModelProgress`/`mlModelError`,
 * `apiTokenConfigured`) are NOT entries: the handler appends them to the decoded
 * DTO and the encode ignores them, exactly as before. The settled seams stay
 * untouched — `mergeSection` is the one merge owner, `saveEntries` the one atomic
 * write, touched-only encoding keeps untouched keys verbatim, and the
 * descriptors own every clamp/normalize on the way to disk.
 */
internal object StreamingFieldCatalog {

    private val dtoAdapter = AppJson.moshi.adapter(StreamingSettingsDto::class.java)

    // The zone-list adapters: internal so the test probes render their zone
    // expectations through the SAME declarations (one home, no twin adapters).
    internal val maskingZoneListAdapter = AppJson.moshi.adapter<List<MaskingZoneDto>>(
        Types.newParameterizedType(List::class.java, MaskingZoneDto::class.java),
    )
    internal val motionZoneListAdapter = AppJson.moshi.adapter<List<MotionZoneDto>>(
        Types.newParameterizedType(List::class.java, MotionZoneDto::class.java),
    )

    /**
     * One field's crossing convention: the wire key(s) it owns, how it decodes
     * out of a fresh [Preferences] pass into JSON-ready wire values, and how a
     * touched key encodes into the section's [SettingEntry] list. The runners
     * below fold these generically — no per-field ladder lives outside the
     * entry. [decode]'s map keys must equal [wireKeys] (the gate pins it).
     */
    internal sealed interface Convention {
        val wireKeys: Set<String>
        fun decode(prefs: Preferences): Map<String, Any?>
        fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences): List<SettingEntry<*>>
    }

    /**
     * A plain copy through the descriptor: decode reads the stored value,
     * encode writes the DTO value, each through [normalize] (identity for the
     * Int/Boolean/String fields; the backup target's tolerant policy parse on
     * both sides). The descriptor's own encode applies the identical clamp.
     */
    internal data class Plain<T>(
        val pref: SettingPref<T>,
        val wireKey: String,
        val normalize: (T) -> T = { it },
        val accessor: (StreamingSettingsDto) -> T,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf(wireKey to normalize(pref.decode(prefs)))
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched) listOf(SettingEntry(pref, normalize(accessor(stream)))) else emptyList()
    }

    /**
     * A tolerant enum: decode renders the stored constant's wire spelling
     * ([wireName] — `.name` for the input format, `.wireName` for the
     * resolution/codec), encode parses the presented name and SKIPS on an
     * absent/blank/unknown one ([parse] yields null), keeping the stored value.
     */
    internal data class EnumTolerant<E : Enum<E>>(
        val pref: SettingPref<E>,
        val wireKey: String,
        val accessor: (StreamingSettingsDto) -> String,
        val wireName: (E) -> String,
        val parse: (String?) -> E?,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf(wireKey to wireName(pref.decode(prefs)))
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched) {
                parse(accessor(stream))?.let { listOf(SettingEntry(pref, it)) } ?: emptyList()
            } else {
                emptyList()
            }
    }

    /**
     * A write-only credential: decode is BLANK (the [Secret] wire type always
     * serializes empty, so the merge base and GET carry no stored secret),
     * encode overwrites only on a presented value ([SecretUpdate.overwrites]) —
     * an empty update keeps the stored credential, never re-writing it.
     */
    internal data class SecretOverwrite(
        val pref: SettingPref<String>,
        val wireKey: String,
        val accessor: (StreamingSettingsDto) -> Secret,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf<String, Any?>(wireKey to "")
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched && SecretUpdate.overwrites(accessor(stream).value)) {
                listOf(SettingEntry(pref, accessor(stream).value))
            } else {
                emptyList()
            }
    }

    /**
     * A credential-bearing plain string the GET answer redacts (the handler
     * blanks the field; only the admin-only export envelope carries it
     * verbatim): decode renders the stored value — the export's lossless
     * path and the PUT's merge base read it — while encode overwrites only
     * on a presented non-blank value ([SecretUpdate.overwrites]), so the
     * blank echo a whole-section save sends back after a redacted GET keeps
     * the stored value — the [SecretOverwrite] keep rule without the
     * always-blank decode.
     */
    internal data class RedactedOverwrite(
        val pref: SettingPref<String>,
        val wireKey: String,
        val accessor: (StreamingSettingsDto) -> String,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf(wireKey to pref.decode(prefs))
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched && SecretUpdate.overwrites(accessor(stream))) {
                listOf(SettingEntry(pref, accessor(stream)))
            } else {
                emptyList()
            }
    }

    /**
     * A string-set field carried as a wire list: decode renders the stored set
     * as a list, encode folds the presented list back to a set. The descriptor
     * owns the class-gate normalization on both sides (unknown spellings drop,
     * an empty set folds to the curated default) — the convention only bridges
     * the List↔Set shape.
     */
    internal data class StringSet(
        val pref: SettingPref<Set<String>>,
        val wireKey: String,
        val accessor: (StreamingSettingsDto) -> List<String>,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf(wireKey to pref.decode(prefs).toList())
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched) listOf(SettingEntry(pref, accessor(stream).toSet())) else emptyList()
    }

    /**
     * The motion-zone list: decode renders the stored zones through their DTO
     * adapter (JSON-ready maps, never raw objects), encode maps the presented
     * DTOs back to domain zones (minting an id for a blank one). The descriptor
     * serializes/normalizes on the way to disk.
     */
    internal data class MotionZones(
        val pref: SettingPref<List<MotionZone>>,
        val wireKey: String,
        val accessor: (StreamingSettingsDto) -> List<MotionZoneDto>,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) =
            mapOf(wireKey to motionZoneListAdapter.toJsonValue(pref.decode(prefs).map(::toMotionZoneDto)))
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched) listOf(SettingEntry(pref, accessor(stream).map(::toMotionZone))) else emptyList()
    }

    /**
     * The apiToken enable flag's hash-and-gate: the flag only turns on when a
     * usable credential would exist after the update (a presented token, or a
     * stored hash read from [fresh] — the same untorn snapshot the merge base
     * came from). An enable with no plaintext and no stored hash fails closed.
     * Disabling always goes through.
     */
    internal data class HashGate(
        val pref: SettingPref<Boolean>,
        val wireKey: String,
        val enabledAccessor: (StreamingSettingsDto) -> Boolean,
        val tokenAccessor: (StreamingSettingsDto) -> Secret,
        val hashPref: SettingPref<String>,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf(wireKey to pref.decode(prefs))
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched) {
                listOf(
                    SettingEntry(
                        pref,
                        enabledAccessor(stream) && SecretUpdate.willHaveSecret(
                            tokenAccessor(stream).value,
                            hashPref.decode(fresh),
                        ),
                    ),
                )
            } else {
                emptyList()
            }
    }

    /**
     * The apiToken plaintext's hash-store: decode is BLANK (no plaintext is
     * persisted, and the [Secret] adapter would blank it regardless), encode
     * hashes a presented token (SHA-256 hex) and stores only the hash; an empty
     * value keeps the stored hash (disable rides [HashGate]).
     */
    internal data class HashStore(
        val hashPref: SettingPref<String>,
        val wireKey: String,
        val tokenAccessor: (StreamingSettingsDto) -> Secret,
    ) : Convention {
        override val wireKeys = setOf(wireKey)
        override fun decode(prefs: Preferences) = mapOf<String, Any?>(wireKey to "")
        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKey in touched && SecretUpdate.overwrites(tokenAccessor(stream).value)) {
                listOf(SettingEntry(hashPref, StreamAuthCrypto.sha256Hex(tokenAccessor(stream).value)))
            } else {
                emptyList()
            }
    }

    /**
     * The ONE composite overlay descriptor across its seventeen wire fields:
     * decode fans the stored [OverlaySettings] out to the wire keys, encode
     * fans any touched overlay field back into the whole rebuilt composite —
     * its untouched siblings ride the merged document, and the blank-fallbacks
     * (timestamp format, colors) and the position fallback read [fresh], the
     * same snapshot the merge base came from. Numeric clamping stays the
     * descriptor's (OverlaySettings.normalized runs on save).
     */
    internal data class OverlayComposite(
        val pref: SettingPref<OverlaySettings>,
        override val wireKeys: Set<String>,
    ) : Convention {
        override fun decode(prefs: Preferences): Map<String, Any?> {
            val overlay = pref.decode(prefs)
            return mapOf(
                "overlayEnabled" to overlay.enabled,
                "showTimestamp" to overlay.showTimestamp,
                "timestampFormat" to overlay.timestampFormat,
                "showBranding" to overlay.showBranding,
                "brandingText" to overlay.brandingText,
                "showStatus" to overlay.showStatus,
                "showIlluminance" to overlay.showIlluminance,
                "showCustomText" to overlay.showCustomText,
                "customText" to overlay.customText,
                "overlayPosition" to overlay.position.name,
                "overlayFontSize" to overlay.fontSize,
                "overlayTextColor" to overlay.textColor,
                "overlayBackgroundColor" to overlay.backgroundColor,
                "overlayPadding" to overlay.padding,
                "overlayLineHeight" to overlay.lineHeight,
                "maskingEnabled" to overlay.maskingEnabled,
                "maskingZones" to maskingZoneListAdapter.toJsonValue(overlay.maskingZones.map(::toMaskingZoneDto)),
            )
        }

        override fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences) =
            if (wireKeys.any(touched::contains)) {
                listOf(SettingEntry(pref, toOverlaySettings(stream, pref.decode(fresh))))
            } else {
                emptyList()
            }
    }

    /** The seventeen wire fields the composite overlay descriptor covers. */
    private val OVERLAY_WIRE_KEYS = setOf(
        "overlayEnabled", "showTimestamp", "timestampFormat", "showBranding",
        "brandingText", "showStatus", "showIlluminance", "showCustomText", "customText",
        "overlayPosition", "overlayFontSize", "overlayTextColor",
        "overlayBackgroundColor", "overlayPadding", "overlayLineHeight",
        "maskingEnabled", "maskingZones",
    )

    /**
     * Every streaming field, in DTO declaration order. Additive: a new field is
     * one entry carrying its convention; the completeness gate derives the DTO
     * key set and fails until the entry exists. Response-only facts stay out.
     */
    internal val entries: List<Convention> = listOf(
        Plain(streamingPortPref, "port") { it.port },
        Plain(webStreamingEnabledPref, "webStreamingEnabled") { it.webStreamingEnabled },
        Plain(jpegQualityPref, "jpegQuality") { it.jpegQuality },
        Plain(showPreviewPref, "showPreview") { it.showPreview },
        Plain(streamAudioEnabledPref, "streamAudioEnabled") { it.streamAudioEnabled },
        Plain(streamAudioBitrateKbpsPref, "streamAudioBitrateKbps") { it.streamAudioBitrateKbps },
        Plain(streamAudioChannelsPref, "streamAudioChannels") { it.streamAudioChannels },
        Plain(streamAudioEchoCancellationPref, "streamAudioEchoCancellation") { it.streamAudioEchoCancellation },
        Plain(recordingAudioEnabledPref, "recordingAudioEnabled") { it.recordingAudioEnabled },
        Plain(rtspEnabledPref, "rtspEnabled") { it.rtspEnabled },
        Plain(rtspTlsEnabledPref, "rtspTlsEnabled") { it.rtspTlsEnabled },
        Plain(rtspBasicAuthCompatPref, "rtspBasicAuthCompat") { it.rtspBasicAuthCompat },
        Plain(rtspPortPref, "rtspPort") { it.rtspPort },
        EnumTolerant(
            rtspInputFormatPref, "rtspInputFormat", { it.rtspInputFormat }, { it.name },
            { name -> parseEnumOrNull<RtspInputFormat>(name?.takeIf { it.isNotBlank() }) },
        ),
        EnumTolerant(rtspResolutionPref, "rtspResolution", { it.rtspResolution }, { it.wireName }, RtspResolution::fromWireNameOrNull),
        EnumTolerant(rtspVideoCodecPref, "rtspVideoCodec", { it.rtspVideoCodec }, { it.wireName }, RtspVideoCodec::fromWireNameOrNull),
        Plain(rtmpEnabledPref, "rtmpEnabled") { it.rtmpEnabled },
        SecretOverwrite(rtmpUrlPref, "rtmpUrl") { it.rtmpUrl },
        Plain(whipEnabledPref, "whipEnabled") { it.whipEnabled },
        Plain(whipUrlPref, "whipUrl") { it.whipUrl },
        SecretOverwrite(whipTokenPref, "whipToken") { it.whipToken },
        Plain(whipStunServerPref, "whipStunServer") { it.whipStunServer },
        Plain(srtEnabledPref, "srtEnabled") { it.srtEnabled },
        SecretOverwrite(srtUrlPref, "srtUrl") { it.srtUrl },
        Plain(adaptiveBitrateEnabledPref, "adaptiveBitrateEnabled") { it.adaptiveBitrateEnabled },
        Plain(encodedAdaptiveBitrateEnabledPref, "adaptiveEncodedBitrateEnabled") { it.adaptiveEncodedBitrateEnabled },
        Plain(rtspSubStreamEnabledPref, "rtspSubStreamEnabled") { it.rtspSubStreamEnabled },
        Plain(hlsDvrSegmentsPref, "hlsDvrSegments") { it.hlsDvrSegments },
        OverlayComposite(overlaySettingsPref, OVERLAY_WIRE_KEYS),
        Plain(watchdogEnabledPref, "watchdogEnabled") { it.watchdogEnabled },
        Plain(watchdogMaxRetriesPref, "watchdogMaxRetries") { it.watchdogMaxRetries },
        Plain(watchdogCheckIntervalSecondsPref, "watchdogCheckIntervalSeconds") { it.watchdogCheckIntervalSeconds },
        Plain(fsiCameraResumePref, "fsiCameraResumeEnabled") { it.fsiCameraResumeEnabled },
        Plain(mdnsEnabledPref, "mdnsEnabled") { it.mdnsEnabled },
        Plain(motionDetectionEnabledPref, "motionDetectionEnabled") { it.motionDetectionEnabled },
        Plain(motionSensitivityPref, "motionSensitivityPercent") { it.motionSensitivityPercent },
        MotionZones(motionZonesPref, "motionZones") { it.motionZones },
        Plain(motionRecordingEnabledPref, "motionRecordingEnabled") { it.motionRecordingEnabled },
        Plain(motionPostRollSecondsPref, "motionPostRollSeconds") { it.motionPostRollSeconds },
        Plain(motionArmScheduleEnabledPref, "motionArmScheduleEnabled") { it.motionArmScheduleEnabled },
        Plain(motionArmStartMinutePref, "motionArmStartMinute") { it.motionArmStartMinute },
        Plain(motionArmEndMinutePref, "motionArmEndMinute") { it.motionArmEndMinute },
        Plain(motionArmDaysMaskPref, "motionArmDaysMask") { it.motionArmDaysMask },
        Plain(soundDetectionEnabledPref, "soundDetectionEnabled") { it.soundDetectionEnabled },
        Plain(soundThresholdPercentPref, "soundThresholdPercent") { it.soundThresholdPercent },
        Plain(soundAdaptiveNoiseFloorPref, "soundAdaptiveNoiseFloor") { it.soundAdaptiveNoiseFloor },
        Plain(soundRecordingEnabledPref, "soundRecordingEnabled") { it.soundRecordingEnabled },
        Plain(motionCooldownSecondsPref, "motionCooldownSeconds") { it.motionCooldownSeconds },
        Plain(soundCooldownSecondsPref, "soundCooldownSeconds") { it.soundCooldownSeconds },
        Plain(soundClassificationEnabledPref, "soundClassificationEnabled") { it.soundClassificationEnabled },
        Plain(soundClassificationConfidencePercentPref, "soundClassificationConfidencePercent") { it.soundClassificationConfidencePercent },
        StringSet(soundClassificationAllowedClassesPref, "soundClassificationAllowedClasses") { it.soundClassificationAllowedClasses },
        Plain(soundTriggerEnabledPref, "soundTriggerEnabled") { it.soundTriggerEnabled },
        StringSet(soundTriggerClassesPref, "soundTriggerClasses") { it.soundTriggerClasses },
        Plain(webhookEnabledPref, "webhookEnabled") { it.webhookEnabled },
        Plain(webhookUrlPref, "webhookUrl") { it.webhookUrl },
        // The header map frequently carries an Authorization header, so the
        // GET answer redacts it (SettingsWebHandler.get) and a blank echo
        // keeps the stored headers — the export envelope alone carries it
        // verbatim.
        RedactedOverwrite(webhookHeadersPref, "webhookHeaders") { it.webhookHeaders },
        Plain(autoSirenPref, "autoSiren") { it.autoSiren },
        Plain(autoTorchPref, "autoTorch") { it.autoTorch },
        Plain(sirenDurationSecondsPref, "sirenDurationSeconds") { it.sirenDurationSeconds },
        Plain(autoDeterrenceCooldownSecondsPref, "autoDeterrenceCooldownSeconds") { it.autoDeterrenceCooldownSeconds },
        Plain(backupEnabledPref, "backupEnabled") { it.backupEnabled },
        Plain(backupWifiOnlyPref, "backupWifiOnly") { it.backupWifiOnly },
        Plain(backupTargetPref, "backupTarget", normalize = { BackupTargetPolicy.parse(it).wireName }) { it.backupTarget },
        Plain(backupWebdavUrlPref, "backupWebdavUrl") { it.backupWebdavUrl },
        Plain(backupWebdavUsernamePref, "backupWebdavUsername") { it.backupWebdavUsername },
        SecretOverwrite(backupWebdavPasswordPref, "backupWebdavPassword") { it.backupWebdavPassword },
        Plain(telegramChatIdPref, "telegramChatId") { it.telegramChatId },
        SecretOverwrite(telegramBotTokenPref, "telegramBotToken") { it.telegramBotToken },
        HashGate(apiTokenEnabledPref, "apiTokenEnabled", { it.apiTokenEnabled }, { it.apiToken }, apiTokenHashPref),
        HashStore(apiTokenHashPref, "apiToken") { it.apiToken },
        Plain(httpsEnabledPref, "httpsEnabled") { it.httpsEnabled },
        Plain(audioDeviceIdPref, "audioDeviceId") { it.audioDeviceId },
        Plain(detectionNotificationsEnabledPref, "detectionNotificationsEnabled") { it.detectionNotificationsEnabled },
        Plain(alertQuietHoursEnabledPref, "alertQuietHoursEnabled") { it.alertQuietHoursEnabled },
        Plain(alertQuietHoursStartMinutePref, "alertQuietHoursStartMinute") { it.alertQuietHoursStartMinute },
        Plain(alertQuietHoursEndMinutePref, "alertQuietHoursEndMinute") { it.alertQuietHoursEndMinute },
        Plain(keepAwakeEnabledPref, "keepAwakeEnabled") { it.keepAwakeEnabled },
        Plain(tamperDetectionEnabledPref, "tamperDetectionEnabled") { it.tamperDetectionEnabled },
        Plain(mqttEnabledPref, "mqttEnabled") { it.mqttEnabled },
        Plain(mqttBrokerHostPref, "mqttBrokerHost") { it.mqttBrokerHost },
        Plain(mqttBrokerPortPref, "mqttBrokerPort") { it.mqttBrokerPort },
        Plain(mqttUsernamePref, "mqttUsername") { it.mqttUsername },
        SecretOverwrite(mqttPasswordPref, "mqttPassword") { it.mqttPassword },
        Plain(mqttTlsPref, "mqttTls") { it.mqttTls },
        Plain(mqttDiscoveryPrefixPref, "mqttDiscoveryPrefix") { it.mqttDiscoveryPrefix },
        Plain(mqttTelemetryEnabledPref, "mqttTelemetryEnabled") { it.mqttTelemetryEnabled },
        Plain(pushEnabledPref, "pushEnabled") { it.pushEnabled },
        Plain(unifiedPushEnabledPref, "unifiedPushEnabled") { it.unifiedPushEnabled },
        Plain(captureRetentionDaysPref, "captureRetentionDays") { it.captureRetentionDays },
        Plain(eventRetentionDaysPref, "eventRetentionDays") { it.eventRetentionDays },
        Plain(storageQuotaMbPref, "storageQuotaMb") { it.storageQuotaMb },
        Plain(mediaEncryptionEnabledPref, "mediaEncryptionEnabled") { it.mediaEncryptionEnabled },
        Plain(mlDetectionEnabledPref, "mlDetectionEnabled") { it.mlDetectionEnabled },
        Plain(mlMinScorePercentPref, "mlMinScorePercent") { it.mlMinScorePercent },
        Plain(mlIncludePersonPref, "mlIncludePerson") { it.mlIncludePerson },
        Plain(mlIncludePetsPref, "mlIncludePets") { it.mlIncludePets },
        Plain(mlIncludeVehiclesPref, "mlIncludeVehicles") { it.mlIncludeVehicles },
        Plain(continuousRecordingPref, "continuousRecording") { it.continuousRecording },
        Plain(continuousSegmentMinutesPref, "continuousSegmentMinutes") { it.continuousSegmentMinutes },
        Plain(onvifEnabledPref, "onvifEnabled") { it.onvifEnabled },
        Plain(ecoIdleFpsEnabledPref, "ecoIdleFpsEnabled") { it.ecoIdleFpsEnabled },
    )

    /**
     * The streaming section decoded from one fresh file pass: fold every
     * entry's JSON-ready wire values into one tree and run it through the
     * production DTO adapter — the same [com.squareup.moshi.JsonAdapter.fromJsonValue]
     * `mergeSection` uses, so defaults/nulls/number handling are proven. The
     * response-only facts are absent here (the handler appends them); their DTO
     * defaults fold in and are overwritten there, leaving the wire bytes unchanged.
     */
    fun decode(prefs: Preferences): StreamingSettingsDto {
        val tree = HashMap<String, Any?>()
        for (entry in entries) tree.putAll(entry.decode(prefs))
        return requireNotNull(dtoAdapter.fromJsonValue(tree)) { "The streaming decode tree produced no document" }
    }

    /**
     * The section's (descriptor → value) entries for the ONE atomic save: each
     * convention interprets the touched wire keys ([touched], the raw JSON key
     * set) and contributes its entries; untouched keys stay verbatim on disk.
     * Unknown and response-only keys match no entry and are ignored, exactly as
     * the old encode ladder's fall-through did. An empty touched set writes nothing.
     */
    fun encode(stream: StreamingSettingsDto, touched: Set<String>, fresh: Preferences): List<SettingEntry<*>> {
        if (touched.isEmpty()) return emptyList()
        val entries = ArrayList<SettingEntry<*>>()
        for (entry in this.entries) entries += entry.encode(stream, touched, fresh)
        return entries
    }

    // ── the zone/overlay DTO helpers the composite and zone entries call ──

    private fun toMaskingZoneDto(zone: MaskingZone) = MaskingZoneDto(
        id = zone.id,
        label = zone.label,
        enabled = zone.enabled,
        type = zone.type.name,
        x = zone.x.toDouble(),
        y = zone.y.toDouble(),
        width = zone.width.toDouble(),
        height = zone.height.toDouble(),
        pixelateSize = zone.pixelateSize,
        blurRadius = zone.blurRadius.toDouble(),
    )

    private fun toMotionZoneDto(zone: MotionZone) = MotionZoneDto(
        id = zone.id,
        label = zone.label,
        enabled = zone.enabled,
        x = zone.x.toDouble(),
        y = zone.y.toDouble(),
        width = zone.width.toDouble(),
        height = zone.height.toDouble(),
    )

    private fun toMotionZone(dto: MotionZoneDto) = MotionZone(
        id = dto.id.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
        label = dto.label,
        enabled = dto.enabled,
        x = dto.x.toFloat(),
        y = dto.y.toFloat(),
        width = dto.width.toFloat(),
        height = dto.height.toFloat(),
    )

    private fun toOverlaySettings(stream: StreamingSettingsDto, current: OverlaySettings): OverlaySettings =
        // Pure DTO mapping only — numeric clamping is the Settings Store's
        // (OverlaySettings.normalized runs on save).
        OverlaySettings(
            enabled = stream.overlayEnabled,
            showTimestamp = stream.showTimestamp,
            timestampFormat = stream.timestampFormat.takeIf { it.isNotBlank() } ?: current.timestampFormat,
            showBranding = stream.showBranding,
            brandingText = stream.brandingText,
            showStatus = stream.showStatus,
            showIlluminance = stream.showIlluminance,
            showCustomText = stream.showCustomText,
            customText = stream.customText,
            position = parseEnum(stream.overlayPosition, current.position),
            fontSize = stream.overlayFontSize,
            textColor = stream.overlayTextColor.takeIf { it.isNotBlank() } ?: current.textColor,
            backgroundColor = stream.overlayBackgroundColor.takeIf { it.isNotBlank() } ?: current.backgroundColor,
            padding = stream.overlayPadding,
            lineHeight = stream.overlayLineHeight,
            maskingEnabled = stream.maskingEnabled,
            maskingZones = stream.maskingZones.map { dto ->
                MaskingZone(
                    id = dto.id.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                    label = dto.label,
                    enabled = dto.enabled,
                    type = parseEnum(dto.type, MaskingType.BLACKOUT),
                    x = dto.x.toFloat(),
                    y = dto.y.toFloat(),
                    width = dto.width.toFloat(),
                    height = dto.height.toFloat(),
                    pixelateSize = dto.pixelateSize,
                    blurRadius = dto.blurRadius.toFloat(),
                )
            },
        )
}