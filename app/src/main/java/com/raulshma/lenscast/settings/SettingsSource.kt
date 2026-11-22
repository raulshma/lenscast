package com.raulshma.lenscast.settings

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.CameraSettingsEditor
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.PhotoAspectRatio
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.SecretUpdate
import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.WebAuthGate
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The settings screens' one seam — the exact surface [SettingsViewModel] (and
 * through it both [CameraSettingsScreen] and [AppSettingsScreen]) consumes,
 * as typed flows + write ops. One interface, two backends (the
 * [com.raulshma.lenscast.camera.CameraConsole] /
 * [com.raulshma.lenscast.automation.AutomationSource] pattern):
 *
 * - the **local** backend — [LocalSettingsSource], today's graph singletons
 *   ([SettingsDataStore] passthrough + the phone's camera/power/model
 *   collaborators); behavior identical to the pre-source ViewModel; or
 * - the **remote** backend —
 *   [com.raulshma.lenscast.companion.console.RemoteSettingsSource], the
 *   paired device's `GET/PUT /api/settings` document (camera + streaming
 *   sections) plus `GET/PUT /api/auth/config` for the credential section.
 *
 * **The partition** — which lane owns which member — is the load-bearing
 * design fact here, because some settings are THIS phone's app preferences
 * and everything else is device-enforced state:
 *
 * | Lane          | Members | Remote binding |
 * |---------------|---------|----------------|
 * | device-side   | `settings` (the wire camera section), every streaming/output/detection/integration/storage/reliability member, `authSettings`, the model-state reads and their downloads | the `GET /api/settings` document (writes: whole-section GET-merge-PUT; auth: the `/api/auth/config` merge); gated by [SettingsCapabilities.canEdit] |
 * | viewer-local  | `showPreview`, `dynamicThemingEnabled`, `gridStyle`, `selfTimer`, `spiritLevelEnabled`, `histogramEnabled`, `zebrasEnabled`, `peakingEnabled` | STAYS on this phone's [SettingsDataStore] even in remote mode — they render THIS app's viewfinder/theming, not the device's capture config (the [com.raulshma.lenscast.companion.console.DeviceConsoleViewModel] wave-3 precedent), and their writes ignore [SettingsCapabilities.canEdit] (a viewer still themes their own app) |
 * | phone-only    | the battery-optimization trio, the camera-service live ranges, `isRawCaptureSupported` | meaningful only against this phone; the remote backend pins safe values and the [SettingsCapabilities] flags hide the rows that cannot render remotely |
 *
 * A viewer-role pairing pins [SettingsCapabilities.canEdit] false; every
 * device-side write is a no-op at the source (the P4/P5 hide-write decision —
 * the screens render the read-only banner and the controls never move).
 *
 * The interface is ViewModel-lifecycle-agnostic by design (only `StateFlow`
 * reads and plain `fun` operations) so a non-ViewModel class can implement
 * it; lifetime ownership stays with the implementing class.
 */
interface SettingsSource {

    /** This backend's [SettingsCapabilities] verdict table (read once per composition). */
    val capabilities: StateFlow<SettingsCapabilities>

    /**
     * The device-side config-write verdict (the roster role's): device-side
     * writes no-op when false. Viewer-local writes ignore it. Mirrored in
     * [SettingsCapabilities.canEdit] for the screens' gates.
     */
    val canEdit: Boolean

    // ── Device-side: the camera section ──

    /** The bound camera's persisted settings (the wire camera section remotely). */
    val settings: StateFlow<CameraSettings>

    /** The camera's live control ranges — the settings sliders' spans. */
    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>>
    val availableIsoRange: StateFlow<ClosedRange<Int>>
    val availableExposureRange: StateFlow<ClosedRange<Int>>

    /**
     * The bound camera's live RAW verdict (the RAW row's capability note).
     * No wire carrier — the remote backend pins true (the row's section is
     * wire-degraded hidden remotely anyway, so the value never renders).
     */
    val isRawCaptureSupported: StateFlow<Boolean>

    // ── Photo capture knobs: device capture config with NO settings-wire
    // carrier — the remote backend pins the app defaults and the whole
    // section is capability-hidden ([SettingsCapabilities.photoCaptureSection]).

    val photoJpegQuality: StateFlow<Int>
    val photoMaximizeQuality: StateFlow<Boolean>
    val rawCaptureEnabled: StateFlow<Boolean>
    val photoAspectRatio: StateFlow<PhotoAspectRatio>
    val geotagEnabled: StateFlow<Boolean>

    // ── Phone-local resume levers (no wire carrier) ──

    /** The FSI camera-resume lever: phone-local, capability-hidden remotely. */
    val fsiCameraResume: StateFlow<Boolean>

    // ── Device-side: stream outputs & transport ──

    val streamingPort: StateFlow<Int>
    val webStreamingEnabled: StateFlow<Boolean>
    val jpegQuality: StateFlow<Int>
    val adaptiveBitrateEnabled: StateFlow<Boolean>
    val adaptiveEncodedBitrateEnabled: StateFlow<Boolean>
    val hlsDvrSegments: StateFlow<Int>
    val ecoIdleFpsEnabled: StateFlow<Boolean>
    val mdnsEnabled: StateFlow<Boolean>
    val rtspEnabled: StateFlow<Boolean>
    val rtspPort: StateFlow<Int>
    val rtspBasicAuthCompat: StateFlow<Boolean>
    val rtspInputFormat: StateFlow<RtspInputFormat>
    val rtspSubStreamEnabled: StateFlow<Boolean>
    val rtmpEnabled: StateFlow<Boolean>

    /**
     * The RTMP push URL. Remotely the wire answer is BLANK (the embedded
     * stream key is write-only — the `Secret` wire type), so the field
     * renders empty on a paired device; typing a fresh value overwrites.
     */
    val rtmpUrl: StateFlow<String>
    val whipEnabled: StateFlow<Boolean>
    val whipUrl: StateFlow<String>
    val whipStunServer: StateFlow<String>
    val srtEnabled: StateFlow<Boolean>

    /** The SRT push URL — the RTMP URL's secret semantics remotely (blank GET). */
    val srtUrl: StateFlow<String>

    // ── Device-side: audio ──

    val streamAudioEnabled: StateFlow<Boolean>
    val streamAudioBitrateKbps: StateFlow<Int>
    val streamAudioChannels: StateFlow<Int>
    val streamAudioEchoCancellation: StateFlow<Boolean>
    val recordingAudioEnabled: StateFlow<Boolean>
    val audioDeviceId: StateFlow<String>

    // ── Device-side: detection & alerts ──

