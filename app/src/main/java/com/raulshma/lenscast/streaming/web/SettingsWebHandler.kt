package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.model.CameraSettingsDto
import com.raulshma.lenscast.streaming.model.MaskingZoneDto
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

    private val responseAdapter by lazy { WebJson.moshi.adapter(SettingsResponseDto::class.java) }
    private val updateAdapter by lazy { WebJson.moshi.adapter(SettingsUpdateRequestDto::class.java) }
    private val successAdapter by lazy { WebJson.moshi.adapter(SuccessResponse::class.java) }

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
                focusMode = runCatching { FocusMode.valueOf(cam.focusMode) }.getOrDefault(current.focusMode),
                focusDistance = cam.focusDistance?.coerceIn(0f, CameraSettings.FOCUS_DISTANCE_MAX),
                whiteBalance = runCatching { WhiteBalance.valueOf(cam.whiteBalance) }.getOrDefault(current.whiteBalance),
                colorTemperature = cam.colorTemperature?.coerceIn(
                    CameraSettings.COLOR_TEMPERATURE_MIN,
                    CameraSettings.COLOR_TEMPERATURE_MAX,
                ),
                zoomRatio = cam.zoomRatio.toFloat().coerceIn(CameraSettings.ZOOM_RATIO_MIN, CameraSettings.ZOOM_RATIO_MAX),
                frameRate = cam.frameRate.coerceIn(CameraSettings.FRAME_RATE_MIN, CameraSettings.FRAME_RATE_MAX),
                resolution = runCatching { Resolution.valueOf(cam.resolution) }.getOrDefault(current.resolution),
                stabilization = cam.stabilization,
                hdrMode = runCatching { HdrMode.valueOf(cam.hdrMode) }.getOrDefault(current.hdrMode),
                sceneMode = cam.sceneMode,
                nightVisionMode = runCatching { NightVisionMode.valueOf(cam.nightVisionMode) }.getOrDefault(current.nightVisionMode),
            )
            settingsDataStore.saveSettings(newSettings)
        }

        request.streaming?.let { stream ->
            if (stream.port in 1024..65535) {
                settingsDataStore.saveStreamingPort(stream.port)
            }
            settingsDataStore.saveWebStreamingEnabled(stream.webStreamingEnabled)
            if (stream.jpegQuality > 0) {
                settingsDataStore.saveJpegQuality(stream.jpegQuality)
            }
            settingsDataStore.saveShowPreview(stream.showPreview)
            settingsDataStore.saveStreamAudioEnabled(stream.streamAudioEnabled)
            if (stream.streamAudioBitrateKbps > 0) {
                settingsDataStore.saveStreamAudioBitrateKbps(stream.streamAudioBitrateKbps)
            }
            if (stream.streamAudioChannels > 0) {
                settingsDataStore.saveStreamAudioChannels(stream.streamAudioChannels)
            }
            settingsDataStore.saveStreamAudioEchoCancellation(stream.streamAudioEchoCancellation)
            settingsDataStore.saveRecordingAudioEnabled(stream.recordingAudioEnabled)
            settingsDataStore.saveRtspEnabled(stream.rtspEnabled)
            if (stream.rtspPort in 1024..65535) {
                settingsDataStore.saveRtspPort(stream.rtspPort)
            }
            if (stream.rtspInputFormat.isNotBlank()) {
                runCatching { RtspInputFormat.valueOf(stream.rtspInputFormat) }.getOrNull()
                    ?.let { settingsDataStore.saveRtspInputFormat(it) }
            }
            settingsDataStore.saveAdaptiveBitrateEnabled(stream.adaptiveBitrateEnabled)
            settingsDataStore.saveOverlaySettings(toOverlaySettings(stream, settingsDataStore.overlaySettings.value))
            settingsDataStore.saveWatchdogEnabled(stream.watchdogEnabled)
            settingsDataStore.saveWatchdogMaxRetries(stream.watchdogMaxRetries)
            settingsDataStore.saveWatchdogCheckIntervalSeconds(stream.watchdogCheckIntervalSeconds)
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

    private fun toOverlaySettings(stream: StreamingSettingsDto, current: OverlaySettings): OverlaySettings {
        return OverlaySettings(
            enabled = stream.overlayEnabled,
            showTimestamp = stream.showTimestamp,
            timestampFormat = stream.timestampFormat.takeIf { it.isNotBlank() } ?: current.timestampFormat,
            showBranding = stream.showBranding,
            brandingText = stream.brandingText,
            showStatus = stream.showStatus,
            showCustomText = stream.showCustomText,
            customText = stream.customText,
            position = runCatching { OverlayPosition.valueOf(stream.overlayPosition) }.getOrDefault(current.position),
            fontSize = stream.overlayFontSize.coerceIn(8, 120),
            textColor = stream.overlayTextColor.takeIf { it.isNotBlank() } ?: current.textColor,
            backgroundColor = stream.overlayBackgroundColor.takeIf { it.isNotBlank() } ?: current.backgroundColor,
            padding = stream.overlayPadding.coerceIn(0, 48),
            lineHeight = stream.overlayLineHeight.coerceIn(0, 32),
            maskingEnabled = stream.maskingEnabled,
            maskingZones = stream.maskingZones.map { dto ->
                MaskingZone(
                    id = dto.id.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                    label = dto.label,
                    enabled = dto.enabled,
                    type = runCatching { MaskingType.valueOf(dto.type) }.getOrDefault(MaskingType.BLACKOUT),
                    x = dto.x.toFloat().coerceIn(0f, 1f),
                    y = dto.y.toFloat().coerceIn(0f, 1f),
                    width = dto.width.toFloat().coerceIn(0.01f, 1f),
                    height = dto.height.toFloat().coerceIn(0.01f, 1f),
                    pixelateSize = dto.pixelateSize.coerceIn(4, 64),
                    blurRadius = dto.blurRadius.toFloat().coerceIn(1f, 50f),
                )
            },
        )
    }
}
