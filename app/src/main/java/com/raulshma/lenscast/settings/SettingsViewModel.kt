package com.raulshma.lenscast.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.PhotoAspectRatio
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.flow.StateFlow

/**
 * The settings screens' write-side ViewModel — now a thin shell over the
 * [SettingsSource] seam (P6's one-interface-two-backends wave): every flow
 * read and every write rides the source, so the same screens render against
 * this phone's store ([LocalSettingsSource]) or a paired device's settings
 * document ([com.raulshma.lenscast.companion.console.RemoteSettingsSource])
 * with no screen-side branching. The source carries the lane partition
 * KDoc's contract (device-side vs viewer-local vs phone-only); this class
 * adds nothing but the [Factory] and the ViewModel lifecycle.
 */
class SettingsViewModel(
    /** The backend: this phone's store passthrough or the paired device's. */
    private val source: SettingsSource,
) : ViewModel() {

    /** The backend's render verdict table (the screens' capability gates). */
    val capabilities: StateFlow<SettingsCapabilities> = source.capabilities

    // ── Camera section ──
    val settings: StateFlow<CameraSettings> = source.settings
    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = source.availableZoomRange
    val availableExposureRange: StateFlow<ClosedRange<Int>> = source.availableExposureRange
    val availableIsoRange: StateFlow<ClosedRange<Int>> = source.availableIsoRange
    val isRawCaptureSupported: StateFlow<Boolean> = source.isRawCaptureSupported

    // ── Streaming / app settings ──
    val streamingPort: StateFlow<Int> = source.streamingPort
    val webStreamingEnabled: StateFlow<Boolean> = source.webStreamingEnabled
    val jpegQuality: StateFlow<Int> = source.jpegQuality
    val adaptiveBitrateEnabled: StateFlow<Boolean> = source.adaptiveBitrateEnabled
    val adaptiveEncodedBitrateEnabled: StateFlow<Boolean> = source.adaptiveEncodedBitrateEnabled
    val hlsDvrSegments: StateFlow<Int> = source.hlsDvrSegments
    val ecoIdleFpsEnabled: StateFlow<Boolean> = source.ecoIdleFpsEnabled
    val mdnsEnabled: StateFlow<Boolean> = source.mdnsEnabled

    // ── Camera pro mode (viewer-local lanes) ──
    val showPreview: StateFlow<Boolean> = source.showPreview
    val dynamicThemingEnabled: StateFlow<Boolean> = source.dynamicThemingEnabled
    val gridStyle: StateFlow<GridStyle> = source.gridStyle
    val selfTimer: StateFlow<SelfTimerMode> = source.selfTimer
    val spiritLevelEnabled: StateFlow<Boolean> = source.spiritLevelEnabled
    val histogramEnabled: StateFlow<Boolean> = source.histogramEnabled
    val zebrasEnabled: StateFlow<Boolean> = source.zebrasEnabled
    val peakingEnabled: StateFlow<Boolean> = source.peakingEnabled

    // ── Photo capture knobs ──
    val photoJpegQuality: StateFlow<Int> = source.photoJpegQuality
    val photoMaximizeQuality: StateFlow<Boolean> = source.photoMaximizeQuality
    val rawCaptureEnabled: StateFlow<Boolean> = source.rawCaptureEnabled
    val photoAspectRatio: StateFlow<PhotoAspectRatio> = source.photoAspectRatio
    val geotagEnabled: StateFlow<Boolean> = source.geotagEnabled

    // ── Audio ──
    val streamAudioEnabled: StateFlow<Boolean> = source.streamAudioEnabled
    val streamAudioBitrateKbps: StateFlow<Int> = source.streamAudioBitrateKbps
    val streamAudioChannels: StateFlow<Int> = source.streamAudioChannels
    val streamAudioEchoCancellation: StateFlow<Boolean> = source.streamAudioEchoCancellation
    val recordingAudioEnabled: StateFlow<Boolean> = source.recordingAudioEnabled
    val audioDeviceId: StateFlow<String> = source.audioDeviceId

    // ── Detection ──
    val motionDetectionEnabled: StateFlow<Boolean> = source.motionDetectionEnabled
    val motionSensitivityPercent: StateFlow<Int> = source.motionSensitivityPercent
    val motionRecordingEnabled: StateFlow<Boolean> = source.motionRecordingEnabled
    val motionPostRollSeconds: StateFlow<Int> = source.motionPostRollSeconds
    val motionArmScheduleEnabled: StateFlow<Boolean> = source.motionArmScheduleEnabled
    val motionArmStartMinute: StateFlow<Int> = source.motionArmStartMinute
    val motionArmEndMinute: StateFlow<Int> = source.motionArmEndMinute
    val motionArmDaysMask: StateFlow<Int> = source.motionArmDaysMask
    val motionCooldownSeconds: StateFlow<Int> = source.motionCooldownSeconds
    val soundDetectionEnabled: StateFlow<Boolean> = source.soundDetectionEnabled
    val soundThresholdPercent: StateFlow<Int> = source.soundThresholdPercent
    val soundAdaptiveNoiseFloor: StateFlow<Boolean> = source.soundAdaptiveNoiseFloor
    val soundRecordingEnabled: StateFlow<Boolean> = source.soundRecordingEnabled
    val soundCooldownSeconds: StateFlow<Int> = source.soundCooldownSeconds
    val soundClassificationEnabled: StateFlow<Boolean> = source.soundClassificationEnabled
    val soundClassificationConfidencePercent: StateFlow<Int> =
        source.soundClassificationConfidencePercent
    val soundClassificationAllowedClasses: StateFlow<Set<String>> =
        source.soundClassificationAllowedClasses
    val soundTriggerEnabled: StateFlow<Boolean> = source.soundTriggerEnabled
    val soundTriggerClasses: StateFlow<Set<String>> = source.soundTriggerClasses
    val mlDetectionEnabled: StateFlow<Boolean> = source.mlDetectionEnabled
    val mlMinScorePercent: StateFlow<Int> = source.mlMinScorePercent
    val mlIncludePerson: StateFlow<Boolean> = source.mlIncludePerson
    val mlIncludePets: StateFlow<Boolean> = source.mlIncludePets
    val mlIncludeVehicles: StateFlow<Boolean> = source.mlIncludeVehicles
    val tamperDetectionEnabled: StateFlow<Boolean> = source.tamperDetectionEnabled
    val detectionNotificationsEnabled: StateFlow<Boolean> = source.detectionNotificationsEnabled
    val alertQuietHoursEnabled: StateFlow<Boolean> = source.alertQuietHoursEnabled
    val alertQuietHoursStartMinute: StateFlow<Int> = source.alertQuietHoursStartMinute
    val alertQuietHoursEndMinute: StateFlow<Int> = source.alertQuietHoursEndMinute
    val pushEnabled: StateFlow<Boolean> = source.pushEnabled
    val unifiedPushEnabled: StateFlow<Boolean> = source.unifiedPushEnabled
    val pushVapidSubject: StateFlow<String> = source.pushVapidSubject
    val detectionModelState: StateFlow<DetectionModelStore.State> = source.detectionModelState
    val audioModelState: StateFlow<AudioModelStore.State?> = source.audioModelState

    // ── Storage / recording / reliability / integrations ──
    val storageQuotaMb: StateFlow<Int> = source.storageQuotaMb
    val mediaEncryptionEnabled: StateFlow<Boolean> = source.mediaEncryptionEnabled
    val continuousRecording: StateFlow<Boolean> = source.continuousRecording
    val continuousSegmentMinutes: StateFlow<Int> = source.continuousSegmentMinutes
    val watchdogEnabled: StateFlow<Boolean> = source.watchdogEnabled
    val watchdogMaxRetries: StateFlow<Int> = source.watchdogMaxRetries
    val watchdogCheckIntervalSeconds: StateFlow<Int> = source.watchdogCheckIntervalSeconds
    val backupEnabled: StateFlow<Boolean> = source.backupEnabled
    val backupWifiOnly: StateFlow<Boolean> = source.backupWifiOnly
    val backupWebdavUrl: StateFlow<String> = source.backupWebdavUrl
    val backupWebdavUsername: StateFlow<String> = source.backupWebdavUsername
    val httpsEnabled: StateFlow<Boolean> = source.httpsEnabled
    val keepAwakeEnabled: StateFlow<Boolean> = source.keepAwakeEnabled
    val resumeStreamsOnBoot: StateFlow<Boolean> = source.resumeStreamsOnBoot
    val fsiCameraResume: StateFlow<Boolean> = source.fsiCameraResume
    val authSettings: StateFlow<StreamAuthSettings> = source.authSettings
    val rtspEnabled: StateFlow<Boolean> = source.rtspEnabled
    val rtspPort: StateFlow<Int> = source.rtspPort
    val rtspBasicAuthCompat: StateFlow<Boolean> = source.rtspBasicAuthCompat
    val rtspInputFormat: StateFlow<RtspInputFormat> = source.rtspInputFormat
    val rtspSubStreamEnabled: StateFlow<Boolean> = source.rtspSubStreamEnabled
    val rtmpEnabled: StateFlow<Boolean> = source.rtmpEnabled
    val rtmpUrl: StateFlow<String> = source.rtmpUrl
    val whipEnabled: StateFlow<Boolean> = source.whipEnabled
    val whipUrl: StateFlow<String> = source.whipUrl
    val whipStunServer: StateFlow<String> = source.whipStunServer
    val srtEnabled: StateFlow<Boolean> = source.srtEnabled
    val srtUrl: StateFlow<String> = source.srtUrl

    // ── Phone-only ──
    val isIgnoringBatteryOptimizations = source.isIgnoringBatteryOptimizations

    fun refreshBatteryOptimizationStatus() = source.refreshBatteryOptimizationStatus()

    fun requestIgnoreBatteryOptimization(activity: Activity) =
        source.requestIgnoreBatteryOptimization(activity)

    // ── Writes (one-line delegates; the source owns the lane semantics) ──

    fun updateQuickSetting(type: QuickSettingType, value: Any) =
        source.updateQuickSetting(type, value)

    fun updateFocusDistance(distance: Float?) = source.updateFocusDistance(distance)

    fun updateColorTemperature(temp: Int?) = source.updateColorTemperature(temp)

    fun updateSceneMode(mode: String) = source.updateSceneMode(mode)

    fun resetToDefaults() = source.resetToDefaults()

    fun updateStreamingPort(port: Int) = source.updateStreamingPort(port)

    fun updateWebStreamingEnabled(enabled: Boolean) = source.updateWebStreamingEnabled(enabled)

    fun updateJpegQuality(quality: Int) = source.updateJpegQuality(quality)

    fun updateAdaptiveBitrateEnabled(enabled: Boolean) =
        source.updateAdaptiveBitrateEnabled(enabled)

    fun updateAdaptiveEncodedBitrateEnabled(enabled: Boolean) =
        source.updateAdaptiveEncodedBitrateEnabled(enabled)

    fun updateRtspSubStreamEnabled(enabled: Boolean) = source.updateRtspSubStreamEnabled(enabled)

    fun updateHlsDvrSegments(segments: Int) = source.updateHlsDvrSegments(segments)

    fun updateEcoIdleFpsEnabled(enabled: Boolean) = source.updateEcoIdleFpsEnabled(enabled)

    fun updateMdnsEnabled(enabled: Boolean) = source.updateMdnsEnabled(enabled)

    fun updateGridStyle(name: String) = source.updateGridStyle(name)

    fun updateSelfTimer(name: String) = source.updateSelfTimer(name)

    fun updateSpiritLevelEnabled(enabled: Boolean) = source.updateSpiritLevelEnabled(enabled)

    fun updateHistogramEnabled(enabled: Boolean) = source.updateHistogramEnabled(enabled)

    fun updateZebrasEnabled(enabled: Boolean) = source.updateZebrasEnabled(enabled)

    fun updatePeakingEnabled(enabled: Boolean) = source.updatePeakingEnabled(enabled)

    fun updatePhotoJpegQuality(quality: Int) = source.updatePhotoJpegQuality(quality)

    fun updatePhotoMaximizeQuality(enabled: Boolean) = source.updatePhotoMaximizeQuality(enabled)

    fun updateRawCaptureEnabled(enabled: Boolean) = source.updateRawCaptureEnabled(enabled)

    fun updatePhotoAspectRatio(aspect: PhotoAspectRatio) = source.updatePhotoAspectRatio(aspect)

    fun updateGeotagEnabled(enabled: Boolean) = source.updateGeotagEnabled(enabled)

    fun updateShowPreview(show: Boolean) = source.updateShowPreview(show)

    fun updateDynamicThemingEnabled(enabled: Boolean) = source.updateDynamicThemingEnabled(enabled)

    fun updateStreamAudioEnabled(enabled: Boolean) = source.updateStreamAudioEnabled(enabled)

    fun updateStreamAudioBitrateKbps(bitrateKbps: Int) =
        source.updateStreamAudioBitrateKbps(bitrateKbps)

    fun updateStreamAudioChannels(channels: Int) = source.updateStreamAudioChannels(channels)

    fun updateStreamAudioEchoCancellation(enabled: Boolean) =
        source.updateStreamAudioEchoCancellation(enabled)

    fun updateRecordingAudioEnabled(enabled: Boolean) = source.updateRecordingAudioEnabled(enabled)

    fun updateRtspEnabled(enabled: Boolean) = source.updateRtspEnabled(enabled)

    fun updateRtspPort(port: Int) = source.updateRtspPort(port)

    fun updateRtspBasicAuthCompat(enabled: Boolean) = source.updateRtspBasicAuthCompat(enabled)

    fun updateRtspInputFormat(name: String) = source.updateRtspInputFormat(name)

    fun updateRtmpEnabled(enabled: Boolean) = source.updateRtmpEnabled(enabled)

    fun updateRtmpUrl(url: String) = source.updateRtmpUrl(url)

    fun updateWhipEnabled(enabled: Boolean) = source.updateWhipEnabled(enabled)

    fun updateWhipUrl(url: String) = source.updateWhipUrl(url)

    fun updateWhipToken(token: String) = source.updateWhipToken(token)

    fun updateWhipStunServer(server: String) = source.updateWhipStunServer(server)

    fun updateSrtEnabled(enabled: Boolean) = source.updateSrtEnabled(enabled)

    fun updateSrtUrl(url: String) = source.updateSrtUrl(url)

    fun updateMotionDetectionEnabled(enabled: Boolean) = source.updateMotionDetectionEnabled(enabled)

    fun updateMotionSensitivity(percent: Int) = source.updateMotionSensitivity(percent)

    fun updateMotionRecordingEnabled(enabled: Boolean) =
        source.updateMotionRecordingEnabled(enabled)

    fun updateMotionPostRollSeconds(seconds: Int) = source.updateMotionPostRollSeconds(seconds)

    fun updateMotionArmScheduleEnabled(enabled: Boolean) =
        source.updateMotionArmScheduleEnabled(enabled)

    fun updateMotionArmStartMinute(minute: Int) = source.updateMotionArmStartMinute(minute)

    fun updateMotionArmEndMinute(minute: Int) = source.updateMotionArmEndMinute(minute)

    fun updateMotionArmDaysMask(mask: Int) = source.updateMotionArmDaysMask(mask)

    fun updateSoundDetectionEnabled(enabled: Boolean) = source.updateSoundDetectionEnabled(enabled)

    fun updateSoundThresholdPercent(percent: Int) = source.updateSoundThresholdPercent(percent)

    fun updateSoundAdaptiveNoiseFloor(enabled: Boolean) =
        source.updateSoundAdaptiveNoiseFloor(enabled)

    fun updateSoundRecordingEnabled(enabled: Boolean) = source.updateSoundRecordingEnabled(enabled)

    fun updateMotionCooldownSeconds(seconds: Int) = source.updateMotionCooldownSeconds(seconds)

    fun updateSoundCooldownSeconds(seconds: Int) = source.updateSoundCooldownSeconds(seconds)

    fun updateMlDetectionEnabled(enabled: Boolean) = source.updateMlDetectionEnabled(enabled)

    fun updateMlMinScorePercent(percent: Int) = source.updateMlMinScorePercent(percent)

    fun updateMlIncludePerson(enabled: Boolean) = source.updateMlIncludePerson(enabled)

    fun updateMlIncludePets(enabled: Boolean) = source.updateMlIncludePets(enabled)

    fun updateMlIncludeVehicles(enabled: Boolean) = source.updateMlIncludeVehicles(enabled)

    fun downloadDetectionModel() = source.downloadDetectionModel()

    fun updateSoundClassificationEnabled(enabled: Boolean) =
        source.updateSoundClassificationEnabled(enabled)

    fun updateSoundClassificationConfidencePercent(percent: Int) =
        source.updateSoundClassificationConfidencePercent(percent)

    fun updateSoundClassificationAllowedClasses(classes: Set<String>) =
        source.updateSoundClassificationAllowedClasses(classes)

    fun updateSoundTriggerEnabled(enabled: Boolean) = source.updateSoundTriggerEnabled(enabled)

    fun updateSoundTriggerClasses(classes: Set<String>) = source.updateSoundTriggerClasses(classes)

    fun downloadAudioModel() = source.downloadAudioModel()

    fun updateContinuousRecording(enabled: Boolean) = source.updateContinuousRecording(enabled)

    fun updateContinuousSegmentMinutes(minutes: Int) =
        source.updateContinuousSegmentMinutes(minutes)

    fun updateDetectionNotificationsEnabled(enabled: Boolean) =
        source.updateDetectionNotificationsEnabled(enabled)

    fun updateAlertQuietHoursEnabled(enabled: Boolean) =
        source.updateAlertQuietHoursEnabled(enabled)

    fun updateAlertQuietHoursStartMinute(minute: Int) =
        source.updateAlertQuietHoursStartMinute(minute)

    fun updateAlertQuietHoursEndMinute(minute: Int) =
        source.updateAlertQuietHoursEndMinute(minute)

    fun updateTamperDetectionEnabled(enabled: Boolean) = source.updateTamperDetectionEnabled(enabled)

    fun updatePushEnabled(enabled: Boolean) = source.updatePushEnabled(enabled)

    fun updateUnifiedPushEnabled(enabled: Boolean) = source.updateUnifiedPushEnabled(enabled)

    fun updatePushVapidSubject(subject: String) = source.updatePushVapidSubject(subject)

    fun updateWatchdogEnabled(enabled: Boolean) = source.updateWatchdogEnabled(enabled)

    fun updateWatchdogMaxRetries(retries: Int) = source.updateWatchdogMaxRetries(retries)

    fun updateWatchdogCheckIntervalSeconds(seconds: Int) =
        source.updateWatchdogCheckIntervalSeconds(seconds)

    fun updateBackupEnabled(enabled: Boolean) = source.updateBackupEnabled(enabled)

    fun updateBackupWifiOnly(wifiOnly: Boolean) = source.updateBackupWifiOnly(wifiOnly)

    fun updateBackupWebdavUrl(url: String) = source.updateBackupWebdavUrl(url)

    fun updateBackupWebdavUsername(username: String) = source.updateBackupWebdavUsername(username)

    fun updateHttpsEnabled(enabled: Boolean) = source.updateHttpsEnabled(enabled)

    fun updateAudioDeviceId(id: String) = source.updateAudioDeviceId(id)

    fun updateResumeStreamsOnBoot(enabled: Boolean) = source.updateResumeStreamsOnBoot(enabled)

    fun updateKeepAwakeEnabled(enabled: Boolean) = source.updateKeepAwakeEnabled(enabled)

    fun updateFsiCameraResume(enabled: Boolean) = source.updateFsiCameraResume(enabled)

    fun updateStorageQuotaMb(quotaMb: Int) = source.updateStorageQuotaMb(quotaMb)

    fun updateMediaEncryptionEnabled(enabled: Boolean) = source.updateMediaEncryptionEnabled(enabled)

    fun updateBackupWebdavPassword(password: String) = source.updateBackupWebdavPassword(password)

    fun updateAuthEnabled(enabled: Boolean) = source.updateAuthEnabled(enabled)

    fun updateAuthUsername(username: String) = source.updateAuthUsername(username)

    fun updateAuthPassword(password: String) = source.updateAuthPassword(password)

    class Factory(
        private val source: SettingsSource,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(source) as T
        }
    }
}