    val motionDetectionEnabled: StateFlow<Boolean>
    val motionSensitivityPercent: StateFlow<Int>
    val motionRecordingEnabled: StateFlow<Boolean>
    val motionPostRollSeconds: StateFlow<Int>
    val motionArmScheduleEnabled: StateFlow<Boolean>
    val motionArmStartMinute: StateFlow<Int>
    val motionArmEndMinute: StateFlow<Int>
    val motionArmDaysMask: StateFlow<Int>
    val motionCooldownSeconds: StateFlow<Int>
    val soundDetectionEnabled: StateFlow<Boolean>
    val soundThresholdPercent: StateFlow<Int>
    val soundAdaptiveNoiseFloor: StateFlow<Boolean>
    val soundRecordingEnabled: StateFlow<Boolean>
    val soundCooldownSeconds: StateFlow<Int>
    val soundClassificationEnabled: StateFlow<Boolean>
    val soundClassificationConfidencePercent: StateFlow<Int>
    val soundClassificationAllowedClasses: StateFlow<Set<String>>
    val soundTriggerEnabled: StateFlow<Boolean>
    val soundTriggerClasses: StateFlow<Set<String>>
    val mlDetectionEnabled: StateFlow<Boolean>
    val mlMinScorePercent: StateFlow<Int>
    val mlIncludePerson: StateFlow<Boolean>
    val mlIncludePets: StateFlow<Boolean>
    val mlIncludeVehicles: StateFlow<Boolean>
    val tamperDetectionEnabled: StateFlow<Boolean>
    val detectionNotificationsEnabled: StateFlow<Boolean>
    val alertQuietHoursEnabled: StateFlow<Boolean>
    val alertQuietHoursStartMinute: StateFlow<Int>
    val alertQuietHoursEndMinute: StateFlow<Int>
    val pushEnabled: StateFlow<Boolean>
    val unifiedPushEnabled: StateFlow<Boolean>
    val pushVapidSubject: StateFlow<String>

    /**
     * The detection model's download lifecycle. Remotely mapped from the
     * document's response-only `mlModelState`/`mlModelProgress`/`mlModelError`
     * fields (the wire's read of the DEVICE's model file — the local path in
     * a remote [DetectionModelStore.State.Ready] is never meaningful).
     */
    val detectionModelState: StateFlow<DetectionModelStore.State>

    /**
     * The YAMNet audio model's download lifecycle, or null when the backend
     * has no carrier for it (remote: the settings wire carries only the
     * detection model's state) — the null-means-no-carrier degrade, the
     * [com.raulshma.lenscast.automation.AutomationSource.torchStrengthMaxLevel]
     * precedent. Null hides the model row; the download write still exists.
     */
    val audioModelState: StateFlow<AudioModelStore.State?>

    // ── Device-side: storage, recording, reliability, integrations ──

    val storageQuotaMb: StateFlow<Int>
    val mediaEncryptionEnabled: StateFlow<Boolean>
    val continuousRecording: StateFlow<Boolean>
    val continuousSegmentMinutes: StateFlow<Int>
    val watchdogEnabled: StateFlow<Boolean>
    val watchdogMaxRetries: StateFlow<Int>
    val watchdogCheckIntervalSeconds: StateFlow<Int>
    val backupEnabled: StateFlow<Boolean>
    val backupWifiOnly: StateFlow<Boolean>
    val backupWebdavUrl: StateFlow<String>
    val backupWebdavUsername: StateFlow<String>
    val httpsEnabled: StateFlow<Boolean>
    val keepAwakeEnabled: StateFlow<Boolean>
    val resumeStreamsOnBoot: StateFlow<Boolean>

    /**
     * The device's web-auth credential state. Remotely this rides
     * `GET/PUT /api/auth/config` (not the settings document — the credential
     * surface is its own admin-only route), server-redacted: the password
     * hash never crosses back, so a remote [StreamAuthSettings] carries an
     * empty hash and the password write goes through as a rotation.
     */
    val authSettings: StateFlow<StreamAuthSettings>

    // ── Viewer-local lanes (this phone's app prefs; stay local when remote) ──

    /** The dashboard's preview-in-window toggle (this app's viewfinder PiP). */
    val showPreview: StateFlow<Boolean>

    /** This app's Material You theming toggle. */
    val dynamicThemingEnabled: StateFlow<Boolean>

    // The camera screen's viewfinder aids — render/persist on THIS phone.
    val gridStyle: StateFlow<GridStyle>
    val selfTimer: StateFlow<SelfTimerMode>
    val spiritLevelEnabled: StateFlow<Boolean>
    val histogramEnabled: StateFlow<Boolean>
    val zebrasEnabled: StateFlow<Boolean>
    val peakingEnabled: StateFlow<Boolean>

    // ── Phone-only facts ──

    /**
     * Whether THIS phone's OS exempts the app from battery optimization.
     * Compose state (not a Flow) — the pre-source ViewModel's shape, kept;
     * refreshed on resume via [refreshBatteryOptimizationStatus]. The remote
     * backend pins false and the row is capability-hidden.
     */
    val isIgnoringBatteryOptimizations: State<Boolean>

    // ── Writes: camera section ──

    /**
     * The camera-settings screen's single camera-settings write entry, the
     * Quick Setting Catalog's write table (the raw editor callback value is
     * converted onto the typed editor value per the descriptor's shape).
     * Caller-gated by [SettingsCapabilities.canEdit].
     */
    fun updateQuickSetting(type: QuickSettingType, value: Any)

    /** The manual-focus slider; the catalog's FOCUS row covers only the mode. */
    fun updateFocusDistance(distance: Float?)

    /** The manual white-balance Kelvin slider; the WHITE_BALANCE row covers only the mode. */
    fun updateColorTemperature(temp: Int?)

    /** The scene-mode dropdown; "OFF" clears the override (no catalog row). */
    fun updateSceneMode(mode: String)

    /** The top-bar Reset: camera settings back to their defaults. Caller-gated by canEdit. */
    fun resetToDefaults()

    // ── Writes: stream outputs & transport (device-side) ──

    fun updateStreamingPort(port: Int)
    fun updateWebStreamingEnabled(enabled: Boolean)
    fun updateJpegQuality(quality: Int)
    fun updateAdaptiveBitrateEnabled(enabled: Boolean)
    fun updateAdaptiveEncodedBitrateEnabled(enabled: Boolean)
    fun updateRtspSubStreamEnabled(enabled: Boolean)
    fun updateHlsDvrSegments(segments: Int)
    fun updateEcoIdleFpsEnabled(enabled: Boolean)
    fun updateMdnsEnabled(enabled: Boolean)
    fun updateRtspEnabled(enabled: Boolean)
    fun updateRtspPort(port: Int)
    fun updateRtspBasicAuthCompat(enabled: Boolean)
    fun updateRtspInputFormat(name: String)
    fun updateRtmpEnabled(enabled: Boolean)
    fun updateRtmpUrl(url: String)
    fun updateWhipEnabled(enabled: Boolean)
    fun updateWhipUrl(url: String)

    /** Write-only on both backends; an empty token keeps the stored one (no-op). */
    fun updateWhipToken(token: String)
    fun updateWhipStunServer(server: String)
    fun updateSrtEnabled(enabled: Boolean)
    fun updateSrtUrl(url: String)

    // ── Writes: audio (device-side) ──

    fun updateStreamAudioEnabled(enabled: Boolean)
    fun updateStreamAudioBitrateKbps(bitrateKbps: Int)
    fun updateStreamAudioChannels(channels: Int)
    fun updateStreamAudioEchoCancellation(enabled: Boolean)
    fun updateRecordingAudioEnabled(enabled: Boolean)
    fun updateAudioDeviceId(id: String)

    // ── Writes: detection & alerts (device-side) ──

