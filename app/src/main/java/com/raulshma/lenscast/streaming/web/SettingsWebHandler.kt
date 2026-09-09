package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.BackupTargetPolicy
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.core.parseEnumOrNull
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.model.CameraSettingsDto
import com.raulshma.lenscast.streaming.model.MaskingZoneDto
import com.raulshma.lenscast.streaming.model.MotionZoneDto
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import com.raulshma.lenscast.streaming.model.SettingsUpdateRequestDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat

/**
 * /api/settings — JSON-in/JSON-out mapping between the Web API DTOs and the
 * Settings Store. This handler only *writes* settings; the Settings Applier
 * applies them to the runtime.
 */
class SettingsWebHandler(private val settingsDataStore: SettingsDataStore) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(SettingsResponseDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    suspend fun get(): String {
        val store = settingsDataStore
        val overlay = store.overlaySettings.value
        val response = SettingsResponseDto(
            camera = toCameraDto(store.settings.value),
            streaming = StreamingSettingsDto(
                port = store.streamingPort.value,
                webStreamingEnabled = store.webStreamingEnabled.value,
                jpegQuality = store.jpegQuality.value,
                showPreview = store.showPreview.value,
                streamAudioEnabled = store.streamAudioEnabled.value,
                streamAudioBitrateKbps = store.streamAudioBitrateKbps.value,
                streamAudioChannels = store.streamAudioChannels.value,
                streamAudioEchoCancellation = store.streamAudioEchoCancellation.value,
                recordingAudioEnabled = store.recordingAudioEnabled.value,
                rtspEnabled = store.rtspEnabled.value,
                rtspPort = store.rtspPort.value,
                rtspInputFormat = store.rtspInputFormat.value.name,
                adaptiveBitrateEnabled = store.adaptiveBitrateEnabled.value,
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
                watchdogEnabled = store.watchdogEnabled.value,
                watchdogMaxRetries = store.watchdogMaxRetries.value,
                watchdogCheckIntervalSeconds = store.watchdogCheckIntervalSeconds.value,
                mdnsEnabled = store.mdnsEnabled.value,
                motionDetectionEnabled = store.motionDetectionEnabled.value,
                motionSensitivityPercent = store.motionSensitivity.value,
                motionZones = store.motionZones.value.map(::toMotionZoneDto),
                motionRecordingEnabled = store.motionRecordingEnabled.value,
                motionPostRollSeconds = store.motionPostRollSeconds.value,
                motionArmScheduleEnabled = store.motionArmScheduleEnabled.value,
                motionArmStartMinute = store.motionArmStartMinute.value,
                motionArmEndMinute = store.motionArmEndMinute.value,
                soundDetectionEnabled = store.soundDetectionEnabled.value,
                soundThresholdPercent = store.soundThresholdPercent.value,
                webhookEnabled = store.webhookEnabled.value,
                webhookUrl = store.webhookUrl.value,
                webhookHeaders = store.webhookHeaders.value,
                autoSiren = store.autoSiren.value,
                autoTorch = store.autoTorch.value,
                sirenDurationSeconds = store.sirenDurationSeconds.value,
                autoDeterrenceCooldownSeconds = store.autoDeterrenceCooldownSeconds.value,
                backupEnabled = store.backupEnabled.value,
                backupWifiOnly = store.backupWifiOnly.value,
                backupTarget = BackupTargetPolicy.parse(store.backupTarget.value).wireName,
                backupWebdavUrl = store.backupWebdavUrl.value,
                backupWebdavUsername = store.backupWebdavUsername.value,
                // Write-only, like the stream-auth password: blank in every
                // response; an empty update keeps the stored secret.
                backupWebdavPassword = "",
                telegramChatId = store.telegramChatId.value,
                // Write-only, exactly like backupWebdavPassword.
                telegramBotToken = "",
                apiTokenEnabled = store.apiTokenEnabled.value,
                apiTokenConfigured = store.apiTokenHash.value.isNotBlank(),
                // Write-only plaintext: the stored value is the SHA-256 hex
                // hash, and neither it nor the token ever round-trips.
                apiToken = "",
                httpsEnabled = store.httpsEnabled.value,
                audioDeviceId = store.audioDeviceId.value,
                detectionNotificationsEnabled = store.detectionNotificationsEnabled.value,
                tamperDetectionEnabled = store.tamperDetectionEnabled.value,
                mqttEnabled = store.mqttEnabled.value,
                mqttBrokerHost = store.mqttBrokerHost.value,
                mqttBrokerPort = store.mqttBrokerPort.value,
                mqttUsername = store.mqttUsername.value,
                // Write-only, like backupWebdavPassword: blank in every response.
                mqttPassword = "",
                mqttTls = store.mqttTls.value,
                mqttDiscoveryPrefix = store.mqttDiscoveryPrefix.value,
            ),
        )
        return responseAdapter.toJson(response)
    }

    suspend fun put(body: String): String {
        val request = updateAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid settings JSON")

        request.camera?.let { cam ->
            val current = settingsDataStore.settings.value
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
            )
            settingsDataStore.saveSettings(newSettings)
        }

        request.streaming?.let { stream ->
            // Out-of-range numeric values are the Settings Store's clamp
            // policy, not this handler's: every saver coerces on save.
            settingsDataStore.saveStreamingPort(stream.port)
            settingsDataStore.saveWebStreamingEnabled(stream.webStreamingEnabled)
            settingsDataStore.saveJpegQuality(stream.jpegQuality)
            settingsDataStore.saveShowPreview(stream.showPreview)
            settingsDataStore.saveStreamAudioEnabled(stream.streamAudioEnabled)
            settingsDataStore.saveStreamAudioBitrateKbps(stream.streamAudioBitrateKbps)
            settingsDataStore.saveStreamAudioChannels(stream.streamAudioChannels)
            settingsDataStore.saveStreamAudioEchoCancellation(stream.streamAudioEchoCancellation)
            settingsDataStore.saveRecordingAudioEnabled(stream.recordingAudioEnabled)
            settingsDataStore.saveRtspEnabled(stream.rtspEnabled)
            settingsDataStore.saveRtspPort(stream.rtspPort)
            parseEnumOrNull<RtspInputFormat>(stream.rtspInputFormat.takeIf { it.isNotBlank() })
                ?.let { settingsDataStore.saveRtspInputFormat(it) }
            settingsDataStore.saveAdaptiveBitrateEnabled(stream.adaptiveBitrateEnabled)
            settingsDataStore.saveOverlaySettings(toOverlaySettings(stream, settingsDataStore.overlaySettings.value))
            settingsDataStore.saveWatchdogEnabled(stream.watchdogEnabled)
            settingsDataStore.saveWatchdogMaxRetries(stream.watchdogMaxRetries)
            settingsDataStore.saveWatchdogCheckIntervalSeconds(stream.watchdogCheckIntervalSeconds)
            settingsDataStore.saveMdnsEnabled(stream.mdnsEnabled)
            settingsDataStore.saveMotionDetectionEnabled(stream.motionDetectionEnabled)
            settingsDataStore.saveMotionSensitivity(stream.motionSensitivityPercent)
            settingsDataStore.saveMotionZones(stream.motionZones.map { toMotionZone(it) })
            settingsDataStore.saveMotionRecordingEnabled(stream.motionRecordingEnabled)
            settingsDataStore.saveMotionPostRollSeconds(stream.motionPostRollSeconds)
            settingsDataStore.saveMotionArmScheduleEnabled(stream.motionArmScheduleEnabled)
            settingsDataStore.saveMotionArmStartMinute(stream.motionArmStartMinute)
            settingsDataStore.saveMotionArmEndMinute(stream.motionArmEndMinute)
            settingsDataStore.saveSoundDetectionEnabled(stream.soundDetectionEnabled)
            settingsDataStore.saveSoundThresholdPercent(stream.soundThresholdPercent)
            settingsDataStore.saveWebhookEnabled(stream.webhookEnabled)
            settingsDataStore.saveWebhookUrl(stream.webhookUrl)
            settingsDataStore.saveWebhookHeaders(stream.webhookHeaders)
            settingsDataStore.saveAutoSiren(stream.autoSiren)
            settingsDataStore.saveAutoTorch(stream.autoTorch)
            settingsDataStore.saveSirenDurationSeconds(stream.sirenDurationSeconds)
            settingsDataStore.saveAutoDeterrenceCooldownSeconds(stream.autoDeterrenceCooldownSeconds)
            settingsDataStore.saveBackupEnabled(stream.backupEnabled)
            settingsDataStore.saveBackupWifiOnly(stream.backupWifiOnly)
            settingsDataStore.saveBackupTarget(BackupTargetPolicy.parse(stream.backupTarget).wireName)
            settingsDataStore.saveBackupWebdavUrl(stream.backupWebdavUrl)
            settingsDataStore.saveBackupWebdavUsername(stream.backupWebdavUsername)
            settingsDataStore.saveTelegramChatId(stream.telegramChatId)
            settingsDataStore.saveHttpsEnabled(stream.httpsEnabled)
            settingsDataStore.saveAudioDeviceId(stream.audioDeviceId)
            settingsDataStore.saveDetectionNotificationsEnabled(stream.detectionNotificationsEnabled)
            settingsDataStore.saveTamperDetectionEnabled(stream.tamperDetectionEnabled)
            settingsDataStore.saveMqttEnabled(stream.mqttEnabled)
            settingsDataStore.saveMqttBrokerHost(stream.mqttBrokerHost)
            settingsDataStore.saveMqttBrokerPort(stream.mqttBrokerPort)
            settingsDataStore.saveMqttUsername(stream.mqttUsername)
            settingsDataStore.saveMqttTls(stream.mqttTls)
            settingsDataStore.saveMqttDiscoveryPrefix(stream.mqttDiscoveryPrefix)
            // Same write-only contract as the WebDAV password: an empty value
            // keeps the stored credential.
            if (stream.mqttPassword.isNotEmpty()) {
                settingsDataStore.saveMqttPassword(stream.mqttPassword)
            }
            // An empty password on update means "keep the stored one" so the
            // dashboard never needs to round-trip the secret.
            if (stream.backupWebdavPassword.isNotEmpty()) {
                settingsDataStore.saveBackupWebdavPassword(stream.backupWebdavPassword)
            }
            // Same write-only contract as the WebDAV password.
            if (stream.telegramBotToken.isNotEmpty()) {
                settingsDataStore.saveTelegramBotToken(stream.telegramBotToken)
            }
            // The token flag only turns on with a token in hand: an enable
            // with no plaintext and no stored hash would arm the token path
            // without a credential, so every presented token header would
            // 401 (the gate fails closed on a blank hash). Disabling always
            // goes through.
            val tokenConfigured = stream.apiToken.isNotEmpty() ||
                settingsDataStore.apiTokenHash.value.isNotBlank()
            settingsDataStore.saveApiTokenEnabled(stream.apiTokenEnabled && tokenConfigured)
            // A non-empty apiToken is the write-only plaintext: hash it here,
            // store only the hash, and never hand either back over the wire.
            // An empty value keeps the stored hash (disable via apiTokenEnabled).
            if (stream.apiToken.isNotEmpty()) {
                settingsDataStore.saveApiTokenHash(StreamAuthCrypto.sha256Hex(stream.apiToken))
            }
        }

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
