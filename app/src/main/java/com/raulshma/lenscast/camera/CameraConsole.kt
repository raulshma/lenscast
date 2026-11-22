package com.raulshma.lenscast.camera

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.ConsoleCapabilities
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.LevelIndicatorPolicy
import com.raulshma.lenscast.camera.model.ProToolsPolicy
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.streaming.AdaptiveBitrateController
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The camera dashboard's one seam: the exact surface of [CameraViewModel]
 * the screen consumes, as a plain interface. The app is "one interface, two
 * backends" — the dashboard must stay renderable against either
 *
 * - the **local** backend — today's [CameraViewModel] over this phone's own
 *   hardware (`CameraService`, `StreamingManager`, `RecordingController`,
 *   the `SettingsDataStore`), or
 * - the **remote** backend — a later package (`companion/console/`) that
 *   implements this interface against a paired LensCast device's HTTP API.
 *
 * The UI binds to this interface, never to the concrete ViewModel, and reads
 * [capabilities] to hide what the current backend can't honor (the flag
 * table is [ConsoleCapabilities]; its KDoc names each flag's owner). The
 * local backend publishes [ConsoleCapabilities.local] — the set the
 * dashboard actually renders today, so the screen hides nothing and
 * behavior is unchanged; the remote one builds an opt-in set from what the
 * paired device actually answers.
 *
 * The interface is ViewModel-lifecycle-agnostic by design, so a non-ViewModel
 * class can implement it: only `StateFlow`/`SharedFlow` reads and plain
 * `fun` operations — no `viewModelScope`, no `onCleared`, no provider
 * `Factory`. Lifetime ownership (coroutine scopes, teardown) stays with the
 * implementing class; the concrete ViewModel keeps its own.
 *
 * Members below are exactly what `CameraScreen.kt` (including
 * `ImmersiveCameraView`, `CameraPreview`, and the quick-setting and connect
 * sheet call sites) reads or calls today. A few are inherently local-only —
 * they touch this phone's Android surfaces or this phone's server identity;
 * each carries a note on how the remote backend degrades it, with the
 * screen's gate named from [capabilities].
 */
interface CameraConsole {

    // ── What this backend can honor ──
    // The screen's render gate, read once per composition like every other
    // flow here. Constant for the local backend; the remote backend may
    // republish as its probe/status answers land.

    /** This backend's [ConsoleCapabilities] verdict table. */
    val capabilities: StateFlow<ConsoleCapabilities>

    // ── State flows ──
    // Derived truth the screen renders; backends own their producers.

    val cameraState: StateFlow<CameraState>
    val settings: StateFlow<CameraSettings>
    val streamStatus: StateFlow<StreamStatus>
    val thermalState: StateFlow<ThermalState>
    val wifiConnected: StateFlow<Boolean>
    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState>
    val connectionQualityStats: StateFlow<NetworkQualityMonitor.NetworkStatsSnapshot?>
    val showPreview: StateFlow<Boolean>
    val lastServerError: StateFlow<String?>

    // Capture truth.
    val isRecording: StateFlow<Boolean>
    val recordingState: StateFlow<RecordingState>
    val recordingElapsedSeconds: StateFlow<Int>
    val flashModeSetting: StateFlow<FlashMode>

    // Lens/exposure inventory (the quick-setting ranges and the lens picker).
    val availableLenses: StateFlow<List<CameraLensInfo>>
    val selectedLensIndex: StateFlow<Int>
    val availableIsoRange: StateFlow<ClosedRange<Int>>
    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>>
    val availableExposureRange: StateFlow<ClosedRange<Int>>

    // Viewfinder aids and the self-timer overlay.
    val gridStyle: StateFlow<GridStyle>
    val histogramEnabled: StateFlow<Boolean>
    val zebrasEnabled: StateFlow<Boolean>
    val peakingEnabled: StateFlow<Boolean>

    /**
     * The newest pro-tools analysis frame, or null. Local-only feed: the
     * remote backend holds it at null (no analysis frames cross its API),
     * which keeps the overlays from rendering; the real gate is
     * [ConsoleCapabilities.proToolsOverlays].
     */
    val proToolsFrame: StateFlow<ProToolsPolicy.ProToolsFrame?>

    /**
     * The spirit level's line state, or null when the sensors are parked.
     * Local-only feed: the remote backend has no motion-sensor access and
     * holds it at null; the screen shows the overlay only under
     * [ConsoleCapabilities.spiritLevel].
     */
    val levelLineState: StateFlow<LevelIndicatorPolicy.LevelLineState?>

    val selfTimerSecondsRemaining: StateFlow<Int?>

    // Permission state the screen consults. Local plumbing: the remote
    // backend reports the paired device's readiness through [cameraState]
    // and holds these at granted/read so no launcher path ever fires; the
    // permission screens themselves only render on the local backend.
    val hasAudioPermission: StateFlow<Boolean>
    val notificationPermissionAsked: StateFlow<Boolean>

    // ── One-shot events ──

    /** One emit per actual capture (immediate or timer fire) — the shutter flash. */
    val captureFlashEvents: SharedFlow<Unit>

    // ── Operations ──
    // Verdicts live in the pure policies under camera/model/; an
    // implementation only executes them.

    // Permission plumbing — local-only. The remote backend stubs these to
    // no-ops (its [cameraState] never enters RequestPermission, so the
    // screens that call them never render).
    fun onPermissionResult(cameraGranted: Boolean, audioGranted: Boolean)
    fun onAudioPermissionResult(granted: Boolean)
    fun markNotificationPermissionAsked()

    fun retryCameraInit()

    /**
     * Binds the backend's preview into this phone's [PreviewView] under this
     * activity's [LifecycleOwner] — local-only by signature: a remote
     * backend renders the device's preview through its own player surface
     * instead, stubs this to a no-op, and the screen's viewfinder gating
     * goes through [capabilities].
     */
    fun startPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner)
    fun stopPreview()

    fun switchCamera()
    fun selectLens(index: Int)

    /** Normalized (0..1) preview tap coordinates; gated by [ConsoleCapabilities.tapToFocus]. */
    fun tapToFocus(x: Float, y: Float)

    fun updateZoom(ratio: Float)
    fun updateQuickSetting(type: QuickSettingType, value: Any)
    fun togglePreview()
    fun cycleFlashMode()
    fun onShutterPress()
    fun cancelSelfTimer()
    fun toggleRecording()

    // Stream/server controls — writes gated by [ConsoleCapabilities.canControl],
    // the panel itself by [ConsoleCapabilities.serverPanel].
    fun toggleWebStreaming()
    fun toggleRtspStreaming()
    fun toggleServer()

    fun copyStreamUrl()
    fun copyRtspUrl()
    fun copyHlsUrl()

    /**
     * This phone's server identity for the connect sheet — local-only: the
     * remote backend serves no viewers of its own, degrades this to a stub,
     * and the screen never opens the sheet under
     * [ConsoleCapabilities.serverPanel] = false.
     */
    fun getConnectInfo(): StreamingManager.ConnectInfo

    /**
     * The web identity's TLS certificate fingerprint (empty until built) —
     * local-only like [getConnectInfo]; the remote backend stubs it empty
     * and the sheet's TLS row is gated by [ConsoleCapabilities.serverPanel].
     */
    fun tlsCertificateFingerprint(): String
}
