package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.BackupTargetPolicy
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.core.parseEnumOrNull
import com.raulshma.lenscast.core.Secret
import com.raulshma.lenscast.core.SecretUpdate
import androidx.datastore.preferences.core.Preferences
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OutputRotationPolicy
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.data.SettingEntry
import com.raulshma.lenscast.data.SettingsDataStore
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
import com.raulshma.lenscast.data.cameraSettingsPref
import com.raulshma.lenscast.data.captureRetentionDaysPref
import com.raulshma.lenscast.data.continuousRecordingPref
import com.raulshma.lenscast.data.continuousSegmentMinutesPref
import com.raulshma.lenscast.data.detectionNotificationsEnabledPref
import com.raulshma.lenscast.data.ecoIdleFpsEnabledPref
import com.raulshma.lenscast.data.encodedAdaptiveBitrateEnabledPref
import com.raulshma.lenscast.data.eventRetentionDaysPref
import com.raulshma.lenscast.data.fsiCameraResumePref
import com.raulshma.lenscast.data.hlsDvrSegmentsPref
import com.raulshma.lenscast.data.keepAwakeEnabledPref
import com.raulshma.lenscast.data.jpegQualityPref
import com.raulshma.lenscast.data.mediaEncryptionEnabledPref
import com.raulshma.lenscast.data.mdnsEnabledPref
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
import com.raulshma.lenscast.data.httpsEnabledPref
import com.raulshma.lenscast.data.webStreamingEnabledPref
import com.raulshma.lenscast.data.watchdogCheckIntervalSecondsPref
import com.raulshma.lenscast.data.watchdogEnabledPref
import com.raulshma.lenscast.data.watchdogMaxRetriesPref
import com.raulshma.lenscast.data.srtEnabledPref
import com.raulshma.lenscast.data.srtUrlPref
import com.raulshma.lenscast.data.streamAudioBitrateKbpsPref
import com.raulshma.lenscast.data.streamAudioChannelsPref
import com.raulshma.lenscast.data.streamAudioEchoCancellationPref
import com.raulshma.lenscast.data.streamAudioEnabledPref
import com.raulshma.lenscast.data.telegramBotTokenPref
import com.raulshma.lenscast.data.telegramChatIdPref
import com.raulshma.lenscast.data.tamperDetectionEnabledPref
import com.raulshma.lenscast.data.unifiedPushEnabledPref
import com.raulshma.lenscast.data.webhookEnabledPref
import com.raulshma.lenscast.data.webhookHeadersPref
import com.raulshma.lenscast.data.webhookUrlPref
import com.raulshma.lenscast.data.whipEnabledPref
import com.raulshma.lenscast.data.whipStunServerPref
import com.raulshma.lenscast.data.whipTokenPref
import com.raulshma.lenscast.data.whipUrlPref
import com.raulshma.lenscast.streaming.model.CameraSettingsDto
import com.raulshma.lenscast.streaming.model.MaskingZoneDto
import com.raulshma.lenscast.streaming.model.MotionZoneDto
import com.raulshma.lenscast.streaming.model.SettingsExportDto
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import com.raulshma.lenscast.streaming.model.SettingsUpdateRequestDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types

/**
 * /api/settings — JSON-in/JSON-out mapping between the Web API DTOs and the
 * Settings Store. This handler only *writes* settings; the Settings Applier
 * applies them to the runtime.
 *
 * The RTSP video codec rides the same store round-trip as every other
 * setting: GET reads the persisted flow, PUT saves through its descriptor
 * (an absent or unknown wire name is skipped), and the Settings Applier
 * applies it to the manager through the restart ladder.
 */