    fun updateMotionDetectionEnabled(enabled: Boolean)
    fun updateMotionSensitivity(percent: Int)
    fun updateMotionRecordingEnabled(enabled: Boolean)
    fun updateMotionPostRollSeconds(seconds: Int)
    fun updateMotionArmScheduleEnabled(enabled: Boolean)
    fun updateMotionArmStartMinute(minute: Int)
    fun updateMotionArmEndMinute(minute: Int)
    fun updateMotionArmDaysMask(mask: Int)
    fun updateMotionCooldownSeconds(seconds: Int)
    fun updateSoundDetectionEnabled(enabled: Boolean)
    fun updateSoundThresholdPercent(percent: Int)
    fun updateSoundAdaptiveNoiseFloor(enabled: Boolean)
    fun updateSoundRecordingEnabled(enabled: Boolean)
    fun updateSoundCooldownSeconds(seconds: Int)
    fun updateSoundClassificationEnabled(enabled: Boolean)
    fun updateSoundClassificationConfidencePercent(percent: Int)
    fun updateSoundClassificationAllowedClasses(classes: Set<String>)
    fun updateSoundTriggerEnabled(enabled: Boolean)
    fun updateSoundTriggerClasses(classes: Set<String>)
    fun updateMlDetectionEnabled(enabled: Boolean)
    fun updateMlMinScorePercent(percent: Int)
    fun updateMlIncludePerson(enabled: Boolean)
    fun updateMlIncludePets(enabled: Boolean)
    fun updateMlIncludeVehicles(enabled: Boolean)
    fun updateTamperDetectionEnabled(enabled: Boolean)
    fun updateDetectionNotificationsEnabled(enabled: Boolean)
    fun updateAlertQuietHoursEnabled(enabled: Boolean)
    fun updateAlertQuietHoursStartMinute(minute: Int)
    fun updateAlertQuietHoursEndMinute(minute: Int)
    fun updatePushEnabled(enabled: Boolean)
    fun updateUnifiedPushEnabled(enabled: Boolean)
    fun updatePushVapidSubject(subject: String)

    /**
     * The detection model's fetch (the wire's `/api/settings/ml-model/download`
     * route remotely; the model store's idempotent request locally).
     */
    fun downloadDetectionModel()

    /**
     * The YAMNet model's fetch (`/api/settings/audio-model/download`
     * remotely; the audio model store locally). Remote progress has no wire
     * carrier — [audioModelState] stays null there.
     */
    fun downloadAudioModel()

    // ── Writes: storage, recording, reliability, integrations (device-side) ──

    fun updateStorageQuotaMb(quotaMb: Int)
    fun updateMediaEncryptionEnabled(enabled: Boolean)
    fun updateContinuousRecording(enabled: Boolean)
    fun updateContinuousSegmentMinutes(minutes: Int)
    fun updateWatchdogEnabled(enabled: Boolean)
    fun updateWatchdogMaxRetries(retries: Int)
    fun updateWatchdogCheckIntervalSeconds(seconds: Int)
    fun updateBackupEnabled(enabled: Boolean)
    fun updateBackupWifiOnly(wifiOnly: Boolean)
    fun updateBackupWebdavUrl(url: String)
    fun updateBackupWebdavUsername(username: String)

    /** Write-only on both backends; an empty password keeps the stored one (no-op). */
    fun updateBackupWebdavPassword(password: String)
    fun updateHttpsEnabled(enabled: Boolean)
    fun updateKeepAwakeEnabled(enabled: Boolean)
    fun updateResumeStreamsOnBoot(enabled: Boolean)

    /** No wire carrier — the remote backend no-ops and the row is capability-hidden. */
    fun updateFsiCameraResume(enabled: Boolean)

    // ── Writes: photo capture knobs (no settings-wire carrier remotely) ──

    fun updatePhotoJpegQuality(quality: Int)
    fun updatePhotoMaximizeQuality(enabled: Boolean)
    fun updateRawCaptureEnabled(enabled: Boolean)
    fun updatePhotoAspectRatio(aspect: PhotoAspectRatio)
    fun updateGeotagEnabled(enabled: Boolean)

    // ── Writes: the device's web-auth credentials (rides /api/auth/config) ──

    fun updateAuthEnabled(enabled: Boolean)
    fun updateAuthUsername(username: String)

    /** A rotation: the plaintext goes through once and is never echoed back. */
    fun updateAuthPassword(password: String)

    // ── Writes: viewer-local lanes (this phone's prefs; never role-gated) ──

    fun updateShowPreview(show: Boolean)
    fun updateDynamicThemingEnabled(enabled: Boolean)
    fun updateGridStyle(name: String)
    fun updateSelfTimer(name: String)
    fun updateSpiritLevelEnabled(enabled: Boolean)
    fun updateHistogramEnabled(enabled: Boolean)
    fun updateZebrasEnabled(enabled: Boolean)
    fun updatePeakingEnabled(enabled: Boolean)

    // ── Writes: phone-only ──

    /** Re-reads [isIgnoringBatteryOptimizations] (the ON_RESUME refresh). */
    fun refreshBatteryOptimizationStatus()

    /** Fires THIS phone's battery-optimization system dialog (no-op remotely). */
    fun requestIgnoreBatteryOptimization(activity: Activity)
}

/**
 * What the current settings backend can honor — the screens' render verdict
 * table (the [com.raulshma.lenscast.camera.model.ConsoleCapabilities]
 * pattern): the UI hides what the backend's flags don't cover, so neither
 * backend needs an `if (remote)` branch. Pure immutable data; the backends
 * own the verdicts.
 */
data class SettingsCapabilities(

    /**
     * The roster role's config-write verdict: every DEVICE-side write the
     * screens offer is presented only when true (viewer pairings render the
     * read-only banner and their writes no-op at the source). Viewer-local
     * writes ignore this — a viewer still themes their own app.
     */
    val canEdit: Boolean,

    /**
     * The photo-capture knob section (photo JPEG quality, aspect ratio,
     * maximize-quality, RAW, EXIF geotag): local true; remote false — the
     * settings wire carries none of them, so the section degrades to hidden
     * (the P5 wire-lacks-a-carrier precedent).
     */
    val photoCaptureSection: Boolean,

    /** This app's self-update section: phone-only, hidden against a device. */
    val selfUpdate: Boolean,

    /** The battery-optimization row: an intent on THIS phone only. */
    val batteryOptimizationRow: Boolean,

    /** The resume-on-boot row: a phone-local boot intent, no wire carrier. */
    val resumeOnBootRow: Boolean,

    /** The FSI camera-resume row: a phone-local resume lever, no wire carrier. */
    val fsiResumeRow: Boolean,

    /**
     * The microphone picker dropdown: enumerates THIS phone's mics, so it
     * renders only on the local backend (the `audioDeviceId` VALUE stays
     * device-side and rides the document).
     */
    val microphonePicker: Boolean,

    /**
     * The RTMP/WHIP/SRT live publisher status lines: mirror THIS phone's
     * publisher lifecycle, so they render only on the local backend (the
     * enable/URL settings themselves stay device-side).
     */
    val livePublisherStatus: Boolean,

    /**
     * The Web Push VAPID subject field: the settings wire carries the push
     * toggles but not the subject, so the field renders only on the local
     * backend (the toggles stay device-side).
     */
    val pushVapidSubjectField: Boolean,

    /**
     * The web-auth credential section: rides `/api/auth/config`, an
     * admin-only read ([com.raulshma.lenscast.streaming.web.RoleGatePolicy]) —
     * local true; remote pinned to [canEdit].
     */
    val securitySection: Boolean,
) {
    companion object {

        /**
         * The local phone's set: everything the screens render today stays
         * rendered — the behavior-neutral invariant (the ConsoleCapabilities
         * rule that `local()` is what the local screens ACTUALLY render).
         */
        fun local(): SettingsCapabilities = SettingsCapabilities(
            canEdit = true,
            photoCaptureSection = true,
            selfUpdate = true,
            batteryOptimizationRow = true,
            resumeOnBootRow = true,
            fsiResumeRow = true,
            microphonePicker = true,
            livePublisherStatus = true,
            pushVapidSubjectField = true,
            securitySection = true,
        )

        /**
         * The remote paired-device set: the wire-carried sections render,
         * every phone-only affordance hides, and the write gate is the
         * roster role's verdict.
         */
        fun remote(canEdit: Boolean): SettingsCapabilities = SettingsCapabilities(
            canEdit = canEdit,
            photoCaptureSection = false,
            selfUpdate = false,
            batteryOptimizationRow = false,
            resumeOnBootRow = false,
            fsiResumeRow = false,
            microphonePicker = false,
            livePublisherStatus = false,
            pushVapidSubjectField = false,
            securitySection = canEdit,
        )
    }
}