class SettingsWebHandler(
    private val settingsDataStore: SettingsDataStore,
    /** The on-demand model store; its state rides the settings response, its download the POST route. */
    private val detectionModelStore: DetectionModelStore,
    /** The on-demand YAMNet model store; its download rides its own POST route (audio-model). */
    private val audioModelStore: AudioModelStore,
    /** The export envelope's timestamp source. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(SettingsResponseDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java) }
    private val exportAdapter by lazy { AppJson.moshi.adapter(SettingsExportDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }
    private val cameraDtoAdapter by lazy { AppJson.moshi.adapter(CameraSettingsDto::class.java) }
    private val streamingDtoAdapter by lazy { AppJson.moshi.adapter(StreamingSettingsDto::class.java) }

    /**
     * The raw body's key tree — the only way to know which section keys the
     * client actually sent, which is exactly what the section merge below
     * preserves vs. overlays.
     */
    private val rawBodyAdapter by lazy {
        AppJson.moshi.adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
    }

    suspend fun get(): String = responseAdapter.toJson(buildResponse())

    /**
     * GET /api/settings/export — the current settings document inside the
     * versioned [SettingsExportDto] envelope, for downloading as a file and
     * restoring via POST /api/settings/import on this or another device.
     *
     * What the export carries: exactly what GET /api/settings returns — the
     * write-only credentials (WebDAV password, Telegram bot token, MQTT
     * password, WHIP bearer token, API token, and the RTMP push URL, whose
     * stream key is embedded in it) serialize blank through the [Secret]
     * wire type, but `webhookHeaders` is a user-authored header map that
     * round-trips in full and frequently contains an Authorization header.
     * The file is configuration, not a sanitized share artifact — treat it
     * as such.
     */
    suspend fun export(): String =
        exportAdapter.toJson(
            SettingsExportDto(
                exportedAtMs = nowMs(),
                settings = buildResponse(),
            ),
        )

    /**
     * POST /api/settings/import — accepts a [SettingsExportDto] envelope (the
     * export route's own shape, schema-version gated) or a bare settings
     * update document, and applies it through the exact [put] path a
     * dashboard save uses, so every clamp and write-only-secret rule holds
     * identically. A bare document carries [SettingsUpdateRequestDto] PUT
     * semantics: fields present in a section apply, fields it omits keep
     * their stored values (section-level merge — see [put]) — the envelope
     * is the lossless path and the one the dashboard's export button
     * produces.
     * Fails with an [IllegalArgumentException] (the router's error-payload
     * shape) for unparseable bodies, unsupported schema versions, and
     * documents with no settings at all.
     */
    suspend fun import(body: String): String {
        val parsed = SettingsImportParser.parse(body)
        val update = when (parsed) {
            is SettingsImportParser.Parsed.Envelope -> {
                val settings = requireNotNull(parsed.export.settings) { "Export envelope carries no settings" }
                SettingsUpdateRequestDto(camera = settings.camera, streaming = settings.streaming)
            }
            is SettingsImportParser.Parsed.RawUpdate -> parsed.update
        }
        put(updateAdapter.toJson(update))
        return successAdapter.toJson(SuccessResponse())
    }

    /**
     * The response decodes one fresh file pass ([SettingsDataStore.readFresh])
     * through the pref descriptors: a GET landing right after a save answers
     * from the disk, never from the shared flows' lagging collector beat —
     * the stale `iso:null` / `compat:null` echo a back-to-back curl used to
     * observe. Only the model lifecycle stays live: runtime state, not a
     * persisted setting.
     */
    private suspend fun buildResponse(): SettingsResponseDto =
        settingsDataStore.readFresh { prefs ->
            SettingsResponseDto(
                camera = toCameraDto(cameraSettingsPref.decode(prefs)),
                streaming = streamingDtoFrom(prefs),
            )
        }

    /**
     * The streaming section of the settings document, decoded from the
     * [SettingsDataStore.readFresh] file pass. The same decode feeds the GET
     * response and the PUT's stored-side merge base, so both answer and
     * overlay come from one committed file state.
     */
    private fun streamingDtoFrom(prefs: Preferences): StreamingSettingsDto {
        val overlay = overlaySettingsPref.decode(prefs)
        // Response-only model facts: the store's published lifecycle.
        val modelWire = DetectionModelStore.wireFields(detectionModelStore.state.value)
        return StreamingSettingsDto(
                port = streamingPortPref.decode(prefs),
                webStreamingEnabled = webStreamingEnabledPref.decode(prefs),
                jpegQuality = jpegQualityPref.decode(prefs),
                showPreview = showPreviewPref.decode(prefs),
                streamAudioEnabled = streamAudioEnabledPref.decode(prefs),
                streamAudioBitrateKbps = streamAudioBitrateKbpsPref.decode(prefs),
                streamAudioChannels = streamAudioChannelsPref.decode(prefs),
                streamAudioEchoCancellation = streamAudioEchoCancellationPref.decode(prefs),
                recordingAudioEnabled = recordingAudioEnabledPref.decode(prefs),
                rtspEnabled = rtspEnabledPref.decode(prefs),
                rtspTlsEnabled = rtspTlsEnabledPref.decode(prefs),
                rtspBasicAuthCompat = rtspBasicAuthCompatPref.decode(prefs),
                rtspPort = rtspPortPref.decode(prefs),
                rtspInputFormat = rtspInputFormatPref.decode(prefs).name,
                rtspResolution = rtspResolutionPref.decode(prefs).wireName,
                rtspVideoCodec = rtspVideoCodecPref.decode(prefs).wireName,
                rtmpEnabled = rtmpEnabledPref.decode(prefs),
                // Every secret below is a plain copy of its stored value: the
                // Secret wire type (App Json) serializes all of them blank, so
                // no stored credential ever round-trips (see core/Secret.kt).
                rtmpUrl = Secret(rtmpUrlPref.decode(prefs)),
                whipEnabled = whipEnabledPref.decode(prefs),
                whipUrl = whipUrlPref.decode(prefs),
                whipToken = Secret(whipTokenPref.decode(prefs)),
                whipStunServer = whipStunServerPref.decode(prefs),
                srtEnabled = srtEnabledPref.decode(prefs),
                srtUrl = Secret(srtUrlPref.decode(prefs)),
                adaptiveBitrateEnabled = adaptiveBitrateEnabledPref.decode(prefs),
                adaptiveEncodedBitrateEnabled = encodedAdaptiveBitrateEnabledPref.decode(prefs),
                rtspSubStreamEnabled = rtspSubStreamEnabledPref.decode(prefs),
                hlsDvrSegments = hlsDvrSegmentsPref.decode(prefs),
                overlayEnabled = overlay.enabled,
                showTimestamp = overlay.showTimestamp,
                timestampFormat = overlay.timestampFormat,
                showBranding = overlay.showBranding,
                brandingText = overlay.brandingText,
                showStatus = overlay.showStatus,
                showCustomText = overlay.showCustomText,
                customText = overlay.customText,
                overlayPosition = overlay.position.name,
                overlayFontSize = overlay.fontSize,
                overlayTextColor = overlay.textColor,
                overlayBackgroundColor = overlay.backgroundColor,
                overlayPadding = overlay.padding,
                overlayLineHeight = overlay.lineHeight,
                maskingEnabled = overlay.maskingEnabled,
                maskingZones = overlay.maskingZones.map(::toZoneDto),
                watchdogEnabled = watchdogEnabledPref.decode(prefs),
                watchdogMaxRetries = watchdogMaxRetriesPref.decode(prefs),
                watchdogCheckIntervalSeconds = watchdogCheckIntervalSecondsPref.decode(prefs),
                fsiCameraResumeEnabled = fsiCameraResumePref.decode(prefs),
                mdnsEnabled = mdnsEnabledPref.decode(prefs),
                motionDetectionEnabled = motionDetectionEnabledPref.decode(prefs),
                motionSensitivityPercent = motionSensitivityPref.decode(prefs),
                motionZones = motionZonesPref.decode(prefs).map(::toMotionZoneDto),
                motionRecordingEnabled = motionRecordingEnabledPref.decode(prefs),
                motionPostRollSeconds = motionPostRollSecondsPref.decode(prefs),
                motionArmScheduleEnabled = motionArmScheduleEnabledPref.decode(prefs),
                motionArmStartMinute = motionArmStartMinutePref.decode(prefs),
                motionArmEndMinute = motionArmEndMinutePref.decode(prefs),
                motionArmDaysMask = motionArmDaysMaskPref.decode(prefs),
                soundDetectionEnabled = soundDetectionEnabledPref.decode(prefs),
                soundThresholdPercent = soundThresholdPercentPref.decode(prefs),
                soundAdaptiveNoiseFloor = soundAdaptiveNoiseFloorPref.decode(prefs),
                soundRecordingEnabled = soundRecordingEnabledPref.decode(prefs),
                motionCooldownSeconds = motionCooldownSecondsPref.decode(prefs),
                soundCooldownSeconds = soundCooldownSecondsPref.decode(prefs),
                soundClassificationEnabled = soundClassificationEnabledPref.decode(prefs),
                soundClassificationConfidencePercent = soundClassificationConfidencePercentPref.decode(prefs),
                soundClassificationAllowedClasses = soundClassificationAllowedClassesPref.decode(prefs).toList(),
                soundTriggerEnabled = soundTriggerEnabledPref.decode(prefs),
                soundTriggerClasses = soundTriggerClassesPref.decode(prefs).toList(),
                webhookEnabled = webhookEnabledPref.decode(prefs),
                webhookUrl = webhookUrlPref.decode(prefs),
                webhookHeaders = webhookHeadersPref.decode(prefs),
                autoSiren = autoSirenPref.decode(prefs),
                autoTorch = autoTorchPref.decode(prefs),
                sirenDurationSeconds = sirenDurationSecondsPref.decode(prefs),
                autoDeterrenceCooldownSeconds = autoDeterrenceCooldownSecondsPref.decode(prefs),
                backupEnabled = backupEnabledPref.decode(prefs),
                backupWifiOnly = backupWifiOnlyPref.decode(prefs),
                backupTarget = BackupTargetPolicy.parse(backupTargetPref.decode(prefs)).wireName,
                backupWebdavUrl = backupWebdavUrlPref.decode(prefs),
                backupWebdavUsername = backupWebdavUsernamePref.decode(prefs),
                backupWebdavPassword = Secret(backupWebdavPasswordPref.decode(prefs)),
                telegramChatId = telegramChatIdPref.decode(prefs),
                telegramBotToken = Secret(telegramBotTokenPref.decode(prefs)),
                apiTokenEnabled = apiTokenEnabledPref.decode(prefs),
                apiTokenConfigured = apiTokenHashPref.decode(prefs).isNotBlank(),
                // The token's stored form is the SHA-256 hash, and there is
                // no plaintext to copy — the field stays empty here; the
                // Secret adapter would blank it regardless.
                apiToken = Secret(""),
                httpsEnabled = httpsEnabledPref.decode(prefs),
                audioDeviceId = audioDeviceIdPref.decode(prefs),
                detectionNotificationsEnabled = detectionNotificationsEnabledPref.decode(prefs),
                alertQuietHoursEnabled = alertQuietHoursEnabledPref.decode(prefs),
                alertQuietHoursStartMinute = alertQuietHoursStartMinutePref.decode(prefs),
                alertQuietHoursEndMinute = alertQuietHoursEndMinutePref.decode(prefs),
                keepAwakeEnabled = keepAwakeEnabledPref.decode(prefs),
                tamperDetectionEnabled = tamperDetectionEnabledPref.decode(prefs),
                mqttEnabled = mqttEnabledPref.decode(prefs),
                mqttBrokerHost = mqttBrokerHostPref.decode(prefs),
                mqttBrokerPort = mqttBrokerPortPref.decode(prefs),
                mqttUsername = mqttUsernamePref.decode(prefs),
                mqttPassword = Secret(mqttPasswordPref.decode(prefs)),
                mqttTls = mqttTlsPref.decode(prefs),
                mqttDiscoveryPrefix = mqttDiscoveryPrefixPref.decode(prefs),
                mqttTelemetryEnabled = mqttTelemetryEnabledPref.decode(prefs),
                pushEnabled = pushEnabledPref.decode(prefs),
                unifiedPushEnabled = unifiedPushEnabledPref.decode(prefs),
                captureRetentionDays = captureRetentionDaysPref.decode(prefs),
                eventRetentionDays = eventRetentionDaysPref.decode(prefs),
                storageQuotaMb = storageQuotaMbPref.decode(prefs),
                mediaEncryptionEnabled = mediaEncryptionEnabledPref.decode(prefs),
                mlDetectionEnabled = mlDetectionEnabledPref.decode(prefs),
                mlMinScorePercent = mlMinScorePercentPref.decode(prefs),
                mlIncludePerson = mlIncludePersonPref.decode(prefs),
                mlIncludePets = mlIncludePetsPref.decode(prefs),
                mlIncludeVehicles = mlIncludeVehiclesPref.decode(prefs),
                mlModelState = modelWire.state,
                mlModelProgress = modelWire.progress,
                mlModelError = modelWire.error,
                continuousRecording = continuousRecordingPref.decode(prefs),
                continuousSegmentMinutes = continuousSegmentMinutesPref.decode(prefs),
                onvifEnabled = onvifEnabledPref.decode(prefs),
                ecoIdleFpsEnabled = ecoIdleFpsEnabledPref.decode(prefs),
        )
    }

    /**
     * PUT /api/settings — section-level merge: the raw JSON keys the client
     * sent overlay the stored document ([mergeSection]), so a section that
     * omits a field keeps the stored value instead of silently resetting it
     * to the DTO default. Full-section writers (the import envelope) carry
     * every key and behave exactly as before, and the dashboard PUTs its
     * partial sections straight through for the server to merge (no
     * client-side GET-merge-PUT chain); an explicit JSON null still overlays
     * as null and resets an optional field.
     * Each section then persists in ONE atomic DataStore transaction
     * ([SettingsDataStore.saveEntries], the entries built by
     * [streamingEntries]) — a crash mid-PUT cannot land a half-applied
     * section, and a concurrent disk-truth reader sees the old or the new
     * document, never a torn one. The camera rebuild keeps the torch
     * contract: no torch fields ride the DTO, so the stored torch pair
     * carries over verbatim — convergence to the live LED fact is
     * CameraService.applySettings' job (the single owner; the writer-side
     * live-fact snapshot this rebuild once took was the protocol behind
     * fixes 22fe040/e84b36d).
     */
    suspend fun put(body: String): String {
        val request = updateAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid settings JSON")
        val rawSections: Map<String, Any> = rawBodyAdapter.fromJson(body) ?: emptyMap()

        request.camera?.let {
            val current = settingsDataStore.settingsFromDisk()
            val cam = mergeSection(cameraDtoAdapter, toCameraDto(current), rawSections["camera"])
            val newSettings = CameraSettings(
                exposureCompensation = cam.exposureCompensation.coerceIn(
                    CameraSettings.EXPOSURE_COMPENSATION_MIN,
                    CameraSettings.EXPOSURE_COMPENSATION_MAX,
                ),
                iso = cam.iso?.let { if (it > 0) it else null },
                exposureTime = cam.exposureTime?.let { if (it > 0) it else null },
                focusMode = parseEnum(cam.focusMode, current.focusMode),
                focusDistance = cam.focusDistance?.coerceIn(0f, CameraSettings.FOCUS_DISTANCE_MAX),
                whiteBalance = parseEnum(cam.whiteBalance, current.whiteBalance),
                colorTemperature = cam.colorTemperature?.coerceIn(
                    CameraSettings.COLOR_TEMPERATURE_MIN,
                    CameraSettings.COLOR_TEMPERATURE_MAX,
                ),
                zoomRatio = cam.zoomRatio.toFloat().coerceIn(CameraSettings.ZOOM_RATIO_MIN, CameraSettings.ZOOM_RATIO_MAX),
                frameRate = cam.frameRate.coerceIn(CameraSettings.FRAME_RATE_MIN, CameraSettings.FRAME_RATE_MAX),
                resolution = parseEnum(cam.resolution, current.resolution),
                stabilization = cam.stabilization,
                hdrMode = parseEnum(cam.hdrMode, current.hdrMode),
                sceneMode = cam.sceneMode,
                nightVisionMode = parseEnum(cam.nightVisionMode, current.nightVisionMode),
                // Torch is NOT a web-camera-section setting: the camera DTO
                // carries no torch fields (GET can't present them, PUT can't
                // change them), so the rebuild carries the STORED pair
                // verbatim. Converging it to the live LED fact is
                // CameraService.applySettings' job — this save lands, the
                // applier applies it, and the untouched torch pair converges
                // there (carrying the stale stored value used to kill a lit
                // LED — the scheduled-automation torch, too — on every
                // dashboard camera-section save, issue #16, fixed writer-side
                // by 22fe040 before the verdict moved to the applier).
                // torchStrength stays the stored intent: a live flip drives
                // the device-default brightness and carries no strength
                // intent of its own.
                torchEnabled = current.torchEnabled,
                outputRotation = OutputRotationPolicy.coerce(cam.outputRotation),
                orientationLocked = cam.orientationLocked,
                torchStrength = current.torchStrength,
            )
            settingsDataStore.saveSettings(newSettings)
        }

        request.streaming?.let {
            val rawStreaming = rawSections["streaming"] as? Map<*, *>
            val stream = mergeSection(
                streamingDtoAdapter,
                settingsDataStore.readFresh(::streamingDtoFrom),
                rawStreaming,
            )
            // The raw section's key set is the touched set: the fields the
            // client actually sent. Only they become entries — untouched
            // siblings keep their stored values bit-for-bit instead of being
            // re-encoded back onto the file.
            val touched = rawStreaming?.keys?.filterIsInstance<String>()?.toSet().orEmpty()
            // ONE atomic section save for the whole merged document (this
            // used to be ~103 independent saveX transactions, each its own
            // read-modify-write pass of the preferences file). Out-of-range
            // numeric values are still the Settings Store's clamp policy, not
            // this handler's: the entries ride the same descriptors, which
            // coerce on encode.
            settingsDataStore.saveEntries(streamingEntries(stream, touched))
        }

        return successAdapter.toJson(SuccessResponse())
    }

    /** The sixteen wire fields the composite overlay descriptor covers. */
    private val overlayWireFields = setOf(
        "overlayEnabled", "showTimestamp", "timestampFormat", "showBranding",
        "brandingText", "showStatus", "showCustomText", "customText",
        "overlayPosition", "overlayFontSize", "overlayTextColor",
        "overlayBackgroundColor", "overlayPadding", "overlayLineHeight",
        "maskingEnabled", "maskingZones",
    )

    /**
     * The streaming section's (descriptor → value) entries for the ONE atomic
     * [SettingsDataStore.saveEntries] pass — one entry per wire field the
     * client actually sent ([touched], the raw JSON key set). Every entry
     * keeps the exact per-field convention of the saveX calls it replaces:
     * the tolerant enum mappings skip (an absent or unknown wire name keeps
     * the stored one), the write-only secrets overwrite only on a presented
     * value, the apiToken keeps its hash-and-gate, and each descriptor's own
     * encode applies the identical clamp/normalize on the way to disk. Wire
     * fields with no saver (`mlModelState`, `apiTokenConfigured` —
     * response-only facts) and unknown names have no entry here and are
     * ignored, as before.
     */
    private fun streamingEntries(stream: StreamingSettingsDto, touched: Set<String>): List<SettingEntry<*>> {
        if (touched.isEmpty()) return emptyList()
        val entries = ArrayList<SettingEntry<*>>(touched.size)
        // The overlay is ONE composite descriptor across the wire fields
        // above: any touched overlay field lands the whole rebuilt
        // OverlaySettings — its untouched siblings ride the merge base,
        // exactly as before.
        if (overlayWireFields.any(touched::contains)) {
            entries += SettingEntry(
                overlaySettingsPref,
                toOverlaySettings(stream, settingsDataStore.overlaySettings.value),
            )
        }
        for (key in touched) when (key) {
            "port" -> entries += SettingEntry(streamingPortPref, stream.port)
            "webStreamingEnabled" -> entries += SettingEntry(webStreamingEnabledPref, stream.webStreamingEnabled)
            "jpegQuality" -> entries += SettingEntry(jpegQualityPref, stream.jpegQuality)
            "showPreview" -> entries += SettingEntry(showPreviewPref, stream.showPreview)
            "streamAudioEnabled" -> entries += SettingEntry(streamAudioEnabledPref, stream.streamAudioEnabled)
            "streamAudioBitrateKbps" -> entries += SettingEntry(streamAudioBitrateKbpsPref, stream.streamAudioBitrateKbps)
            "streamAudioChannels" -> entries += SettingEntry(streamAudioChannelsPref, stream.streamAudioChannels)
            "streamAudioEchoCancellation" -> entries += SettingEntry(streamAudioEchoCancellationPref, stream.streamAudioEchoCancellation)
            "recordingAudioEnabled" -> entries += SettingEntry(recordingAudioEnabledPref, stream.recordingAudioEnabled)
            "rtspEnabled" -> entries += SettingEntry(rtspEnabledPref, stream.rtspEnabled)
            "rtspTlsEnabled" -> entries += SettingEntry(rtspTlsEnabledPref, stream.rtspTlsEnabled)
            "rtspBasicAuthCompat" -> entries += SettingEntry(rtspBasicAuthCompatPref, stream.rtspBasicAuthCompat)
            "rtspPort" -> entries += SettingEntry(rtspPortPref, stream.rtspPort)
            // Same tolerant mapping as before: an absent or unknown input
            // format wire name is skipped, keeping the stored one.
            "rtspInputFormat" -> parseEnumOrNull<RtspInputFormat>(stream.rtspInputFormat.takeIf { it.isNotBlank() })
                ?.let { entries += SettingEntry(rtspInputFormatPref, it) }
            // Same tolerant mapping as the input format: an absent or unknown
            // resolution wire name is skipped, keeping the stored one.
            "rtspResolution" -> RtspResolution.fromWireNameOrNull(stream.rtspResolution)
                ?.let { entries += SettingEntry(rtspResolutionPref, it) }
            // Same tolerant mapping as the input format/resolution: an absent
            // or unknown codec wire name is skipped, keeping the stored one.
            "rtspVideoCodec" -> RtspVideoCodec.fromWireNameOrNull(stream.rtspVideoCodec)
                ?.let { entries += SettingEntry(rtspVideoCodecPref, it) }
            "rtmpEnabled" -> entries += SettingEntry(rtmpEnabledPref, stream.rtmpEnabled)
            // RTMP push: write-only (Secret) — an empty update keeps the
            // stored URL. A presented value is judged at start time by
            // RtmpUrl.parse (plus the H.264 codec gate).
            "rtmpUrl" -> if (SecretUpdate.overwrites(stream.rtmpUrl.value)) {
                entries += SettingEntry(rtmpUrlPref, stream.rtmpUrl.value)
            }
            // WHIP push: endpoint/STUN round-trip; validity is judged at
            // start time by the output (a readable error beats silently
            // "fixing" a URL the user mistyped).
            "whipEnabled" -> entries += SettingEntry(whipEnabledPref, stream.whipEnabled)
            "whipUrl" -> entries += SettingEntry(whipUrlPref, stream.whipUrl)
            "whipStunServer" -> entries += SettingEntry(whipStunServerPref, stream.whipStunServer)
            // The bearer token is write-only exactly like the MQTT password.
            "whipToken" -> if (SecretUpdate.overwrites(stream.whipToken.value)) {
                entries += SettingEntry(whipTokenPref, stream.whipToken.value)
            }
            // SRT push: the enable toggle round-trips; the write-only URL is
            // judged at start time by SrtUrl.parse, exactly like the RTMP
            // push URL (Secret — an empty update keeps the stored one).
            "srtEnabled" -> entries += SettingEntry(srtEnabledPref, stream.srtEnabled)
            "srtUrl" -> if (SecretUpdate.overwrites(stream.srtUrl.value)) {
                entries += SettingEntry(srtUrlPref, stream.srtUrl.value)
            }
            "adaptiveBitrateEnabled" -> entries += SettingEntry(adaptiveBitrateEnabledPref, stream.adaptiveBitrateEnabled)
            "adaptiveEncodedBitrateEnabled" -> entries += SettingEntry(
                encodedAdaptiveBitrateEnabledPref,
                stream.adaptiveEncodedBitrateEnabled,
            )
            "rtspSubStreamEnabled" -> entries += SettingEntry(rtspSubStreamEnabledPref, stream.rtspSubStreamEnabled)
            "hlsDvrSegments" -> entries += SettingEntry(hlsDvrSegmentsPref, stream.hlsDvrSegments)
            "watchdogEnabled" -> entries += SettingEntry(watchdogEnabledPref, stream.watchdogEnabled)
            "watchdogMaxRetries" -> entries += SettingEntry(watchdogMaxRetriesPref, stream.watchdogMaxRetries)
            "watchdogCheckIntervalSeconds" -> entries += SettingEntry(
                watchdogCheckIntervalSecondsPref,
                stream.watchdogCheckIntervalSeconds,
            )
            // Opt-in, default off: the post-death camera bring-up prompt
            // (blueprint P1 item 11) — lights the screen by design, so the
            // flag rides the standard round-trip like every other gate.
            "fsiCameraResumeEnabled" -> entries += SettingEntry(fsiCameraResumePref, stream.fsiCameraResumeEnabled)
            "mdnsEnabled" -> entries += SettingEntry(mdnsEnabledPref, stream.mdnsEnabled)
            "motionDetectionEnabled" -> entries += SettingEntry(motionDetectionEnabledPref, stream.motionDetectionEnabled)
            "motionSensitivityPercent" -> entries += SettingEntry(motionSensitivityPref, stream.motionSensitivityPercent)
            "motionZones" -> entries += SettingEntry(motionZonesPref, stream.motionZones.map { toMotionZone(it) })
            "motionRecordingEnabled" -> entries += SettingEntry(motionRecordingEnabledPref, stream.motionRecordingEnabled)
            "motionPostRollSeconds" -> entries += SettingEntry(motionPostRollSecondsPref, stream.motionPostRollSeconds)
            "motionArmScheduleEnabled" -> entries += SettingEntry(motionArmScheduleEnabledPref, stream.motionArmScheduleEnabled)
            "motionArmStartMinute" -> entries += SettingEntry(motionArmStartMinutePref, stream.motionArmStartMinute)
            "motionArmEndMinute" -> entries += SettingEntry(motionArmEndMinutePref, stream.motionArmEndMinute)
            "motionArmDaysMask" -> entries += SettingEntry(motionArmDaysMaskPref, stream.motionArmDaysMask)
            "soundDetectionEnabled" -> entries += SettingEntry(soundDetectionEnabledPref, stream.soundDetectionEnabled)
            "soundThresholdPercent" -> entries += SettingEntry(soundThresholdPercentPref, stream.soundThresholdPercent)
            "soundAdaptiveNoiseFloor" -> entries += SettingEntry(soundAdaptiveNoiseFloorPref, stream.soundAdaptiveNoiseFloor)
            "soundRecordingEnabled" -> entries += SettingEntry(soundRecordingEnabledPref, stream.soundRecordingEnabled)
            "motionCooldownSeconds" -> entries += SettingEntry(motionCooldownSecondsPref, stream.motionCooldownSeconds)
            "soundCooldownSeconds" -> entries += SettingEntry(soundCooldownSecondsPref, stream.soundCooldownSeconds)
            // The allow-list normalizes on save (unknown spellings drop out, an
            // empty set folds back to the curated default), so a document that
            // omits or empties the list can never widen or disarm the gate.
            "soundClassificationEnabled" -> entries += SettingEntry(
                soundClassificationEnabledPref,
                stream.soundClassificationEnabled,
            )
            "soundClassificationConfidencePercent" -> entries += SettingEntry(
                soundClassificationConfidencePercentPref,
                stream.soundClassificationConfidencePercent,
            )
            "soundClassificationAllowedClasses" -> entries += SettingEntry(
                soundClassificationAllowedClassesPref,
                stream.soundClassificationAllowedClasses.toSet(),
            )
            // The trigger set normalizes on save exactly like the allow-list:
            // unknown spellings drop out, an empty set folds back to the
            // curated trigger default — the list narrows, never disarms.
            "soundTriggerEnabled" -> entries += SettingEntry(soundTriggerEnabledPref, stream.soundTriggerEnabled)
            "soundTriggerClasses" -> entries += SettingEntry(
                soundTriggerClassesPref,
                stream.soundTriggerClasses.toSet(),
            )
            "webhookEnabled" -> entries += SettingEntry(webhookEnabledPref, stream.webhookEnabled)
            "webhookUrl" -> entries += SettingEntry(webhookUrlPref, stream.webhookUrl)
            "webhookHeaders" -> entries += SettingEntry(webhookHeadersPref, stream.webhookHeaders)
            "autoSiren" -> entries += SettingEntry(autoSirenPref, stream.autoSiren)
            "autoTorch" -> entries += SettingEntry(autoTorchPref, stream.autoTorch)
            "sirenDurationSeconds" -> entries += SettingEntry(sirenDurationSecondsPref, stream.sirenDurationSeconds)
            "autoDeterrenceCooldownSeconds" -> entries += SettingEntry(
                autoDeterrenceCooldownSecondsPref,
                stream.autoDeterrenceCooldownSeconds,
            )
            "backupEnabled" -> entries += SettingEntry(backupEnabledPref, stream.backupEnabled)
            "backupWifiOnly" -> entries += SettingEntry(backupWifiOnlyPref, stream.backupWifiOnly)
            "backupTarget" -> entries += SettingEntry(
                backupTargetPref,
                BackupTargetPolicy.parse(stream.backupTarget).wireName,
            )
            "backupWebdavUrl" -> entries += SettingEntry(backupWebdavUrlPref, stream.backupWebdavUrl)
            "backupWebdavUsername" -> entries += SettingEntry(backupWebdavUsernamePref, stream.backupWebdavUsername)
            "backupWebdavPassword" -> if (SecretUpdate.overwrites(stream.backupWebdavPassword.value)) {
                entries += SettingEntry(backupWebdavPasswordPref, stream.backupWebdavPassword.value)
            }
            "telegramChatId" -> entries += SettingEntry(telegramChatIdPref, stream.telegramChatId)
            "telegramBotToken" -> if (SecretUpdate.overwrites(stream.telegramBotToken.value)) {
                entries += SettingEntry(telegramBotTokenPref, stream.telegramBotToken.value)
            }
            "httpsEnabled" -> entries += SettingEntry(httpsEnabledPref, stream.httpsEnabled)
            "audioDeviceId" -> entries += SettingEntry(audioDeviceIdPref, stream.audioDeviceId)
            "detectionNotificationsEnabled" -> entries += SettingEntry(
                detectionNotificationsEnabledPref,
                stream.detectionNotificationsEnabled,
            )
            "alertQuietHoursEnabled" -> entries += SettingEntry(alertQuietHoursEnabledPref, stream.alertQuietHoursEnabled)
            "alertQuietHoursStartMinute" -> entries += SettingEntry(
                alertQuietHoursStartMinutePref,
                stream.alertQuietHoursStartMinute,
            )
            "alertQuietHoursEndMinute" -> entries += SettingEntry(
                alertQuietHoursEndMinutePref,
                stream.alertQuietHoursEndMinute,
            )
            "keepAwakeEnabled" -> entries += SettingEntry(keepAwakeEnabledPref, stream.keepAwakeEnabled)
            "tamperDetectionEnabled" -> entries += SettingEntry(tamperDetectionEnabledPref, stream.tamperDetectionEnabled)
            "mqttEnabled" -> entries += SettingEntry(mqttEnabledPref, stream.mqttEnabled)
            "mqttBrokerHost" -> entries += SettingEntry(mqttBrokerHostPref, stream.mqttBrokerHost)
            "mqttBrokerPort" -> entries += SettingEntry(mqttBrokerPortPref, stream.mqttBrokerPort)
            "mqttUsername" -> entries += SettingEntry(mqttUsernamePref, stream.mqttUsername)
            "mqttPassword" -> if (SecretUpdate.overwrites(stream.mqttPassword.value)) {
                entries += SettingEntry(mqttPasswordPref, stream.mqttPassword.value)
            }
            "mqttTls" -> entries += SettingEntry(mqttTlsPref, stream.mqttTls)
            "mqttDiscoveryPrefix" -> entries += SettingEntry(mqttDiscoveryPrefixPref, stream.mqttDiscoveryPrefix)
            "mqttTelemetryEnabled" -> entries += SettingEntry(mqttTelemetryEnabledPref, stream.mqttTelemetryEnabled)
            // Web Push: the master gate rides the standard round-trip; the
            // subscriptions themselves are browser-session state through the
            // /api/push routes, never this document.
            "pushEnabled" -> entries += SettingEntry(pushEnabledPref, stream.pushEnabled)
            // UnifiedPush is an independent gate; its targets live in the
            // push store behind /api/push/unifiedpush, never this document.
            "unifiedPushEnabled" -> entries += SettingEntry(unifiedPushEnabledPref, stream.unifiedPushEnabled)
            // Retention windows clamp on save through their descriptors
            // (0 = keep forever), like every other bounded numeric setting.
            "captureRetentionDays" -> entries += SettingEntry(captureRetentionDaysPref, stream.captureRetentionDays)
            "eventRetentionDays" -> entries += SettingEntry(eventRetentionDaysPref, stream.eventRetentionDays)
            // The storage quota clamps to its 100 MB–32 GB descriptor bounds.
            "storageQuotaMb" -> entries += SettingEntry(storageQuotaMbPref, stream.storageQuotaMb)
            // Opt-in, migration-free: existing media is never re-encrypted or
            // decrypted — the resolver sniffs each file's header per read.
            "mediaEncryptionEnabled" -> entries += SettingEntry(mediaEncryptionEnabledPref, stream.mediaEncryptionEnabled)
            "mlDetectionEnabled" -> entries += SettingEntry(mlDetectionEnabledPref, stream.mlDetectionEnabled)
            "mlMinScorePercent" -> entries += SettingEntry(mlMinScorePercentPref, stream.mlMinScorePercent)
            "mlIncludePerson" -> entries += SettingEntry(mlIncludePersonPref, stream.mlIncludePerson)
            "mlIncludePets" -> entries += SettingEntry(mlIncludePetsPref, stream.mlIncludePets)
            "mlIncludeVehicles" -> entries += SettingEntry(mlIncludeVehiclesPref, stream.mlIncludeVehicles)
            "continuousRecording" -> entries += SettingEntry(continuousRecordingPref, stream.continuousRecording)
            "continuousSegmentMinutes" -> entries += SettingEntry(continuousSegmentMinutesPref, stream.continuousSegmentMinutes)
            "onvifEnabled" -> entries += SettingEntry(onvifEnabledPref, stream.onvifEnabled)
            "ecoIdleFpsEnabled" -> entries += SettingEntry(ecoIdleFpsEnabledPref, stream.ecoIdleFpsEnabled)
            // The apiToken is the ONE exception layered on the same entry
            // point: the stored form is the SHA-256 hex hash of the
            // plaintext, and its enable flag is hash-and-gated — the flag
            // only turns on when a usable credential would exist after the
            // update (a presented token, or a stored hash). An enable with no
            // plaintext and no stored hash would arm the token path without a
            // credential, so every presented token header would 401 (the gate
            // fails closed on a blank hash). Disabling always goes through.
            "apiTokenEnabled" -> entries += SettingEntry(
                apiTokenEnabledPref,
                stream.apiTokenEnabled && SecretUpdate.willHaveSecret(
                    stream.apiToken.value,
                    settingsDataStore.apiTokenHash.value,
                ),
            )
            // A non-empty token is the write-only plaintext: hash it here,
            // store only the hash, and never hand either back over the wire
            // (the Secret adapter blanks the response). An empty value keeps
            // the stored hash (disable via apiTokenEnabled).
            "apiToken" -> if (SecretUpdate.overwrites(stream.apiToken.value)) {
                entries += SettingEntry(
                    apiTokenHashPref,
                    StreamAuthCrypto.sha256Hex(stream.apiToken.value),
                )
            }
        }
        return entries
    }

    /**
     * Section-level merge for the settings PUT: the incoming section's raw
     * JSON keys overlay the stored section's value tree, so omitted keys
     * keep their stored values while sent keys (including explicit nulls)
     * replace them. Both sides run through the section's own Moshi adapter,
     * so the key set can never drift from the DTO shape — no hand-written
     * presence list to maintain.
     */
    private fun <T : Any> mergeSection(adapter: JsonAdapter<T>, stored: T, incoming: Any?): T {
        if (incoming !is Map<*, *>) return stored
        val merged = adapter.toJsonValue(stored) as MutableMap<Any?, Any?>
        for ((key, value) in incoming) merged[key] = value
        return adapter.fromJsonValue(merged) ?: stored
    }

    /**
     * POST /api/settings/ml-model/download — asks the model store to fetch
     * the detection model. Idempotent in the store (Ready and in-flight
     * downloads are no-ops); the outcome surfaces on the next settings GET
     * through `mlModelState`, never as this route's error.
     */
    suspend fun downloadModel(): String {
        detectionModelStore.requestDownload()
        return successAdapter.toJson(SuccessResponse())
    }

    /**
     * POST /api/settings/audio-model/download — the audio twin of
     * [downloadModel]: asks the YAMNet store to fetch the sound-classification
     * model, idempotent there, outcome never this route's error (the in-app
     * detection section's model row is the state surface).
     */
    suspend fun downloadAudioModel(): String {
        audioModelStore.requestDownload()
        return successAdapter.toJson(SuccessResponse())
    }

    private fun toCameraDto(settings: CameraSettings) = CameraSettingsDto(
        exposureCompensation = settings.exposureCompensation,
        iso = settings.iso,
        exposureTime = settings.exposureTime,
        focusMode = settings.focusMode.name,
        focusDistance = settings.focusDistance,
        whiteBalance = settings.whiteBalance.name,
        colorTemperature = settings.colorTemperature,
        zoomRatio = settings.zoomRatio.toDouble(),
        frameRate = settings.frameRate,
        resolution = settings.resolution.name,
        stabilization = settings.stabilization,
        hdrMode = settings.hdrMode.name,
        sceneMode = settings.sceneMode,
        nightVisionMode = settings.nightVisionMode.name,
        outputRotation = settings.outputRotation,
        orientationLocked = settings.orientationLocked,
    )

    private fun toZoneDto(zone: MaskingZone) = MaskingZoneDto(
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

    private fun toOverlaySettings(stream: StreamingSettingsDto, current: OverlaySettings): OverlaySettings {
        // Pure DTO mapping only — numeric clamping is the Settings Store's
        // (OverlaySettings.normalized runs on save).
        return OverlaySettings(
            enabled = stream.overlayEnabled,
            showTimestamp = stream.showTimestamp,
            timestampFormat = stream.timestampFormat.takeIf { it.isNotBlank() } ?: current.timestampFormat,
            showBranding = stream.showBranding,
            brandingText = stream.brandingText,
            showStatus = stream.showStatus,
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
}

/**
 * The pure import-body classifier behind POST /api/settings/import: an export
 * envelope (validated against its schema version) or a bare settings update
 * document — anything else is an [IllegalArgumentException] the router turns
 * into the standard error payload. JVM-tested; no Android types.
 */
object SettingsImportParser {

    sealed interface Parsed {
        data class Envelope(val export: SettingsExportDto) : Parsed
        data class RawUpdate(val update: SettingsUpdateRequestDto) : Parsed
    }

    private val envelopeAdapter by lazy { AppJson.moshi.adapter(SettingsExportDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java) }

    fun parse(body: String): Parsed {
        // The envelope path first: an envelope always carries the version and
        // app fields, so its presence (or a present-but-null settings doc,
        // which the caller rejects) decides here.
        val envelope = runCatching { envelopeAdapter.fromJson(body) }.getOrNull()
        if (envelope != null && (envelope.settings != null || envelope.exportedAtMs != 0L || envelope.app != SettingsExportDto.APP_IDENTITY)) {
            if (envelope.schemaVersion != SettingsExportDto.SETTINGS_SCHEMA_VERSION) {
                throw IllegalArgumentException(
                    "Unsupported settings schema version: ${envelope.schemaVersion} " +
                        "(supported: ${SettingsExportDto.SETTINGS_SCHEMA_VERSION})",
                )
            }
            return Parsed.Envelope(envelope)
        }
        // A bare settings document, or an envelope so bare it is
        // indistinguishable from one (both fall through to the update parse).
        // An update carrying no section at all is a no-op import, rejected —
        // a "success" that changed nothing would be a lie.
        val update = runCatching { updateAdapter.fromJson(body) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid settings JSON")
        if (update.camera == null && update.streaming == null) {
            throw IllegalArgumentException("No settings to import")
        }
        return Parsed.RawUpdate(update)
    }
}