/**
 * The settings screens' LOCAL backend: [SettingsSource] over this phone's
 * graph singletons — the SettingsViewModel's old collaborators, lifted
 * verbatim into a source. The settings store stays the single source of
 * truth (every flow reads straight through, no mirror state), the camera
 * writes funnel through the persist-only camera write pass (the editor's
 * normalize; runtime application is the Settings Applier's), and the auth
 * writes keep the exact pre-source logic (the blank-pair refusal, the
 * session revocation on credential changes — fired when the save commits —
 * the RTSP HA1 recomputation).
 */
class LocalSettingsSource(
    private val settingsDataStore: SettingsDataStore,
    /** The bound camera: live control ranges + the RAW verdict. */
    private val cameraService: CameraService,
    /** THIS phone's battery-optimization gate. */
    private val powerManager: PowerManager?,
    /** The on-demand ML model store; its download is the one non-setting write. */
    private val detectionModelStore: DetectionModelStore,
    /** The on-demand YAMNet model store; same one non-setting write. */
    private val audioModelStore: AudioModelStore,
    /** Optional: revokes live web sessions when credentials rotate in-app. */
    private val webAuthGate: WebAuthGate? = null,
    /** The scope the writes launch on (the graph's app scope). */
    private val scope: CoroutineScope,
) : SettingsSource {

    override val capabilities = MutableStateFlow(SettingsCapabilities.local())

    override val canEdit: Boolean = true

    // Reads come straight from the Settings Store's shared StateFlows — no
    // per-source stateIn, no retyped defaults (the ViewModel's old rule).

    override val settings: StateFlow<CameraSettings> = settingsDataStore.settings
    override val availableZoomRange = cameraService.availableZoomRange
    override val availableIsoRange = cameraService.availableIsoRange
    override val availableExposureRange = cameraService.availableExposureRange
    override val isRawCaptureSupported = cameraService.isRawCaptureSupported

    // Photo capture knobs (no wire carrier remotely): the store, verbatim.
    override val photoJpegQuality: StateFlow<Int> = settingsDataStore.photoJpegQuality
    override val photoMaximizeQuality: StateFlow<Boolean> = settingsDataStore.photoMaximizeQuality
    override val rawCaptureEnabled: StateFlow<Boolean> = settingsDataStore.rawCaptureEnabled
    override val photoAspectRatio: StateFlow<PhotoAspectRatio> = settingsDataStore.photoAspectRatio
    override val geotagEnabled: StateFlow<Boolean> = settingsDataStore.geotagEnabled

    override val fsiCameraResume: StateFlow<Boolean> = settingsDataStore.fsiCameraResume

    override val streamingPort: StateFlow<Int> = settingsDataStore.streamingPort
    override val webStreamingEnabled: StateFlow<Boolean> = settingsDataStore.webStreamingEnabled
    override val jpegQuality: StateFlow<Int> = settingsDataStore.jpegQuality
    override val adaptiveBitrateEnabled: StateFlow<Boolean> = settingsDataStore.adaptiveBitrateEnabled
    override val adaptiveEncodedBitrateEnabled: StateFlow<Boolean> =
        settingsDataStore.encodedAdaptiveBitrateEnabled
    override val hlsDvrSegments: StateFlow<Int> = settingsDataStore.hlsDvrSegments
    override val ecoIdleFpsEnabled: StateFlow<Boolean> = settingsDataStore.ecoIdleFpsEnabled
    override val mdnsEnabled: StateFlow<Boolean> = settingsDataStore.mdnsEnabled
    override val rtspEnabled: StateFlow<Boolean> = settingsDataStore.rtspEnabled
    override val rtspPort: StateFlow<Int> = settingsDataStore.rtspPort
    override val rtspBasicAuthCompat: StateFlow<Boolean> = settingsDataStore.rtspBasicAuthCompat
    override val rtspInputFormat: StateFlow<RtspInputFormat> = settingsDataStore.rtspInputFormat
    override val rtspSubStreamEnabled: StateFlow<Boolean> = settingsDataStore.rtspSubStreamEnabled
    override val rtmpEnabled: StateFlow<Boolean> = settingsDataStore.rtmpEnabled
    override val rtmpUrl: StateFlow<String> = settingsDataStore.rtmpUrl
    override val whipEnabled: StateFlow<Boolean> = settingsDataStore.whipEnabled
    override val whipUrl: StateFlow<String> = settingsDataStore.whipUrl
    override val whipStunServer: StateFlow<String> = settingsDataStore.whipStunServer
    override val srtEnabled: StateFlow<Boolean> = settingsDataStore.srtEnabled
    override val srtUrl: StateFlow<String> = settingsDataStore.srtUrl

    override val streamAudioEnabled: StateFlow<Boolean> = settingsDataStore.streamAudioEnabled
    override val streamAudioBitrateKbps: StateFlow<Int> = settingsDataStore.streamAudioBitrateKbps
    override val streamAudioChannels: StateFlow<Int> = settingsDataStore.streamAudioChannels
    override val streamAudioEchoCancellation: StateFlow<Boolean> =
        settingsDataStore.streamAudioEchoCancellation
    override val recordingAudioEnabled: StateFlow<Boolean> = settingsDataStore.recordingAudioEnabled
    override val audioDeviceId: StateFlow<String> = settingsDataStore.audioDeviceId

    override val motionDetectionEnabled: StateFlow<Boolean> = settingsDataStore.motionDetectionEnabled
    override val motionSensitivityPercent: StateFlow<Int> = settingsDataStore.motionSensitivity
    override val motionRecordingEnabled: StateFlow<Boolean> = settingsDataStore.motionRecordingEnabled
    override val motionPostRollSeconds: StateFlow<Int> = settingsDataStore.motionPostRollSeconds
    override val motionArmScheduleEnabled: StateFlow<Boolean> =
        settingsDataStore.motionArmScheduleEnabled
    override val motionArmStartMinute: StateFlow<Int> = settingsDataStore.motionArmStartMinute
    override val motionArmEndMinute: StateFlow<Int> = settingsDataStore.motionArmEndMinute
    override val motionArmDaysMask: StateFlow<Int> = settingsDataStore.motionArmDaysMask
    override val motionCooldownSeconds: StateFlow<Int> = settingsDataStore.motionCooldownSeconds
    override val soundDetectionEnabled: StateFlow<Boolean> = settingsDataStore.soundDetectionEnabled
    override val soundThresholdPercent: StateFlow<Int> = settingsDataStore.soundThresholdPercent
    override val soundAdaptiveNoiseFloor: StateFlow<Boolean> =
        settingsDataStore.soundAdaptiveNoiseFloor
    override val soundRecordingEnabled: StateFlow<Boolean> = settingsDataStore.soundRecordingEnabled
    override val soundCooldownSeconds: StateFlow<Int> = settingsDataStore.soundCooldownSeconds
    override val soundClassificationEnabled: StateFlow<Boolean> =
        settingsDataStore.soundClassificationEnabled
    override val soundClassificationConfidencePercent: StateFlow<Int> =
        settingsDataStore.soundClassificationConfidencePercent
    override val soundClassificationAllowedClasses: StateFlow<Set<String>> =
        settingsDataStore.soundClassificationAllowedClasses
    override val soundTriggerEnabled: StateFlow<Boolean> = settingsDataStore.soundTriggerEnabled
    override val soundTriggerClasses: StateFlow<Set<String>> = settingsDataStore.soundTriggerClasses
    override val mlDetectionEnabled: StateFlow<Boolean> = settingsDataStore.mlDetectionEnabled
    override val mlMinScorePercent: StateFlow<Int> = settingsDataStore.mlMinScorePercent
    override val mlIncludePerson: StateFlow<Boolean> = settingsDataStore.mlIncludePerson
    override val mlIncludePets: StateFlow<Boolean> = settingsDataStore.mlIncludePets
    override val mlIncludeVehicles: StateFlow<Boolean> = settingsDataStore.mlIncludeVehicles
    override val tamperDetectionEnabled: StateFlow<Boolean> = settingsDataStore.tamperDetectionEnabled
    override val detectionNotificationsEnabled: StateFlow<Boolean> =
        settingsDataStore.detectionNotificationsEnabled
    override val alertQuietHoursEnabled: StateFlow<Boolean> =
        settingsDataStore.alertQuietHoursEnabled
    override val alertQuietHoursStartMinute: StateFlow<Int> =
        settingsDataStore.alertQuietHoursStartMinute
    override val alertQuietHoursEndMinute: StateFlow<Int> = settingsDataStore.alertQuietHoursEndMinute
    override val pushEnabled: StateFlow<Boolean> = settingsDataStore.pushEnabled
    override val unifiedPushEnabled: StateFlow<Boolean> = settingsDataStore.unifiedPushEnabled
    override val pushVapidSubject: StateFlow<String> = settingsDataStore.pushVapidSubject

    override val detectionModelState: StateFlow<DetectionModelStore.State> = detectionModelStore.state
    override val audioModelState: StateFlow<AudioModelStore.State?> = audioModelStore.state

    override val storageQuotaMb: StateFlow<Int> = settingsDataStore.storageQuotaMb
    override val mediaEncryptionEnabled: StateFlow<Boolean> = settingsDataStore.mediaEncryptionEnabled
    override val continuousRecording: StateFlow<Boolean> = settingsDataStore.continuousRecording
    override val continuousSegmentMinutes: StateFlow<Int> = settingsDataStore.continuousSegmentMinutes
    override val watchdogEnabled: StateFlow<Boolean> = settingsDataStore.watchdogEnabled
    override val watchdogMaxRetries: StateFlow<Int> = settingsDataStore.watchdogMaxRetries
    override val watchdogCheckIntervalSeconds: StateFlow<Int> =
        settingsDataStore.watchdogCheckIntervalSeconds
    override val backupEnabled: StateFlow<Boolean> = settingsDataStore.backupEnabled
    override val backupWifiOnly: StateFlow<Boolean> = settingsDataStore.backupWifiOnly
    override val backupWebdavUrl: StateFlow<String> = settingsDataStore.backupWebdavUrl
    override val backupWebdavUsername: StateFlow<String> = settingsDataStore.backupWebdavUsername
    override val httpsEnabled: StateFlow<Boolean> = settingsDataStore.httpsEnabled
    override val keepAwakeEnabled: StateFlow<Boolean> = settingsDataStore.keepAwakeEnabled
    override val resumeStreamsOnBoot: StateFlow<Boolean> = settingsDataStore.resumeStreamsOnBoot
    override val authSettings: StateFlow<StreamAuthSettings> = settingsDataStore.authSettings

    // Viewer-local lanes: the store, verbatim.
    override val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview
    override val dynamicThemingEnabled: StateFlow<Boolean> = settingsDataStore.dynamicThemingEnabled
    override val gridStyle: StateFlow<GridStyle> = settingsDataStore.gridStyle
    override val selfTimer: StateFlow<SelfTimerMode> = settingsDataStore.selfTimer
    override val spiritLevelEnabled: StateFlow<Boolean> = settingsDataStore.spiritLevelEnabled
    override val histogramEnabled: StateFlow<Boolean> = settingsDataStore.histogramEnabled
    override val zebrasEnabled: StateFlow<Boolean> = settingsDataStore.zebrasEnabled
    override val peakingEnabled: StateFlow<Boolean> = settingsDataStore.peakingEnabled

    // The battery-optimization mirror state, verbatim from the old ViewModel.
    private val _isIgnoringBatteryOptimizations =
        androidx.compose.runtime.mutableStateOf(false)
    override val isIgnoringBatteryOptimizations: State<Boolean> = _isIgnoringBatteryOptimizations

    init {
        refreshBatteryOptimizationStatus()
    }

    // The camera-settings write funnel's serialization. Persist-only
    // (runtime application is the Settings Applier's), but the merge base is
    // the disk truth under a mutex — never the shared flow's `.value` —
    // because the flow holds the placeholder until DataStore's first read
    // lands and lags the disk by a collector beat after each save (the
    // store's default-first race): a second edit landing inside that window
    // would fold onto a stale base and silently drop the first edit's field,
    // and a pre-hydration edit would persist the placeholder document.
    private val cameraWriteMutex = Mutex()

    override fun refreshBatteryOptimizationStatus() {
        _isIgnoringBatteryOptimizations.value = powerManager?.isIgnoringBatteryOptimizations() == true
    }

    override fun requestIgnoreBatteryOptimization(activity: Activity) {
        powerManager?.requestIgnoreBatteryOptimization(activity)
    }

    override fun updateQuickSetting(type: QuickSettingType, value: Any) {
        val editorValue = QuickSettingCatalog.editorValueFor(type, value) ?: return
        update { current -> QuickSettingCatalog.descriptorFor(type).write(current, editorValue) }
    }

    override fun updateFocusDistance(distance: Float?) {
        update { it.copy(focusDistance = distance) }
    }

    override fun updateColorTemperature(temp: Int?) {
        update { it.copy(colorTemperature = temp) }
    }

    override fun updateSceneMode(mode: String) {
        update { it.copy(sceneMode = CameraSettingsEditor.parseSceneMode(mode)) }
    }

    // Reset rides the incremental edits' ordered write path: a bare save
    // could land inside an edit's read-modify-write window and be folded
    // away by its stale merge base (and vice versa — an edit queued after
    // the Reset would survive as a write onto the defaults).
    override fun resetToDefaults() = update { _ -> CameraSettings() }

    override fun updatePhotoJpegQuality(quality: Int) =
        save { settingsDataStore.savePhotoJpegQuality(quality) }

    override fun updatePhotoMaximizeQuality(enabled: Boolean) =
        save { settingsDataStore.savePhotoMaximizeQuality(enabled) }

    override fun updateRawCaptureEnabled(enabled: Boolean) =
        save { settingsDataStore.saveRawCaptureEnabled(enabled) }

    override fun updatePhotoAspectRatio(aspect: PhotoAspectRatio) =
        save { settingsDataStore.savePhotoAspectRatio(aspect) }

    override fun updateGeotagEnabled(enabled: Boolean) =
        save { settingsDataStore.saveGeotagEnabled(enabled) }

    override fun updateStreamingPort(port: Int) = save { settingsDataStore.saveStreamingPort(port) }

    override fun updateWebStreamingEnabled(enabled: Boolean) =
        save { settingsDataStore.saveWebStreamingEnabled(enabled) }

    override fun updateJpegQuality(quality: Int) = save { settingsDataStore.saveJpegQuality(quality) }

    override fun updateAdaptiveBitrateEnabled(enabled: Boolean) =
        save { settingsDataStore.saveAdaptiveBitrateEnabled(enabled) }

    override fun updateAdaptiveEncodedBitrateEnabled(enabled: Boolean) =
        save { settingsDataStore.saveEncodedAdaptiveBitrateEnabled(enabled) }

    override fun updateRtspSubStreamEnabled(enabled: Boolean) =
        save { settingsDataStore.saveRtspSubStreamEnabled(enabled) }

    override fun updateHlsDvrSegments(segments: Int) =
        save { settingsDataStore.saveHlsDvrSegments(segments) }

    override fun updateEcoIdleFpsEnabled(enabled: Boolean) =
        save { settingsDataStore.saveEcoIdleFpsEnabled(enabled) }

    override fun updateMdnsEnabled(enabled: Boolean) = save { settingsDataStore.saveMdnsEnabled(enabled) }

    override fun updateRtspEnabled(enabled: Boolean) = save { settingsDataStore.saveRtspEnabled(enabled) }

    override fun updateRtspPort(port: Int) = save { settingsDataStore.saveRtspPort(port) }

    override fun updateRtspBasicAuthCompat(enabled: Boolean) =
        save { settingsDataStore.saveRtspBasicAuthCompat(enabled) }

    override fun updateRtspInputFormat(name: String) = save {
        settingsDataStore.saveRtspInputFormat(parseEnum(name, RtspInputFormat.AUTO))
    }

    override fun updateRtmpEnabled(enabled: Boolean) = save { settingsDataStore.saveRtmpEnabled(enabled) }

    override fun updateRtmpUrl(url: String) = save { settingsDataStore.saveRtmpUrl(url) }

    override fun updateWhipEnabled(enabled: Boolean) = save { settingsDataStore.saveWhipEnabled(enabled) }

    override fun updateWhipUrl(url: String) = save { settingsDataStore.saveWhipUrl(url) }

    override fun updateWhipToken(token: String) {
        if (SecretUpdate.overwrites(token)) save { settingsDataStore.saveWhipToken(token) }
    }

    override fun updateWhipStunServer(server: String) =
        save { settingsDataStore.saveWhipStunServer(server) }

    override fun updateSrtEnabled(enabled: Boolean) = save { settingsDataStore.saveSrtEnabled(enabled) }

    override fun updateSrtUrl(url: String) = save { settingsDataStore.saveSrtUrl(url) }

    override fun updateStreamAudioEnabled(enabled: Boolean) =
        save { settingsDataStore.saveStreamAudioEnabled(enabled) }

    override fun updateStreamAudioBitrateKbps(bitrateKbps: Int) =
        save { settingsDataStore.saveStreamAudioBitrateKbps(bitrateKbps) }

    override fun updateStreamAudioChannels(channels: Int) =
        save { settingsDataStore.saveStreamAudioChannels(channels) }

    override fun updateStreamAudioEchoCancellation(enabled: Boolean) =
        save { settingsDataStore.saveStreamAudioEchoCancellation(enabled) }

    override fun updateRecordingAudioEnabled(enabled: Boolean) =
        save { settingsDataStore.saveRecordingAudioEnabled(enabled) }

    override fun updateAudioDeviceId(id: String) = save { settingsDataStore.saveAudioDeviceId(id) }

    override fun updateMotionDetectionEnabled(enabled: Boolean) =
        save { settingsDataStore.saveMotionDetectionEnabled(enabled) }

    override fun updateMotionSensitivity(percent: Int) =
        save { settingsDataStore.saveMotionSensitivity(percent) }

    override fun updateMotionRecordingEnabled(enabled: Boolean) =
        save { settingsDataStore.saveMotionRecordingEnabled(enabled) }

    override fun updateMotionPostRollSeconds(seconds: Int) =
        save { settingsDataStore.saveMotionPostRollSeconds(seconds) }

    override fun updateMotionArmScheduleEnabled(enabled: Boolean) =
        save { settingsDataStore.saveMotionArmScheduleEnabled(enabled) }

    override fun updateMotionArmStartMinute(minute: Int) =
        save { settingsDataStore.saveMotionArmStartMinute(minute) }

    override fun updateMotionArmEndMinute(minute: Int) =
        save { settingsDataStore.saveMotionArmEndMinute(minute) }

    override fun updateMotionArmDaysMask(mask: Int) =
        save { settingsDataStore.saveMotionArmDaysMask(mask) }

    override fun updateMotionCooldownSeconds(seconds: Int) =
        save { settingsDataStore.saveMotionCooldownSeconds(seconds) }

    override fun updateSoundDetectionEnabled(enabled: Boolean) =
        save { settingsDataStore.saveSoundDetectionEnabled(enabled) }

    override fun updateSoundThresholdPercent(percent: Int) =
        save { settingsDataStore.saveSoundThresholdPercent(percent) }

    override fun updateSoundAdaptiveNoiseFloor(enabled: Boolean) =
        save { settingsDataStore.saveSoundAdaptiveNoiseFloor(enabled) }

    override fun updateSoundRecordingEnabled(enabled: Boolean) =
        save { settingsDataStore.saveSoundRecordingEnabled(enabled) }

    override fun updateSoundCooldownSeconds(seconds: Int) =
        save { settingsDataStore.saveSoundCooldownSeconds(seconds) }

    override fun updateSoundClassificationEnabled(enabled: Boolean) =
        save { settingsDataStore.saveSoundClassificationEnabled(enabled) }

    override fun updateSoundClassificationConfidencePercent(percent: Int) =
        save { settingsDataStore.saveSoundClassificationConfidencePercent(percent) }

    override fun updateSoundClassificationAllowedClasses(classes: Set<String>) =
        save { settingsDataStore.saveSoundClassificationAllowedClasses(classes) }

    override fun updateSoundTriggerEnabled(enabled: Boolean) =
        save { settingsDataStore.saveSoundTriggerEnabled(enabled) }

    override fun updateSoundTriggerClasses(classes: Set<String>) =
        save { settingsDataStore.saveSoundTriggerClasses(classes) }

    override fun updateMlDetectionEnabled(enabled: Boolean) =
        save { settingsDataStore.saveMlDetectionEnabled(enabled) }

    override fun updateMlMinScorePercent(percent: Int) =
        save { settingsDataStore.saveMlMinScorePercent(percent) }

    override fun updateMlIncludePerson(enabled: Boolean) =
        save { settingsDataStore.saveMlIncludePerson(enabled) }

    override fun updateMlIncludePets(enabled: Boolean) =
        save { settingsDataStore.saveMlIncludePets(enabled) }

    override fun updateMlIncludeVehicles(enabled: Boolean) =
        save { settingsDataStore.saveMlIncludeVehicles(enabled) }

    override fun updateTamperDetectionEnabled(enabled: Boolean) =
        save { settingsDataStore.saveTamperDetectionEnabled(enabled) }

    override fun updateDetectionNotificationsEnabled(enabled: Boolean) =
        save { settingsDataStore.saveDetectionNotificationsEnabled(enabled) }

    override fun updateAlertQuietHoursEnabled(enabled: Boolean) =
        save { settingsDataStore.saveAlertQuietHoursEnabled(enabled) }

    override fun updateAlertQuietHoursStartMinute(minute: Int) =
        save { settingsDataStore.saveAlertQuietHoursStartMinute(minute) }

    override fun updateAlertQuietHoursEndMinute(minute: Int) =
        save { settingsDataStore.saveAlertQuietHoursEndMinute(minute) }

    override fun updatePushEnabled(enabled: Boolean) = save { settingsDataStore.savePushEnabled(enabled) }

    override fun updateUnifiedPushEnabled(enabled: Boolean) =
        save { settingsDataStore.saveUnifiedPushEnabled(enabled) }

    override fun updatePushVapidSubject(subject: String) =
        save { settingsDataStore.savePushVapidSubject(subject) }

    override fun downloadDetectionModel() {
        detectionModelStore.requestDownload()
    }

    override fun downloadAudioModel() {
        audioModelStore.requestDownload()
    }

    override fun updateStorageQuotaMb(quotaMb: Int) =
        save { settingsDataStore.saveStorageQuotaMb(quotaMb) }

    override fun updateMediaEncryptionEnabled(enabled: Boolean) =
        save { settingsDataStore.saveMediaEncryptionEnabled(enabled) }

    override fun updateContinuousRecording(enabled: Boolean) =
        save { settingsDataStore.saveContinuousRecording(enabled) }

    override fun updateContinuousSegmentMinutes(minutes: Int) =
        save { settingsDataStore.saveContinuousSegmentMinutes(minutes) }

    override fun updateWatchdogEnabled(enabled: Boolean) =
        save { settingsDataStore.saveWatchdogEnabled(enabled) }

    override fun updateWatchdogMaxRetries(retries: Int) =
        save { settingsDataStore.saveWatchdogMaxRetries(retries) }

    override fun updateWatchdogCheckIntervalSeconds(seconds: Int) =
        save { settingsDataStore.saveWatchdogCheckIntervalSeconds(seconds) }

    override fun updateBackupEnabled(enabled: Boolean) =
        save { settingsDataStore.saveBackupEnabled(enabled) }

    override fun updateBackupWifiOnly(wifiOnly: Boolean) =
        save { settingsDataStore.saveBackupWifiOnly(wifiOnly) }

    override fun updateBackupWebdavUrl(url: String) =
        save { settingsDataStore.saveBackupWebdavUrl(url) }

    override fun updateBackupWebdavUsername(username: String) =
        save { settingsDataStore.saveBackupWebdavUsername(username) }

    override fun updateBackupWebdavPassword(password: String) {
        if (SecretUpdate.overwrites(password)) save { settingsDataStore.saveBackupWebdavPassword(password) }
    }

    override fun updateHttpsEnabled(enabled: Boolean) = save { settingsDataStore.saveHttpsEnabled(enabled) }

    override fun updateKeepAwakeEnabled(enabled: Boolean) =
        save { settingsDataStore.saveKeepAwakeEnabled(enabled) }

    override fun updateResumeStreamsOnBoot(enabled: Boolean) =
        save { settingsDataStore.saveResumeStreamsOnBoot(enabled) }

    override fun updateFsiCameraResume(enabled: Boolean) =
        save { settingsDataStore.saveFsiCameraResume(enabled) }

    override fun updateAuthEnabled(enabled: Boolean) {
        enqueueAuthEdit(
            PendingAuthEdit(
                apply = { current ->
                    // The gate folds a blank pair to auth-off, which would
                    // leave the switch looking enabled while the device is
                    // open. The refusal evaluates INSIDE the queued edit —
                    // against the state the drain lands with, after any
                    // earlier queued edits have folded — so an enable queued
                    // behind a credential edit reads the post-fold pair, not
                    // this call's (lagging) flow snapshot. Refuse to enable
                    // until both halves exist.
                    if (enabled && (current.username.isBlank() || current.passwordHash.isBlank())) {
                        current
                    } else {
                        current.copy(enabled = enabled)
                    }
                },
                // The disable transition on the folded states: a revocation
                // verdict over (before, after), not an eager side effect.
                revokeWhen = { before, after -> before.enabled && !after.enabled },
            ),
        )
    }

    override fun updateAuthUsername(username: String) {
        enqueueAuthEdit(
            PendingAuthEdit(
                // The RTSP HA1 cannot be recomputed without the password, so
                // a rename drops it (the authorizer falls back to Basic
                // until the password is re-entered). Resubmitting the
                // current username is a no-op — the digest survives.
                apply = { current ->
                    if (current.username == username) {
                        current
                    } else {
                        current.copy(username = username, rtspDigestHa1 = "")
                    }
                },
                revokeWhen = { before, after -> before.username != after.username },
            ),
        )
    }

    override fun updateAuthPassword(password: String) {
        val hash = StreamAuthCrypto.hashPassword(password)
        // The digest HA1 must bind the username the save lands with: a rapid
        // username edit can still be in flight when this update runs, so the
        // digest is computed inside the write pass, not from this call's
        // (lagging) flow snapshot.
        enqueueAuthEdit(
            PendingAuthEdit(
                apply = {
                    it.copy(
                        passwordHash = hash,
                        rtspDigestHa1 = StreamAuthCrypto.computeRtspDigestHa1(it.username, password),
                    )
                },
                revokeWhen = { before, after -> before.passwordHash != after.passwordHash },
            ),
        )
    }

    // ── Viewer-local writes (the store, verbatim) ──

    override fun updateShowPreview(show: Boolean) = save { settingsDataStore.saveShowPreview(show) }

    override fun updateDynamicThemingEnabled(enabled: Boolean) =
        save { settingsDataStore.saveDynamicThemingEnabled(enabled) }

    override fun updateGridStyle(name: String) = save {
        settingsDataStore.saveGridStyle(parseEnum(name, GridStyle.OFF))
    }

    override fun updateSelfTimer(name: String) = save {
        settingsDataStore.saveSelfTimer(parseEnum(name, SelfTimerMode.OFF))
    }

    override fun updateSpiritLevelEnabled(enabled: Boolean) =
        save { settingsDataStore.saveSpiritLevelEnabled(enabled) }

    override fun updateHistogramEnabled(enabled: Boolean) =
        save { settingsDataStore.saveHistogramEnabled(enabled) }

    override fun updateZebrasEnabled(enabled: Boolean) =
        save { settingsDataStore.saveZebrasEnabled(enabled) }

    override fun updatePeakingEnabled(enabled: Boolean) =
        save { settingsDataStore.savePeakingEnabled(enabled) }

    /** The one persist seam: every plain store write launches through here. */
    private fun save(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    // The camera-edit chain's ordering monitor. The screens' callbacks are
    // main-thread, so appends carry the call order; the drain's extraction
    // is atomic under it — a racing later pass finds an empty chain (the
    // auth chain's discipline, below).
    private val cameraEditMonitor = Any()
    private val pendingCameraEdits = mutableListOf<(CameraSettings) -> CameraSettings>()

    /**
     * The camera-settings funnel: persist-only (the Settings Applier
     * applies). Each edit publishes its transform onto the pending chain
     * BEFORE the writer coroutine launches, and the write pass drains the
     * whole chain — submission order — onto
     * [SettingsDataStore.settingsFromDisk] under [cameraWriteMutex], the
     * read-your-write seam. The queue is load-bearing: the app scope runs
     * a multi-threaded dispatcher, so two bare save passes could take the
     * mutex in either order and the earlier edit would land last, winning
     * the disk with a stale fold — draining the chain under the mutex pins
     * the last-submitted edit as the last write.
     */
    private fun update(transform: (CameraSettings) -> CameraSettings) {
        synchronized(cameraEditMonitor) { pendingCameraEdits += transform }
        save { drainCameraEdits() }
    }

    private suspend fun drainCameraEdits() {
        cameraWriteMutex.withLock {
            // Take the whole chain under the monitor — no suspension inside,
            // so each edit is drained exactly once even when two save passes
            // race: the later one finds an empty chain and returns.
            val edits = synchronized(cameraEditMonitor) {
                if (pendingCameraEdits.isEmpty()) return@withLock
                val drained = pendingCameraEdits.toList()
                pendingCameraEdits.clear()
                drained
            }
            // Per-edit normalize inside the fold: the batch's end state is
            // exactly the sequential per-edit writes' (each transform sees
            // the previous one's normalized output), landed in one
            // DataStore write.
            settingsDataStore.saveSettings(
                edits.fold(settingsDataStore.settingsFromDisk()) { state, edit ->
                    CameraSettingsEditor.normalizeManualDefaults(edit(state))
                },
            )
        }
    }

    /**
     * One queued auth edit: the field transform, plus the session-revocation
     * verdict the drained save carries. The revocation rides the drain as a
     * verdict over the folded save's (before, after) — fired only when the
     * save COMMITS, the AuthWebHandler config route's order (save, then
     * revoke on the transition) — instead of a side effect fired at enqueue
     * time: revoking before the save leaves a window where sessions minted
     * under the old credentials stay valid across the new config's
     * application, and revoking for a save that then fails locks browsers
     * out of a device whose credentials never changed.
     */
    private class PendingAuthEdit(
        val apply: (StreamAuthSettings) -> StreamAuthSettings,
        val revokeWhen: ((before: StreamAuthSettings, after: StreamAuthSettings) -> Boolean)? = null,
    )

    /**
     * The auth writes' persist seam. Each update publishes its field change
     * — as a [PendingAuthEdit] carrying its post-commit revocation verdict —
     * onto a pending-edit chain (monitor-protected; the screens' callbacks
     * are main-thread, so the appends carry the call order), and the write
     * pass folds the whole chain onto the store's disk truth in one
     * serialized read-modify-write. Rapid edits must compose onto the latest
     * auth state: a write of an eagerly-built `current.copy(...)` snapshot
     * races both itself (two snapshots built before either save lands — the
     * second overwrites the first edit's fields with stale values) and the
     * shared flow (it lags the disk by a collector beat — the
     * [SettingsDataStore.editAuthSettings] discipline). Folding at write
     * time — inside the store's [SettingsDataStore.editAuthSettings]
     * transaction, passes serialized by [authWriteMutex] — gives both: every
     * pass folds onto the disk truth DataStore hands the transform, so
     * whichever pass takes the mutex, no edit is dropped. The transaction is
     * also what shields the drain against the other auth writer:
     * [com.raulshma.lenscast.streaming.web.AuthWebHandler]'s rotation folds
     * onto the same record inside its own transaction, and DataStore's lock
     * serializes the two — neither side can clobber the other's fold. The
     * scope runs this on a multi-threaded dispatcher (the graph's
     * `Dispatchers.Default` app scope), so two drain passes CAN race — the
     * chain is therefore extracted INSIDE the mutex: extraction order is
     * mutex order is disk order, and a drain always takes the whole pending
     * chain in enqueue order (a racing later pass finds an empty chain).
     *
     * Failure keeps the batch: a drain whose editAuthSettings
     * throws restores the drained edits at the FRONT of the chain (enqueue
     * order preserved ahead of anything that arrived mid-pass) — nothing is
     * dropped. The failure is one warning line plus a delayed retry pass,
     * not an app-scope rethrow: the scope carries no exception handler, so
     * the old rethrow killed the process, and its only retry trigger was
     * the next enqueue (or a restart) — the scheduled pass retries the
     * restored chain without either. A committed save returns normally —
     * DataStore's edit only throws before its write lands — so a restored
     * batch can never reapply an edit that already committed.
     */
    private val authEditMonitor = Any()
    private val pendingAuthEdits = mutableListOf<PendingAuthEdit>()
    private val authWriteMutex = Mutex()

    private fun enqueueAuthEdit(edit: PendingAuthEdit) {
        synchronized(authEditMonitor) { pendingAuthEdits += edit }
        save { drainAuthEdits() }
    }

    private suspend fun drainAuthEdits() {
        authWriteMutex.withLock {
            // Take the whole pending chain under the monitor — no suspension
            // inside, so each edit is drained exactly once even when two save
            // passes race: the later one finds an empty chain and returns.
            val edits = synchronized(authEditMonitor) {
                if (pendingAuthEdits.isEmpty()) return@withLock
                val drained = pendingAuthEdits.toList()
                pendingAuthEdits.clear()
                drained
            }
            try {
                // The fold runs inside the store's edit transaction — its
                // lock, not this mutex, is what keeps the web rotation's
                // writer out of the read-modify-write.
                var revoke = false
                settingsDataStore.editAuthSettings { current ->
                    val next = edits.fold(current) { state, edit -> edit.apply(state) }
                    // The verdict is computed against the states this pass
                    // actually folded; it only fires once the edit commits.
                    revoke = edits.any { it.revokeWhen?.invoke(current, next) == true }
                    next
                }
                if (revoke) {
                    webAuthGate?.revokeAllSessions()
                }
            } catch (e: CancellationException) {
                // The scope is going down — the batch is unapplied, so it
                // stays queued; no retry pass survives a dead scope.
                synchronized(authEditMonitor) { pendingAuthEdits.addAll(0, edits) }
                throw e
            } catch (t: Throwable) {
                // The drain failed before committing, so the batch is
                // unapplied: restore it at the FRONT of the chain (enqueue
                // order ahead of anything that arrived mid-pass), report the
                // failure (the Applier's one-warning-line convention) and
                // schedule a retry — the restored batch re-drains without
                // waiting for the next enqueue.
                synchronized(authEditMonitor) { pendingAuthEdits.addAll(0, edits) }
                Log.w(TAG, "Auth settings save failed; the batch stays queued", t)
                save {
                    delay(AUTH_SAVE_RETRY_MILLIS)
                    drainAuthEdits()
                }
            }
        }
    }

    private companion object {
        private const val TAG = "LocalSettingsSource"

        /** The auth drain's retry interval — a slow heartbeat, not a hot loop. */
        private const val AUTH_SAVE_RETRY_MILLIS = 30_000L
    }
}
