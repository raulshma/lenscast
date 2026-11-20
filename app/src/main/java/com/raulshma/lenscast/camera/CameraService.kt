package com.raulshma.lenscast.camera


import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
// Aliased: the model's own TorchState state machine is the published torch
// fact; this import stays for CameraX's torch-state constant.
import androidx.camera.core.TorchState as CameraXTorchState
import androidx.camera.core.CameraState as CameraXCameraState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.UseCase
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.raulshma.lenscast.camera.model.CameraErrorPolicy
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraControlPlan
import com.raulshma.lenscast.camera.model.KelvinGainsPolicy
import com.raulshma.lenscast.camera.model.CameraResetResult
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.BindChoreography
import com.raulshma.lenscast.camera.model.CameraSessionArbiter
import com.raulshma.lenscast.camera.model.FocusApplyPolicy
import com.raulshma.lenscast.camera.model.FrameErrorPolicy
import com.raulshma.lenscast.camera.model.PhotoCapturePlan
import com.raulshma.lenscast.camera.model.OutputRotationPolicy
import com.raulshma.lenscast.camera.model.PhotoAspectRatioPolicy
import com.raulshma.lenscast.camera.model.SelectedLens
import com.raulshma.lenscast.camera.model.SessionDemand
import com.raulshma.lenscast.camera.model.StreamAspect
import com.raulshma.lenscast.camera.model.TorchState
import com.raulshma.lenscast.camera.model.TorchStrengthPolicy
import com.raulshma.lenscast.camera.model.ControlsApplyPolicy
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import android.os.Build
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraService(private val context: Context) {

    private class KeepAliveLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        init {
            runOnMainThread { registry.currentState = Lifecycle.State.CREATED }
        }

        fun activate() {
            runOnMainThread { registry.currentState = Lifecycle.State.STARTED }
        }

        fun deactivate() {
            runOnMainThread { registry.currentState = Lifecycle.State.CREATED }
        }

        private fun runOnMainThread(action: () -> Unit) {
            if (android.os.Looper.getMainLooper().isCurrentThread) {
                action()
            } else {
                mainHandler.post(action)
            }
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    /**
     * The camera of the LIVE session — the instance every control apply, remote
     * PTZ/torch flip, live-zoom read, and observation attach must reach. Set by
     * [bindLargestCompatible] on every successful bind — the normal bind and
     * the recording bind ([bindRecording]) alike, since a recording REPLACES
     * the session and a settings apply landing mid-recording must reach the
     * recording session's instance — and cleared by [clearBoundUseCases].
     * Without this seam, every settings apply landing mid-recording silently
     * targeted the closed session — manual ISO/exposure reset at recording
     * start and dashboard changes went dead until the next full rebind.
     */
    private var liveCamera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var frameListener: ((ByteArray, Int, Int, Int) -> Unit)? = null

    // Second frame consumer: the pro-tools analyzer (histogram / zebras /
    // peaking), registered while the camera screen shows the preview. Rides
    // the same tightly-packed NV21 frames as the streaming frame listener.
    @Volatile
    private var proToolsFrameListener: ((ByteArray, Int, Int, Int) -> Unit)? = null

    /**
     * The photo-capture build request (JPEG quality, capture mode, RAW
     * toggle) — applied by the Settings Applier and folded to the device's
     * live RAW capability at bind time. [boundPhotoConfig] is the effective
     * config the current ImageCapture was actually built with.
     */
    @Volatile
    private var photoConfig = PhotoCapturePlan.PhotoCaptureConfig()
    private var boundPhotoConfig: PhotoCapturePlan.PhotoCaptureConfig? = null

    /** Set when a fresh bind's capability query invalidates the config it just built. */
    private var correctivePhotoConfigRebind = false

    private val _isRawCaptureSupported = MutableStateFlow(false)

    /** Whether the bound camera can produce RAW+JPEG output (OUTPUT_FORMAT_RAW_JPEG). */
    val isRawCaptureSupported: StateFlow<Boolean> = _isRawCaptureSupported.asStateFlow()

    /**
     * The one selected-lens fact ([SelectedLens]: selector + facing + index,
     * atomic by construction). Every lens-fact writer updates this single
     * flow; the facing/index flows below are projections of it, and binds
     * read the selector straight off its value.
     */
    private val _selectedLens = MutableStateFlow(
        SelectedLens(CameraSelector.DEFAULT_BACK_CAMERA, isFront = false, index = 0)
    )

    /** The published selected-lens fact; writers are [selectLens], [switchCamera]'s fallback, and [enumerateCameras]. */
    val selectedLens: StateFlow<SelectedLens> = _selectedLens.asStateFlow()

    /**
     * The scope behind the derived public flows below (the same Eagerly
     * stateIn idiom the ViewModels use). Process-lifetime: the service is an
     * app-graph singleton, so nothing cancels it.
     */
    private val derivedFlowsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The one session-demand record ([SessionDemand]): WHAT the camera session
     * is being asked to do — preview, exclusivity, keep-alive, foreground, and
     * whether a view surface exists — as one immutable value. Every writer
     * goes through [updateDemand]'s single copy-on-write seam; every reader
     * (including the frame-analysis thread's cross-thread arbiter consults)
     * sees whole-record snapshots instead of the old statement-at-a-time
     * loose fields. The BINDING side — the bound provider/use cases above —
     * stays in plain fields: it is what the camera is bound WITH, not what it
     * is asked to do.
     */
    private var sessionDemand = SessionDemand()

    /**
     * The single mutation seam for [sessionDemand]: copies the record, swaps
     * it in wholesale, and returns the new value — the post-write refcounts
     * the keep-alive lifecycle toggles and the logs read off it. The old
     * discipline is preserved: all writers run on Main (RecordingService's
     * service callbacks, StreamingSession's and PhotoCaptureManager's explicit
     * Main hops, MainActivity's lifecycle observer, the Compose preview
     * start/stop), and a plain unsynchronized var under that single-writer
     * confinement gives the same benign-staleness reads the loose fields had —
     * with each multi-fact write now atomic instead of statement-at-a-time.
     */
    private fun updateDemand(
        transform: (SessionDemand) -> SessionDemand,
    ): SessionDemand {
        val updated = transform(sessionDemand)
        sessionDemand = updated
        return updated
    }

    @Volatile private var activeSettings = CameraSettings()

    // Whether [applySettings] has committed at least one snapshot. The FIRST
    // apply is the cold-start one — no previously applied settings exist, so
    // the stored intent applies verbatim (the restart contract: a reboot
    // re-lights the LED from the persisted torch pair), and only later
    // applies earn the untouched-write torch convergence.
    @Volatile private var settingsEverApplied = false

    /**
     * The camera-settings save path the untouched-write torch convergence
     * persists through — wired once by the Settings Applier at start
     * ([setSettingsPersister]) against its DataStore, so this service never
     * depends on the store and the converged document rides the exact save
     * every writer uses (one DataStore write, no new store API). Null when
     * unwired (never started, bare unit construction): the convergence still
     * drives per the live fact in memory, only the persisted document is
     * left for the next wired apply to settle.
     */
    @Volatile
    private var persistSettings: (suspend (CameraSettings) -> Unit)? = null

    // Incremented on every successful bind. Post-bind control rungs capture
    // it and drop themselves when a newer bind has landed: the new bind
    // schedules its own rungs, and an old rung's force-focus reapply would
    // otherwise cancel a tap-to-focus on the session that replaced it.
    private var bindGeneration = 0

    /**
     * Whether THIS service last drove the torch on. A settings apply that
     * asks for off only disables a torch we own: force-off on every emission
     * stomped a torch lit elsewhere (the system's flashlight tile, another
     * camera app), while an externally-lit torch that we never touched is
     * not ours to extinguish.
     */
    @Volatile private var torchDrivenOn = false

    // The streaming analysis window's aspect, steered by the encode
    // resolution's aspect through the Settings Applier. Read live at bind
    // time, so a busy camera picks a change up at the next natural rebind.
    @Volatile private var streamAspect: StreamAspect = StreamAspect.WIDE_16_9

    private val controlsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Last settings whose center AF/AE metering was fired; the focus part of
    // the next apply is gated on it (see FocusApplyPolicy). Reset on release
    // so a fresh session re-establishes metering.
    private var lastFocusApplied: CameraSettings? = null

    private val keepAliveLifecycle = KeepAliveLifecycle()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    /**
     * The observed-open fact, separate from [cameraState]: a successful bind
     * CLAIMS Ready before the camera opens (and the open can still be denied
     * later by the while-in-use policy), so consumers that need actual camera
     * *access* — not bind bookkeeping — read this. True only while the
     * camera-open observer has seen OPEN on the currently bound camera; false
     * from bind acceptance until that open lands, on any observed error, and
     * whenever the observation dies with an unbind or a fresh bind.
     */
    private val _cameraOpenObserved = MutableStateFlow(false)
    val cameraOpenObserved: StateFlow<Boolean> = _cameraOpenObserved.asStateFlow()

    // The facing flag is no longer its own store: a projection of the one
    // selected-lens fact above, so it can never disagree with the selector.
    val isFrontCamera: StateFlow<Boolean> = _selectedLens
        .map { it.isFront }
        .stateIn(derivedFlowsScope, SharingStarted.Eagerly, _selectedLens.value.isFront)

    // Defaults are the persistence-side bounds from the CameraSettings
    // companion; the device's live ranges replace them at bind time.
    private val _availableZoomRange =
        MutableStateFlow<ClosedFloatingPointRange<Float>>(CameraSettings.effectiveZoomRange(CameraSettings.ZOOM_RATIO_MAX))
    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _availableZoomRange.asStateFlow()

    private val _availableExposureRange =
        MutableStateFlow<ClosedRange<Int>>(CameraSettings.EXPOSURE_COMPENSATION_MIN..CameraSettings.EXPOSURE_COMPENSATION_MAX)
    val availableExposureRange: StateFlow<ClosedRange<Int>> = _availableExposureRange.asStateFlow()

    private val _availableIsoRange =
        MutableStateFlow<ClosedRange<Int>>(CameraSettings.ISO_RANGE_MIN..CameraSettings.ISO_RANGE_MAX)
    val availableIsoRange: StateFlow<ClosedRange<Int>> = _availableIsoRange.asStateFlow()

    // The bound camera's torch strength ceiling (API 33+
    // FLASH_INFO_STRENGTH_MAXIMUM_LEVEL), the same ladder shape as the ranges
    // above: null until a bind proves the device supports per-level torch at
    // all (pre-33, no flash unit, missing key, or failed query), and the
    // device's real max replaces it at bind time. Ladder semantics in
    // TorchStrengthPolicy.
    private val _torchStrengthMaxLevel = MutableStateFlow<Int?>(null)
    val torchStrengthMaxLevel: StateFlow<Int?> = _torchStrengthMaxLevel.asStateFlow()

    /**
     * The LIVE torch truth ([TorchState]): the bound camera's cameraInfo
     * torchState observation republished on every flip, combined with the
     * strength this service last drove. The persisted torch settings are
     * intent — remote writers flip the live torch without persisting — so
     * consumers must read the torch from here, never from a settings read.
     * [TorchState.Off] while no camera is bound (the live fact dies with the
     * session). Writers: the torch observation below and the apply seam's
     * strength bookkeeping.
     */
    private val _torchState = MutableStateFlow<TorchState>(TorchState.Off)
    val torchState: StateFlow<TorchState> = _torchState.asStateFlow()

    /**
     * The strength level this service last drove onto the torch (the
     * [applyTorchStrength] verdict that reached the flash), null when the
     * device default applies — no level driven this torch-on epoch, an
     * apply-without-strength, or an unbound camera. Fed into [TorchState.from]
     * by every torch publication.
     */
    @Volatile
    private var drivenTorchStrengthLevel: Int? = null

    /**
     * The one registration fact of the torch observation: the observer
     * currently attached to the bound camera's cameraInfo torchState, null
     * while none is. Attach/detach ride [controlsHandler] — LiveData
     * observation is main-thread-only, and the bind paths can run off-main
     * (the frame-error recovery does).
     */
    private var torchStateObserver: Observer<Int>? = null
    private var cameraOpenStateObserver: Observer<CameraXCameraState>? = null

    private var sensorExposureTimeRange: LongRange = 1L..1_000_000_000L

    private val _availableLenses = MutableStateFlow<List<CameraLensInfo>>(emptyList())
    val availableLenses: StateFlow<List<CameraLensInfo>> = _availableLenses.asStateFlow()

    // Same projection shape as [isFrontCamera]: the inventory index the lens
    // row highlights, read off the one selected-lens fact.
    val selectedLensIndex: StateFlow<Int> = _selectedLens
        .map { it.index }
        .stateIn(derivedFlowsScope, SharingStarted.Eagerly, _selectedLens.value.index)

    fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    fun acquireKeepAlive() {
        val updated = updateDemand { it.copy(keepAliveRefCount = it.keepAliveRefCount + 1) }
        if (updated.keepAliveRefCount == 1) {
            keepAliveLifecycle.activate()
            Log.d(TAG, "Keep-alive lifecycle activated")
        }
        Log.d(TAG, "Keep-alive ref count: ${updated.keepAliveRefCount}")
    }

    fun releaseKeepAlive() {
        val updated = updateDemand { it.copy(keepAliveRefCount = max(0, it.keepAliveRefCount - 1)) }
        if (updated.keepAliveRefCount == 0) {
            keepAliveLifecycle.deactivate()
            Log.d(TAG, "Keep-alive lifecycle deactivated")
        }
        Log.d(TAG, "Keep-alive ref count: ${updated.keepAliveRefCount}")
    }

    fun beginExclusiveSession() {
        val updated = updateDemand {
            it.copy(exclusiveSessionRefCount = it.exclusiveSessionRefCount + 1)
        }
        Log.d(TAG, "Exclusive camera session started (count=${updated.exclusiveSessionRefCount})")
    }

    fun endExclusiveSession() {
        val updated = updateDemand {
            it.copy(exclusiveSessionRefCount = max(0, it.exclusiveSessionRefCount - 1))
        }
        Log.d(TAG, "Exclusive camera session ended (count=${updated.exclusiveSessionRefCount})")
    }

    fun getEffectiveLifecycleOwner(): LifecycleOwner? {
        return if (sessionDemand.keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner
    }

    fun tapToFocus(x: Float, y: Float) {
        val cam = liveCamera ?: run {
            Log.w(TAG, "tapToFocus: camera not available")
            return
        }
        try {
            startMetering(cam, x, y, CameraControlPlan.meteringOnTap())
            Log.d(TAG, "tapToFocus: x=$x, y=$y")
        } catch (e: Exception) {
            Log.w(TAG, "tapToFocus failed", e)
        }
    }

    /** Remote PTZ/torch seam for the Web API: zoom clamps to the live device range. */
    fun setZoomRatio(ratio: Float): Boolean {
        val cam = liveCamera ?: return false
        return try {
            cam.cameraControl.setZoomRatio(clampZoom(ratio, cam))
            true
        } catch (e: Exception) {
            Log.w(TAG, "setZoomRatio failed", e)
            false
        }
    }

    /**
     * The one zoom clamp for both zoom paths (the settings-apply path and the
     * remote one): the live device range, floored at 1x, falling open to the
     * persistence ceiling before the first zoom state arrives. Sharing the
     * clamp keeps a zoom preset requested for a wide-range lens from
     * desyncing when a smaller-range lens is selected — the apply path would
     * otherwise send the raw persisted ratio past the lens's max.
     */
    private fun clampZoom(ratio: Float, cam: Camera): Float {
        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio
            ?: CameraSettings.ZOOM_RATIO_MAX
        return ratio.coerceIn(1f, max)
    }

    /**
     * The live zoom truth for the Web API's status: CameraX's observed zoom
     * state, not the persisted setting — a remote zoom takes effect without a
     * settings write, and the status must not keep reporting 1.0 while the
     * lens is physically at 3x. Null before the first camera bind; the caller
     * falls back to the persisted setting.
     */
    fun liveZoomRatio(): Float? = liveCamera?.cameraInfo?.zoomState?.value?.zoomRatio

    fun setTorchEnabled(on: Boolean): Boolean {
        val cam = liveCamera ?: return false
        return try {
            if (!cam.cameraInfo.hasFlashUnit()) {
                Log.w(TAG, "setTorchEnabled: no flash unit")
                return false
            }
            cam.cameraControl.enableTorch(on)
            torchDrivenOn = on
            true
        } catch (e: Exception) {
            Log.w(TAG, "setTorchEnabled failed", e)
            false
        }
    }

    /**
     * Toggle source of truth: the live CameraX torch, not the persisted setting
     * (deterrence drives torch without persisting). Derived from the published
     * [torchState] flow — the single torch fact this service publishes.
     */
    fun isTorchOn(): Boolean = _torchState.value is TorchState.On

    /**
     * Publish the torch fact from the live cameraInfo verdict plus the level
     * this service last drove — the one mapping ([TorchState.from]) every
     * publication goes through.
     */
    private fun publishTorchState(liveTorchOn: Boolean) {
        _torchState.value = TorchState.from(liveTorchOn, drivenTorchStrengthLevel)
    }

    /**
     * Attach the torch observation to the just-bound camera. The observer
     * publishes on every flip, and LiveData's initial dispatch publishes the
     * state the new session came up with; the identity guard drops a stale
     * attach when a fast rebind replaced the camera before this ran.
     */
    private fun observeTorchState(cam: Camera) {
        val observer = Observer<Int> { live ->
            publishTorchState(live == CameraXTorchState.ON)
        }
        torchStateObserver = observer
        controlsHandler.post {
            if (BindChoreography.observationAttachPlan(
                    observerIsCurrent = torchStateObserver === observer,
                    cameraIsLive = liveCamera === cam,
                ) == BindChoreography.ObservationAttach.Attach
            ) {
                cam.cameraInfo.torchState.observeForever(observer)
            }
        }
    }

    /**
     * Detach the torch observation from the bound camera's live data. The
     * live data reference is captured before posting: the live camera may
     * already be gone (unbind) by the time this lands on the main thread.
     */
    private fun stopTorchObservation() {
        val observer = torchStateObserver ?: return
        val liveData = liveCamera?.cameraInfo?.torchState
        torchStateObserver = null
        liveData?.let { controlsHandler.post { it.removeObserver(observer) } }
    }

    /**
     * Attach the camera-open observation to the just-bound camera. A bind
     * succeeding is bookkeeping — CameraX's bindToLifecycle returns before
     * the camera *opens*, and a while-in-use policy denial (the locked-mount
     * headline) surfaces later as a non-null StateError in cameraInfo's
     * camera state, not as a bind exception. The observed transitions are
     * the truth: a StateError republishes [CameraState.Error] carrying the
     * [CameraErrorPolicy] verdict (ERROR_CAMERA_DISABLED keeps the dedicated
     * disabled-by-policy message and the POLICY_DENIED verdict — the typed
     * channel streaming's blocked-by-lock detector reads; ERROR_CAMERA_FATAL_ERROR gets
     * [CAMERA_FATAL_MESSAGE] so the dashboard can tell "reboot the phone"
     * from "will self-heal" — blueprint P0 item 4), OPEN republishes Ready.
     * The policy verdict also DRIVES the shell: REBIND spends the bounded
     * `rebindUseCases` lever, WAIT_CAMERA_ADDED registers the
     * [CameraManager.AvailabilityCallback] wait, and an observed OPEN resets
     * the attempt counter — until the camera actually opens, every error
     * consumed a lever attempt. LiveData's initial dispatch re-states the
     * current state, so a rebind into an already-open camera re-asserts
     * Ready; the identity guard drops a stale attach when a fast rebind
     * replaced the camera before this ran.
     */
    private fun observeCameraOpenState(cam: Camera) {
        val observer = Observer<CameraXCameraState> { state ->
            // CameraX carries errors as a non-null StateError on the emitted
            // state (the Type enum has no ERROR member — it stays one of the
            // five lifecycle states), so the error's presence, not the type,
            // is the check — a null code routes to the open-fact transition.
            when (
                val plan = BindChoreography.openStatePlan(
                    errorCode = state.error?.code,
                    typeOpen = state.type == CameraXCameraState.Type.OPEN,
                    rebindAttempts = errorRebindAttempts.get(),
                    readyAlreadyPublished = _cameraState.value is CameraState.Ready,
                )
            ) {
                is BindChoreography.OpenStatePlan.ErrorObservation -> {
                    val disabled = plan.messageKind is BindChoreography.ErrorMessage.DisabledByPolicy
                    Log.w(TAG, "Observed camera-state error (disabled=$disabled, code=${plan.errorCode}, verdict=${plan.verdict})")
                    _cameraOpenObserved.value = false
                    _cameraState.value = CameraState.Error(
                        when (plan.messageKind) {
                            BindChoreography.ErrorMessage.DisabledByPolicy ->
                                "Camera disabled by policy (while-in-use denial)"
                            BindChoreography.ErrorMessage.RebootRequired -> CAMERA_FATAL_MESSAGE
                            is BindChoreography.ErrorMessage.Code ->
                                "Camera error, code ${plan.messageKind.code}"
                        },
                        plan.verdict,
                    )
                    when (plan.followUp) {
                        BindChoreography.ErrorFollowUp.PostRebind -> {
                            errorRebindAttempts.incrementAndGet()
                            // Posted, not inline: a bind inside the LiveData
                            // dispatch would re-enter this observer mid-transition
                            // (the bind's own ERROR → OPEN choreography) — the
                            // same reason the observation attach itself is posted.
                            controlsHandler.post {
                                Log.d(TAG, "CameraErrorPolicy: bounded error rebind (attempt $errorRebindAttempts)")
                                rebindUseCases()
                            }
                        }
                        BindChoreography.ErrorFollowUp.WaitCameraAdded -> registerCameraAddedWait()
                        // CAMERAX_HANDLES — CameraX's own retry (or the device
                        // policy / user toggle) owns the fix; REBOOT_REQUIRED —
                        // no sanctioned in-process lever exists. Both report only.
                        BindChoreography.ErrorFollowUp.ReportOnly -> {}
                    }
                }
                is BindChoreography.OpenStatePlan.StateObservation -> {
                    // The open fact rides every observed transition: OPEN confirms,
                    // anything else withdraws the confirmation.
                    _cameraOpenObserved.value = plan.openObserved
                    if (plan.resetRebindAttempts) {
                        // An open camera is the only receipt that the bounded rebind
                        // lever's budget restarts: until OPEN, each observed error
                        // spent one (CameraErrorPolicy's caller-owned bookkeeping).
                        if (errorRebindAttempts.getAndSet(0) > 0) {
                            Log.d(TAG, "CameraErrorPolicy: camera opened — rebind attempts reset")
                        }
                    }
                    if (plan.publishReady) {
                        _cameraState.value = CameraState.Ready
                    }
                }
            }
        }
        cameraOpenStateObserver = observer
        controlsHandler.post {
            if (BindChoreography.observationAttachPlan(
                    observerIsCurrent = cameraOpenStateObserver === observer,
                    cameraIsLive = liveCamera === cam,
                ) == BindChoreography.ObservationAttach.Attach
            ) {
                cam.cameraInfo.cameraState.observeForever(observer)
            }
        }
    }

    /**
     * Error-driven rebind attempts since the last observed OPEN — the
     * caller-side bookkeeping [CameraErrorPolicy.decide] takes (the counter
     * itself stays out of the pure policy). Atomic because error observations
     * arrive on the main-thread LiveData dispatch while the OPEN reset and
     * the posted rebind hop through [controlsHandler].
     */
    private val errorRebindAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The pending ERROR_CAMERA_REMOVED wait, when one is registered — the
     * [CameraManager.AvailabilityCallback] that reopens the camera when the
     * hardware returns. At most one lives at a time.
     */
    @Volatile
    private var cameraAddedCallback: CameraManager.AvailabilityCallback? = null

    /**
     * The WAIT_CAMERA_ADDED half of [CameraErrorPolicy]: a removed (unplugged
     * USB) camera is a terminal state CameraX has already torn down — the
     * unbind half of the verdict is CameraX's own — so the app-side half is
     * waiting for the platform's availability callback and spending one
     * rebind on the camera's return (blueprint P0 item 4, self-healing §5).
     * The platform primitive is [CameraManager.AvailabilityCallback.onCameraAvailable]
     * — there is no dedicated "added" callback: a removed camera reports
     * unavailable, and its re-addition reports available (as does the
     * registration itself for cameras already present, which conveniently
     * covers the return racing this registration). Registration replaces any
     * pending wait (a flapping USB camera must not accumulate callbacks); the
     * wait is not a lever attempt — only actual rebinds count.
     */
    private fun registerCameraAddedWait() {
        unregisterCameraAddedWait()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            Log.w(TAG, "CameraErrorPolicy: no CameraManager — cannot wait for the camera's return")
            return
        }
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                Log.d(TAG, "CameraErrorPolicy: camera $cameraId available again — re-binding")
                unregisterCameraAddedWait()
                controlsHandler.post { rebindUseCases() }
            }
        }
        cameraAddedCallback = callback
        // The callback body posts to the main handler itself, so the executor
        // variant (28+) can stay on the caller's thread; below P the Handler
        // variant delivers on main — both land in the same posted rebind.
        // (minSdk 23: the Executor overload is API 28+.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.registerAvailabilityCallback({ r -> r.run() }, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.registerAvailabilityCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        Log.d(TAG, "CameraErrorPolicy: waiting for onCameraAdded before reopening")
    }

    private fun unregisterCameraAddedWait() {
        val pending = cameraAddedCallback ?: return
        cameraAddedCallback = null
        try {
            (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)
                ?.unregisterAvailabilityCallback(pending)
        } catch (e: Exception) {
            Log.w(TAG, "CameraErrorPolicy: camera-added wait unregister failed", e)
        }
    }

    /**
     * Detach the camera-open observation — the same lifecycle the torch one
     * rides. The observed-open confirmation dies with it: the next bind must
     * see OPEN again before [cameraOpenObserved] stands up.
     */
    private fun stopCameraOpenObservation() {
        val observer = cameraOpenStateObserver ?: return
        val liveData = liveCamera?.cameraInfo?.cameraState
        cameraOpenStateObserver = null
        _cameraOpenObserved.value = false
        liveData?.let { controlsHandler.post { it.removeObserver(observer) } }
    }

    /**
     * The one MeteringDecision → CameraX translation, shared by tap-to-focus
     * and the settings-apply metering: the plan owns which metering fires
     * ([CameraControlPlan.meteringOnApply]/[CameraControlPlan.meteringOnTap]);
     * this owns the [FocusMeteringAction] construction.
     */
    private fun startMetering(
        cam: Camera,
        x: Float,
        y: Float,
        decision: CameraControlPlan.MeteringDecision,
    ) {
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = when (decision) {
            CameraControlPlan.MeteringDecision.None -> return
            CameraControlPlan.MeteringDecision.AutoCancelMetering ->
                FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(
                        CameraControlPlan.METERING_AUTO_CANCEL_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    .build()
            CameraControlPlan.MeteringDecision.PlainMetering ->
                FocusMeteringAction.Builder(point).build()
        }
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setFrameListener(listener: ((ByteArray, Int, Int, Int) -> Unit)?) {
        frameListener = listener
    }

    /**
     * Registers the pro-tools frame consumer (histogram / zebras / focus
     * peaking). Receives the same tightly-packed NV21 frames as the
     * streaming listener, on the analysis thread — the consumer must be
     * cheap or self-throttling.
     */
    fun setProToolsFrameListener(listener: ((ByteArray, Int, Int, Int) -> Unit)?) {
        proToolsFrameListener = listener
    }

    /**
     * The photo-capture apply seam: stores the request and, when it changes
     * what the ImageCapture builder would produce on an already-bound
     * camera, rebinds through the arbiter's RebindIfFree verdict (the
     * resolution seam's twin — a busy camera picks the change up at the next
     * natural rebind). The plan owns the effective-config fold; this only
     * translates and rebinds.
     */
    fun applyPhotoCaptureConfig(config: PhotoCapturePlan.PhotoCaptureConfig) {
        photoConfig = config
        val effective = PhotoCapturePlan.effective(config, _isRawCaptureSupported.value)
        if (PhotoCapturePlan.needsRebind(boundPhotoConfig, effective)) {
            when (
                val action = CameraSessionArbiter.decide(
                    demandState(CameraSessionArbiter.Trigger.RebindIfFree)
                )
            ) {
                is CameraSessionArbiter.BindingAction -> executeBinding(action)
                else -> Log.d(TAG, "applyPhotoCaptureConfig: deferred until next active session")
            }
        }
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Starting camera initialization...")
            _cameraState.value = CameraState.Initializing
            var provider = awaitProvider()
            // CameraX is a process-global singleton that snapshots the camera
            // list once at construction. A first snapshot taken while camera
            // access was not effective (permission not granted yet, the system
            // camera-access toggle off, a HAL restart) stays empty for the
            // life of the process — every later bind fails with
            // "No available camera can be found Cams:0". The only in-process
            // cure is shutting the singleton down so the next getInstance
            // re-queries the camera list.
            if (provider.availableCameraInfos.isEmpty()) {
                Log.w(TAG, "CameraX reports an empty camera list; forcing provider re-initialization")
                provider = recoverProvider()
                if (provider.availableCameraInfos.isEmpty()) {
                    throw Exception(
                        "No cameras are available to the system. Check the camera permission " +
                            "and the system camera-access toggle, or let the device cool down, then retry."
                    )
                }
            }
            cameraProvider = provider
            enumerateCameras(provider)
            _cameraState.value = CameraState.Ready
            Log.d(TAG, "Camera initialized successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Camera initialization failed", e)
            _cameraState.value = CameraState.Error(e.message ?: "Camera initialization failed")
            Result.failure(e)
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider {
        val future = ProcessCameraProvider.getInstance(context)
        return withTimeoutOrNull(10_000L) {
            future.await()
        } ?: throw Exception("Camera initialization timed out")
    }

    /**
     * Tear the CameraX singleton down and rebuild it, so its camera-list
     * snapshot is retaken against the current camera-access state.
     */
    private suspend fun recoverProvider(): ProcessCameraProvider {
        try {
            cameraProvider?.shutdownAsync()?.await()
        } catch (e: Exception) {
            Log.w(TAG, "CameraX shutdown during recovery failed; re-initializing anyway", e)
        }
        cameraProvider = null
        return awaitProvider()
    }

    private fun ensureCameraProviderAvailable(): ProcessCameraProvider? {
        cameraProvider?.let { return it }

        // Never construct the provider without the permission: its first
        // camera-list snapshot would be empty and stay empty for the whole
        // process (see [initialize]). Fail fast into the retryable error
        // path instead.
        if (!hasCameraPermission()) {
            Log.w(TAG, "ensureCameraProviderAvailable: CAMERA permission not granted; refusing to initialize")
            _cameraState.value = CameraState.Error(CAMERA_PERMISSION_REQUIRED_MESSAGE)
            return null
        }

        return try {
            Log.d(TAG, "ensureCameraProviderAvailable: initializing camera provider")
            _cameraState.value = CameraState.Initializing
            val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
            cameraProvider = provider
            enumerateCameras(provider)
            _cameraState.value = CameraState.Ready
            provider
        } catch (e: Exception) {
            Log.e(TAG, "ensureCameraProviderAvailable: failed", e)
            _cameraState.value = CameraState.Error(e.message ?: "Camera initialization failed")
            null
        }
    }

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun shouldAttachPreview(): Boolean {
        val demand = sessionDemand
        return demand.previewRequested && demand.currentPreviewView != null &&
            demand.isActivityForeground
    }

    /**
     * The one mapping of [sessionDemand] into the arbiter's
     * [CameraSessionArbiter.CameraDemandState], built fresh at every entry
     * point after the writer's [updateDemand] — the consult sees exactly the
     * state the old inline gates re-assembled by hand from different subsets
     * of the loose fields. No demand fact is read outside the record here
     * (`pendingResolution` is the settings side's parked change, not session
     * demand, and stays its own field).
     */
    private fun demandState(
        trigger: CameraSessionArbiter.Trigger,
    ): CameraSessionArbiter.CameraDemandState {
        val demand = sessionDemand
        return CameraSessionArbiter.CameraDemandState(
            previewRequested = demand.previewRequested,
            keepAliveRefCount = demand.keepAliveRefCount,
            exclusiveSessionRefCount = demand.exclusiveSessionRefCount,
            activityForeground = demand.isActivityForeground,
            previewSurfaceAttached = demand.currentPreviewView != null,
            resolutionChangePending = pendingResolution != null,
            trigger = trigger,
        )
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun enumerateCameras(provider: ProcessCameraProvider) {
        try {
            val cameraInfos = provider.availableCameraInfos
            Log.d(TAG, "Found ${cameraInfos.size} cameras")

            val lenses = mutableListOf<CameraLensInfo>()

            for (info in cameraInfos) {
                try {
                    val camera2Info = Camera2CameraInfo.from(info)
                    val cameraId = camera2Info.cameraId
                    val lensFacing = info.lensFacing

                    // Catch any potential exceptions from experimental API calls
                    val physicalCameras = try {
                        info.physicalCameraInfos
                    } catch (e: Exception) {
                        emptySet()
                    }

                    // Always add the logical camera FIRST
                    val logicalFocalLength = getFocalLength(camera2Info)
                    val logicalSelector = buildCameraSelector(info)

                    val logicalCamInfo = CameraLensInfo(
                        id = cameraId,
                        labelKind = LensInventory.labelKind(lensFacing, logicalFocalLength),
                        lensFacing = lensFacing,
                        focalLength = logicalFocalLength,
                        cameraSelector = logicalSelector,
                        physicalCameraId = null,
                    )
                    lenses.add(logicalCamInfo)

                    // Then add physical cameras if available
                    if (physicalCameras.isNotEmpty() && physicalCameras.size > 1) {
                        for (physInfo in physicalCameras) {
                            val physCamera2Info = Camera2CameraInfo.from(physInfo)
                            val physId = physCamera2Info.cameraId
                            // Skip if physical ID matches logical ID to avoid duplicates
                            if (physId == cameraId) continue

                            val focalLength = getFocalLength(physCamera2Info)
                            val selector = CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .setPhysicalCameraId(physId)
                                .build()

                            lenses.add(
                                CameraLensInfo(
                                    id = physId,
                                    labelKind = LensInventory.labelKind(lensFacing, focalLength),
                                    lensFacing = lensFacing,
                                    focalLength = focalLength,
                                    cameraSelector = selector,
                                    physicalCameraId = physId,
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enumerate camera", e)
                }
            }

            // Inventory decisions (dedup, zoom ratios, collision pass, order,
            // main-lens default) live in the tested LensInventory; the service
            // only publishes them.
            val sorted = LensInventory.sortLenses(
                LensInventory.disambiguate(
                    LensInventory.assignZoomRatios(LensInventory.deduplicate(lenses))
                )
            )

            _availableLenses.value = sorted

            // We MUST default to the MAIN logical back camera. Direct binding to physical
            // cameras on start causes black screen on many OEM drivers.
            val logicalBackIndex = LensInventory.defaultBackIndex(sorted)

            // One SelectedLens write for the whole default — selector, facing
            // and index land together. An empty enumeration leaves it alone:
            // nothing consumes the index while the inventory is empty, and
            // the previous selection is what a later rebind would use anyway.
            if (sorted.isNotEmpty()) {
                val defaultLens = sorted[logicalBackIndex]
                _selectedLens.value = SelectedLens(
                    defaultLens.cameraSelector,
                    defaultLens.lensFacing == CameraSelector.LENS_FACING_FRONT,
                    logicalBackIndex,
                )
            }

            Log.d(TAG, "Enumerated ${sorted.size} cameras, default index=$logicalBackIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Camera enumeration failed, falling back to default", e)
            // Fallback — create basic entries
            val fallback = LensInventory.fallbackLenses()
            _availableLenses.value = fallback
            // All three facts reset together (the old fallback reset only the
            // index and left the selector and facing stale): the fallback
            // inventory's first entry is the default back camera.
            val fallbackLens = fallback.first()
            _selectedLens.value = SelectedLens(
                fallbackLens.cameraSelector,
                fallbackLens.lensFacing == CameraSelector.LENS_FACING_FRONT,
                index = 0,
            )
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun getFocalLength(camera2Info: Camera2CameraInfo): Float {
        return try {
            val focalLengths = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )
            focalLengths?.firstOrNull() ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun buildCameraSelector(info: CameraInfo): CameraSelector {
        val camera2Info = Camera2CameraInfo.from(info)
        val cameraId = camera2Info.cameraId
        return CameraSelector.Builder()
            .requireLensFacing(info.lensFacing)
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    try {
                        Camera2CameraInfo.from(cameraInfo).cameraId == cameraId
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            .build()
    }

    fun selectLens(index: Int) {
        val lenses = _availableLenses.value
        if (index < 0 || index >= lenses.size) {
            Log.w(TAG, "selectLens: index $index out of bounds (size ${lenses.size})")
            return
        }
        val lens = lenses[index]
        Log.d(TAG, "selectLens: switching to lens $index: ${LensInventory.baseLabel(lens)}, provider=${cameraProvider != null}, previewView=${sessionDemand.currentPreviewView != null}")

        // One atomic write: a deferred rebind (or a mid-flight second
        // selection) can only ever observe a complete lens fact.
        _selectedLens.value = SelectedLens(
            lens.cameraSelector,
            lens.lensFacing == CameraSelector.LENS_FACING_FRONT,
            index,
        )

        // One arbiter consult: rebind only while the camera is free (demand
        // active, no exclusive session) — the same verdict as switch/recover;
        // otherwise the selector lands on the next active session.
        when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.RebindIfFree)
            )
        ) {
            is CameraSessionArbiter.BindingAction -> executeBinding(action)
            else -> Log.d(TAG, "selectLens: selector updated; camera will switch on next active session")
        }
    }

    /**
     * The shared binding entry: one arbiter consult over the current demand
     * state, then the action — skip while an exclusive session holds the
     * camera, unbind when no demand remains, bind into the effective owner
     * otherwise. Callers that arrive through a freedom gate consult
     * [CameraSessionArbiter] themselves and land in [executeBinding] directly.
     *
     * @return whether the consult ended without a failed bind — a bound
     * camera, an unbind (no demand), or a skip (an exclusive session owns the
     * camera). False only when a *bind* was attempted and failed (the locked
     * device's while-in-use denial is the headline case); the streaming
     * session reads this to flag the stream blocked-by-lock instead of
     * believing the camera came up.
     */
    fun rebindUseCases(): Boolean {
        return when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.RebindCheck)
            )
        ) {
            CameraSessionArbiter.CameraSessionAction.Noop -> {
                Log.d(TAG, "rebindUseCases: skipped while an exclusive session is active")
                true
            }
            is CameraSessionArbiter.BindingAction -> executeBinding(action)
            else -> true
        }
    }

    /**
     * The remote reset-camera lever (blueprint P1 item 10, prior-art §7.2 —
     * the market's missing primitive): the dashboard's targeted
     * camera-subsystem recovery. It runs the SAME bounded rebind lever the
     * error observer runs — [CameraErrorPolicy.decideResetCamera] consults
     * the wave-1 attempt counter, so the HTTP path spends the identical
     * budget instead of bypassing it (a spam-clicked reset button dies at
     * [CameraErrorPolicy.MAX_REBIND_ATTEMPTS] exactly like a flapping
     * camera does), and a standing taxonomy verdict that is not REBIND
     * (REBOOT_REQUIRED / WAIT_CAMERA_ADDED / CAMERAX_HANDLES / POLICY_DENIED)
     * surfaces verbatim without spending anything. A spent attempt increments the
     * same counter, an observed OPEN still resets it — one budget, one
     * bookkeeping, two entry points (the observer and this lever).
     *
     * The bind hops through the main handler: CameraX binds are main-thread
     * work, and a wedged main thread must bound the wait (the same dispatch
     * discipline the session's recovery rebind uses), so this is suspend.
     * Distinct from the soft-restart tier: this NEVER stops the server.
     */
    suspend fun resetCamera(): CameraResetResult {
        val published = (_cameraState.value as? CameraState.Error)?.recovery
        val verdict = CameraErrorPolicy.decideResetCamera(published, errorRebindAttempts.get())
        if (verdict != CameraErrorPolicy.Verdict.REBIND) {
            Log.d(TAG, "resetCamera: verdict $verdict (published=$published) — no rebind spent")
            return CameraResetResult(verdict)
        }
        errorRebindAttempts.incrementAndGet()
        val bound = withContext(Dispatchers.Main) { rebindUseCases() }
        Log.d(TAG, "resetCamera: bounded manual rebind (attempt $errorRebindAttempts, bound=$bound)")
        return CameraResetResult(CameraErrorPolicy.Verdict.REBIND, bound)
    }

    /**
     * The one action → CameraX execution for the binding verdicts, shared by
     * every gated entry point: ensure the provider — only actions that use it
     * reach here, the same passes the old inline gates allowed through — then
     * unbind or bind into the effective lifecycle owner. @return as in
     * [rebindUseCases].
     */
    private fun executeBinding(action: CameraSessionArbiter.BindingAction): Boolean {
        val provider = ensureCameraProviderAvailable()
        Log.d(TAG, "rebindUseCases: provider=${provider != null}, refCount=${sessionDemand.keepAliveRefCount}, previewRequested=${sessionDemand.previewRequested}, lifecycleOwner=${lifecycleOwner != null}")

        if (provider == null) {
            Log.w(TAG, "rebindUseCases: cameraProvider is null, cannot rebind")
            return false
        }

        return when (action) {
            CameraSessionArbiter.BindingAction.Unbind -> {
                provider.unbindAll()
                clearBoundUseCases()
                Log.d(TAG, "rebindUseCases: unbound camera because there is no active demand")
                true
            }
            CameraSessionArbiter.BindingAction.Rebind -> {
                val owner = getEffectiveLifecycleOwner()
                if (owner == null) {
                    Log.w(TAG, "rebindUseCases: no lifecycle owner available and no keep-alive, cannot rebind")
                    return false
                }
                bindUseCases(provider, owner)
            }
        }
    }

    /**
     * Bind a recording [VideoCapture] alongside the current use-case set,
     * trying progressively smaller combinations before falling back to the
     * VideoCapture alone. The caller brackets the recording with
     * [acquireKeepAlive]/[beginExclusiveSession]; [unbindRecording] restores
     * the standard binding.
     *
     * This is the recording seam: callers never touch the provider, the
     * getters, or the combination ladder directly. The ladder itself — the
     * settle choreography the 5ad333c fix taught this seam — is the pure
     * [BindChoreography.recordingBindPlan]'s step list: detach the
     * observations riding the outgoing camera, rebind (no write-back),
     * reattach to the session that just bound, schedule the deferred
     * controls apply. Without it the fresh session came up at platform
     * defaults — auto AE, manual ISO/exposure silently gone for the whole
     * recording. This method only executes the steps.
     */
    fun bindRecording(videoCapture: VideoCapture<Recorder>): Boolean {
        val provider = ensureCameraProviderAvailable() ?: run {
            Log.w(TAG, "bindRecording: camera provider unavailable")
            return false
        }
        val owner = getEffectiveLifecycleOwner()
        if (owner == null) {
            Log.w(TAG, "bindRecording: no lifecycle owner available and no keep-alive")
            return false
        }

        // Captured once — the old `base` list's timing: the plan's slots
        // resolve against exactly the standard set standing at entry.
        val standardCases: Map<BindChoreography.BindSlot, UseCase> =
            listOfNotNull(
                preview?.let { BindChoreography.BindSlot.Preview to it },
                imageCapture?.let { BindChoreography.BindSlot.ImageCapture to it },
                imageAnalysis?.let { BindChoreography.BindSlot.ImageAnalysis to it },
                BindChoreography.BindSlot.VideoCapture to videoCapture,
            ).toMap()
        return try {
            val plan = BindChoreography.recordingBindPlan(
                previewBound = preview != null,
                imageCaptureBound = imageCapture != null,
                imageAnalysisBound = imageAnalysis != null,
            )
            var bound: Camera? = null
            for (step in plan.steps) {
                when (step) {
                    is BindChoreography.RecordingBindStep.DetachObservations -> {
                        stopTorchObservation()
                        stopCameraOpenObservation()
                    }
                    is BindChoreography.RecordingBindStep.BindRecordingSession -> bindLargestCompatible(
                        provider,
                        owner,
                        combinations = step.attempts.map { attempt ->
                            attempt.map { slot -> standardCases.getValue(slot) }
                        },
                        writeBack = step.writeBackStandardFields,
                    ).also { bound = it }
                    is BindChoreography.RecordingBindStep.AttachObservations -> {
                        val cam = bound!! // the plan orders Attach after Bind — set by the step above
                        observeTorchState(cam)
                        observeCameraOpenState(cam)
                    }
                    is BindChoreography.RecordingBindStep.SchedulePostBindApplies -> schedulePostBindControlsApply()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bindRecording: no compatible combination found", e)
            false
        }
    }

    /** Drop the recording binding and restore the standard use-case combination. */
    fun unbindRecording() {
        rebindUseCases()
    }

    private fun getStreamingAnalysisResolution(captureResolution: Size): Size {
        val (width, height) = analysisSizeFor(
            captureResolution.width,
            captureResolution.height,
            MAX_ANALYSIS_WIDTH,
            MAX_ANALYSIS_HEIGHT,
            streamAspect,
        )
        return Size(width, height)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun startPreview(previewView: PreviewView) {
        val provider = ensureCameraProviderAvailable()
        if (provider == null) {
            Log.e(TAG, "startPreview: camera provider is unavailable")
            return
        }

        // One atomic demand write (the old two-statement write of the view and
        // the flag is one copy now): the view surface exists and the preview is
        // requested land together.
        updateDemand { it.copy(currentPreviewView = previewView, previewRequested = true) }
        Log.d(TAG, "startPreview: requested with selector=${_selectedLens.value.selector}, resolution=$currentResolution")

        val owner = getEffectiveLifecycleOwner()
        if (owner == null) {
            Log.e(TAG, "startPreview: no lifecycle owner available")
            return
        }

        bindUseCases(provider, owner)
    }

    fun stopPreview() {
        // One atomic demand write: preview demand AND the view reference are
        // dropped together (unlike onActivityStop, which detaches the surface
        // but keeps the view — the arbiter's ActivityStopping contract).
        updateDemand { it.copy(previewRequested = false, currentPreviewView = null) }
        preview?.surfaceProvider = null

        // One consult on the post-stop state: the old exclusive-session early
        // return is the arbiter's Noop now, and the no-demand unbind (or the
        // keep-alive rebind) is its shared ladder.
        when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.RebindCheck)
            )
        ) {
            CameraSessionArbiter.CameraSessionAction.Noop ->
                Log.d(TAG, "stopPreview: preview released while exclusive session remains active")
            is CameraSessionArbiter.BindingAction -> executeBinding(action)
            else -> {}
        }
    }

    fun acquirePhotoCapture(): ImageCapture? {
        acquireKeepAlive()
        rebindUseCases()
        return imageCapture ?: run {
            releaseKeepAlive()
            rebindUseCases()
            null
        }
    }

    fun releasePhotoCapture() {
        releaseKeepAlive()
        rebindUseCases()
    }

    /**
     * The CameraX bind itself. @return true when the bind landed
     * ([CameraState.Ready] — the same fact the camera UI reads), false on
     * either failure path (a use-case combination no combination ladder can
     * satisfy, or the OS denying the open — the locked device's while-in-use
     * policy). The state write stays the single truth; the return only
     * propagates it to programmatic callers.
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
    ): Boolean {
        Log.d(TAG, "bindUseCases: selector=${_selectedLens.value.selector}, resolution=$currentResolution, attachPreview=${shouldAttachPreview()}")

        // The photo-use-case decisions (quality clamp, capture mode, RAW
        // output format, capability fold) are the pure Photo Capture Plan's;
        // this builder only translates them onto CameraX.
        val effectivePhotoConfig = PhotoCapturePlan.effective(photoConfig, _isRawCaptureSupported.value)

        // The photo use case gets its own resolution selector: the video
        // resolution for 16:9 (the historical behavior), the same tier widened
        // to 4:3 for the full-sensor-ish aspect — the pure Photo Aspect Ratio
        // Policy's mapping, with CameraX's closest-lower-then-higher fallback
        // ladder absorbing devices without the exact size.
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    PhotoAspectRatioPolicy.captureTargetSize(
                        effectivePhotoConfig.aspect,
                        currentResolution,
                    ),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    getStreamingAnalysisResolution(currentResolution),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        // Intentionally DO NOT bind ResolutionSelector to Preview.
        // Let CameraX decide the best display aspect ratio natively to prevent surface bind failures.
        val previewBuilder = Preview.Builder()
        val captureBuilder = ImageCapture.Builder()
            .setJpegQuality(effectivePhotoConfig.jpegQuality)
            .setTargetRotation(captureTargetRotation())
            .setCaptureMode(
                when (PhotoCapturePlan.captureMode(effectivePhotoConfig)) {
                    PhotoCapturePlan.CaptureMode.MAXIMIZE_QUALITY ->
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    PhotoCapturePlan.CaptureMode.MINIMIZE_LATENCY ->
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }
            )
            .setResolutionSelector(captureResolutionSelector)
        if (PhotoCapturePlan.outputFormat(effectivePhotoConfig) ==
            PhotoCapturePlan.PhotoOutputFormat.RAW_JPEG
        ) {
            captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        val selectedLens = _availableLenses.value.getOrNull(_selectedLens.value.index)
        // setPhysicalCameraId is API 28+; below P the logical camera serves
        // all use cases, which is the pre-multi-lens behavior anyway.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && selectedLens?.physicalCameraId != null) {
            val physId = selectedLens.physicalCameraId
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physId)
            Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physId)
            Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physId)
            Log.d(TAG, "bindUseCases: applied physicalCameraId $physId to all use cases")
        }

        val previewView = sessionDemand.currentPreviewView
        preview = if (shouldAttachPreview() && previewView != null) {
            previewBuilder.build().also {
                Log.d(TAG, "bindUseCases: setting surfaceProvider")
                it.surfaceProvider = previewView.surfaceProvider
            }
        } else {
            null
        }

        imageCapture = captureBuilder.build()

        imageAnalysis = analysisBuilder.build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        try {
            // Detach the previous session's torch observation first: the old
            // camera's live data dies with the unbind below, and the attach
            // must follow the new bind (main-thread posts stay in order).
            stopTorchObservation()
            stopCameraOpenObservation()
            provider.unbindAll()
            val bound: Camera = try {
                bindLargestCompatible(
                    provider,
                    owner,
                    combinations = orderedCombinations(
                        listOfNotNull(preview, imageCapture, imageAnalysis),
                        extra = null,
                    ),
                    writeBack = true,
                )
            } catch (e: Exception) {
                Log.e(TAG, "bindUseCases: failed to bind camera", e)
                _cameraState.value = CameraState.Error("Failed to start camera: ${e.message}")
                return false
            }

            bound.let { cam ->
                // The live torch fact: observe the camera just bound. Every
                // flip (local enableTorch, a remote writer, the re-asserted
                // strength) republishes [torchState].
                observeTorchState(cam)
                // The live open fact: bind is bookkeeping, the observed
                // camera-state transitions are the readiness truth.
                observeCameraOpenState(cam)

                // Refresh the live RAW capability from the camera just
                // bound; if the plan's capability fold changed what should
                // have been built (first bind starts with no camera to
                // query), one self-correcting rebind lands the right config
                // after this bind finishes its control apply.
                _isRawCaptureSupported.value = rawCaptureSupported(cam.cameraInfo)
                boundPhotoConfig = PhotoCapturePlan.effective(photoConfig, _isRawCaptureSupported.value)
                if (boundPhotoConfig != effectivePhotoConfig) {
                    Log.d(TAG, "bindUseCases: RAW capability changed the effective photo config; rebinding")
                    correctivePhotoConfigRebind = true
                }
                cam.cameraInfo.zoomState.value?.let { zoom ->
                    // One ceiling: the device max clamped to the persistence
                    // bound, so pinch and settings re-apply agree.
                    _availableZoomRange.value = CameraSettings.effectiveZoomRange(zoom.maxZoomRatio)
                }
                val expState = cam.cameraInfo.exposureState
                _availableExposureRange.value = expState.exposureCompensationRange.lower..
                        expState.exposureCompensationRange.upper

                // Query sensor ISO and exposure time ranges for the selected lens
                try {
                    val cameraId = selectedLens?.physicalCameraId
                        ?: Camera2CameraInfo.from(cam.cameraInfo).cameraId
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    val chars = cameraManager.getCameraCharacteristics(cameraId)
                    chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { range ->
                        _availableIsoRange.value = range.lower..range.upper
                    }
                    chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { range ->
                        sensorExposureTimeRange = range.lower..range.upper
                    }
                    Log.d(TAG, "Sensor ranges for camera $cameraId: ISO=${_availableIsoRange.value}, exposure=${sensorExposureTimeRange}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query sensor ranges", e)
                }

                // Torch strength ceiling for the API 33+ per-level torch API.
                // Read from the BOUND camera's own characteristics — the same
                // camera whose flash unit backs enableTorch (the torch lives
                // on the logical camera, not the selected physical lens), so
                // the id is resolved the Camera2CameraInfo way rather than the
                // physical-lens fallback the sensor ranges above use. Null
                // (pre-33, no flash unit, missing key, failed query) means the
                // TorchStrengthPolicy ladder never calls the strength API and
                // plain enableTorch stays the whole story.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    cam.cameraInfo.hasFlashUnit()
                ) {
                    try {
                        val cameraManager =
                            context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val torchChars =
                            cameraManager.getCameraCharacteristics(Camera2CameraInfo.from(cam.cameraInfo).cameraId)
                        _torchStrengthMaxLevel.value =
                            torchChars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query torch strength max level", e)
                    }
                } else {
                    _torchStrengthMaxLevel.value = null
                }
                // The #16b triage line: a reported max of 1 means the HAL
                // drives no brightness ladder — any requested strength is a
                // plain on/off regardless of what the settings hold. One
                // INFO per bind pairs the ceiling with the persisted ask.
                Log.i(
                    TAG,
                    "Torch strength: device max=${_torchStrengthMaxLevel.value ?: "unsupported"}, " +
                        "requested=${activeSettings.torchStrength ?: "default"}",
                )
            }

            // CameraX drops control calls issued synchronously inside the
            // bind window (the camera2 session is still settling): a fresh
            // camera would come up at the platform default 1.0 zoom while
            // activeSettings says otherwise — the restart-restores-zoom
            // contract silently broke on rebinds. Defer the apply past the
            // settle instead (the bounded ladder from ControlsApplyPolicy —
            // the same choreography bindRecording runs for its own session).
            schedulePostBindControlsApply()

            // Deferred from the capability check above: rebuild once so the
            // ImageCapture matches the effective photo config. Its verdict
            // propagates: a failed corrective rebind has already published
            // Error, and the claimed Ready below would stomp that honest
            // state and report a working camera that is not bound.
            if (correctivePhotoConfigRebind) {
                correctivePhotoConfigRebind = false
                if (!rebindUseCases()) return false
            }

            // A successful bind CLAIMS camera readiness — CameraX 1.5's
            // bindToLifecycle returns before the camera opens, and a
            // while-in-use denial can still surface as an ERROR in the
            // camera state later. The service only used to post Ready from
            // initialize(), so a bind that recovered after a failure left
            // the state stuck on Error — the sticky UI rule then hid a
            // working camera behind the error screen, and the watchdog's
            // recovery verification (which reads Ready) failed even when
            // frames flowed. The claimed state stands until the observed
            // transitions correct it: [observeCameraOpenState] republishes
            // Ready on the actual open and Error on a policy denial.
            // [cameraOpenObserved] carries the difference between this
            // claim and a confirmed open.
            _cameraState.value = CameraState.Ready
            return true
        } catch (e: Exception) {
            Log.e(TAG, "bindUseCases: failed to bind camera", e)
            _cameraState.value = CameraState.Error("Failed to start camera: ${e.message}")
            return false
        }
    }

    /**
     * Bind the largest compatible use-case combination, largest first, from
     * the [combinations] ladder the caller passes — preview-start computes
     * it with [orderedCombinations], the recording bind gets it as the pure
     * [BindChoreography.recordingBindPlan]'s attempt list — then, via the
     * empty subset, the extra alone. One ladder shape for both session
     * producers so they fall back identically on constraint-limited
     * devices. [writeBack] updates the preview/capture/analysis fields from
     * whatever actually bound; recording passes false so [unbindRecording]
     * can restore the standard set.
     */
    private fun bindLargestCompatible(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        combinations: List<List<UseCase>>,
        writeBack: Boolean,
    ): Camera {
        var lastError: Exception? = null
        for (useCases in combinations) {
            if (useCases.isEmpty()) continue
            try {
                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    owner,
                    _selectedLens.value.selector,
                    *useCases.toTypedArray()
                )

                // Both variants bind a live session — the write-back=false
                // caller (bindRecording) must route its control applies and
                // observations at this instance too, not the dead predecessor.
                liveCamera = boundCamera
                bindGeneration++

                if (writeBack) {
                    preview = useCases.filterIsInstance<Preview>().firstOrNull()
                    imageCapture = useCases.filterIsInstance<ImageCapture>().firstOrNull()
                    imageAnalysis = useCases.filterIsInstance<ImageAnalysis>().firstOrNull()
                }

                Log.d(
                    TAG,
                    "bindLargestCompatible: bound ${useCases.joinToString { it.javaClass.simpleName }}"
                )
                return boundCamera
            } catch (e: Exception) {
                lastError = e
                Log.w(
                    TAG,
                    "bindLargestCompatible: failed for ${useCases.joinToString { it.javaClass.simpleName }}",
                    e
                )
            }
        }

        throw lastError ?: IllegalStateException("No compatible camera use case combination found")
    }

    private fun clearBoundUseCases() {
        stopTorchObservation()
        stopCameraOpenObservation()
        preview?.surfaceProvider = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        liveCamera = null
        boundPhotoConfig = null
        // Unbound camera = torch dark: the live fact dies with the session,
        // and the driven strength goes with it (TorchState's KDoc).
        drivenTorchStrengthLevel = null
        torchDrivenOn = false
        _torchState.value = TorchState.Off
    }

    /**
     * The device RAW verdict: ImageCaptureCapabilities' supported output
     * formats on the bound camera. Fail-closed — a capability query error
     * means no RAW.
     */
    private fun rawCaptureSupported(info: CameraInfo): Boolean = try {
        ImageCapture.getImageCaptureCapabilities(info)
            .supportedOutputFormats
            .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
    } catch (e: Exception) {
        Log.w(TAG, "RAW capability query failed", e)
        false
    }

    fun switchCamera() {
        val lenses = _availableLenses.value
        if (lenses.isEmpty()) {
            // Fallback to simple front/back toggle — the pure LensInventory's.
            // One SelectedLens write; the index is meaningless while the
            // inventory is empty and is left as-is.
            val (selector, frontFacing) = LensInventory.fallbackSelector(_selectedLens.value.isFront)
            _selectedLens.value = _selectedLens.value.copy(selector = selector, isFront = frontFacing)
            val previewView = sessionDemand.currentPreviewView
            if (previewView != null) {
                startPreview(previewView)
            } else {
                // Headless switch (preview hidden): rebind to the new selector
                // only while the camera is free — one arbiter consult.
                when (
                    val action = CameraSessionArbiter.decide(
                        demandState(CameraSessionArbiter.Trigger.RebindIfFree)
                    )
                ) {
                    is CameraSessionArbiter.BindingAction -> executeBinding(action)
                    else -> {}
                }
            }
            return
        }

        // Cycle to next lens
        selectLens(LensInventory.nextIndex(_selectedLens.value.index, lenses.size))
    }

    private val analysisExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "FrameAnalysis").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }
    }

    private var consecutiveFrameErrors = 0
    private var lastFrameErrorTime = 0L

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val cropRect = imageProxy.cropRect
            val width = cropRect.width()
            val height = cropRect.height()
            val rotation = imageProxy.imageInfo.rotationDegrees
            // The freshest sensor-to-display rotation the analysis stream saw —
            // the capture/recording target-rotation computation reads it, so a
            // locked-orientation still matches the streams even when the
            // display's own rotation is not what the mount suggests.
            lastSensorRotationDegrees = rotation
            val yuvData = yuvToNv21(imageProxy)
            if (yuvData != null) {
                consecutiveFrameErrors = 0
                frameListener?.invoke(yuvData, width, height, rotation)
                proToolsFrameListener?.invoke(yuvData, width, height, rotation)
            }
        } catch (e: Exception) {
            // The window/threshold verdicts are the pure FrameErrorPolicy's;
            // this callback only keeps the counter and the last-error stamp.
            val now = System.currentTimeMillis()
            if (FrameErrorPolicy.streakExpired(now, lastFrameErrorTime)) {
                consecutiveFrameErrors = 0
            }
            consecutiveFrameErrors++
            Log.e(TAG, "Frame processing error #${consecutiveFrameErrors}", e)
            // Consulted against the PREVIOUS stamp so the policy's window
            // verdict is meaningful; the stamp advances after the verdict.
            if (FrameErrorPolicy.shouldRecover(consecutiveFrameErrors, now, lastFrameErrorTime)) {
                Log.w(TAG, "Too many frame errors, attempting recovery")
                consecutiveFrameErrors = 0
                triggerAutoRecovery()
            }
            lastFrameErrorTime = now
        } finally {
            imageProxy.close()
        }
    }

    private fun triggerAutoRecovery() {
        try {
            when (
                val action = CameraSessionArbiter.decide(
                    demandState(CameraSessionArbiter.Trigger.RebindIfFree)
                )
            ) {
                is CameraSessionArbiter.BindingAction -> {
                    executeBinding(action)
                    Log.d(TAG, "Auto-recovery: rebind use cases attempted")
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-recovery: rebind failed", e)
            _cameraState.value = CameraState.Error("Camera error, please restart the app")
        }
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val crop = image.cropRect

        return YuvConverter.yuvToNv21(
            yBuffer = yPlane.buffer,
            uBuffer = uPlane.buffer,
            vBuffer = vPlane.buffer,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            width = crop.width(),
            height = crop.height(),
            cropLeft = crop.left,
            cropTop = crop.top,
        )
    }

    private var pendingResolution: Size? = null
    private var currentResolution: Size = Size(1920, 1080)

    /** The analysis stream's latest sensor-to-display rotation (degrees); the default covers the pre-first-frame bind. */
    @Volatile private var lastSensorRotationDegrees = 0

    /**
     * The target rotation stills and recordings bind with so the file carries
     * exactly the effective output rotation the streams show (issue #6): the
     * pure [OutputRotationPolicy.targetRotationDegrees] inversion, fed by the
     * current settings, the freshest sensor rotation, and the display's
     * rotation. Recomputed on every bind — a locked mount keeps its constant,
     * a handheld device picks the orientation up at the next rebind.
     */
    fun captureTargetRotation(): Int {
        val settings = activeSettings
        val effective = OutputRotationPolicy.effectiveRotation(
            lastSensorRotationDegrees,
            settings.outputRotation,
            settings.orientationLocked,
        )
        val displayDegrees = OutputRotationPolicy.displayRotationDegrees(currentDisplayRotation())
        return OutputRotationPolicy.surfaceRotationFromDegrees(
            OutputRotationPolicy.targetRotationDegrees(lastSensorRotationDegrees, displayDegrees, effective)
        )
    }

    private fun currentDisplayRotation(): Int = try {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0
    } catch (_: Exception) {
        android.view.Surface.ROTATION_0
    }

    /** True when the two settings that steer the capture target rotation moved between the bound and requested values. */
    private fun outputRotationChanged(bound: CameraSettings, requested: CameraSettings): Boolean =
        bound.outputRotation != requested.outputRotation || bound.orientationLocked != requested.orientationLocked

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    /**
     * The streaming analysis aspect, applied by the Settings Applier when the
     * encode resolution's aspect moves. Mirrors the output-rotation seam: a
     * free camera rebinds now, a busy one picks the change up at the next
     * natural rebind — [getStreamingAnalysisResolution] reads the field live,
     * so nothing needs parking.
     */
    suspend fun setStreamAspectRatio(aspect: StreamAspect) {
        if (aspect == streamAspect) return
        streamAspect = aspect
        when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.RebindIfFree)
            )
        ) {
            is CameraSessionArbiter.BindingAction -> {
                withContext(Dispatchers.Main) {
                    executeBinding(action)
                }
            }
            else -> Log.d(TAG, "setStreamAspectRatio: deferring $aspect rebind until next active session")
        }
    }

    /**
     * Wire the camera-settings save path the untouched-write torch
     * convergence persists through. Called by the Settings Applier at start
     * — the one owner of "persisted settings → runtime" — so a converged
     * document reaches the store through the existing save every writer
     * uses.
     */
    fun setSettingsPersister(persister: suspend (CameraSettings) -> Unit) {
        persistSettings = persister
    }

    /**
     * The wholesale settings apply — and the ONE place the "did this write
     * touch torch?" verdict is computed, because this is the only seam that
     * sees both the previously applied snapshot ([activeSettings]) and the
     * new one. The torch pair ([CameraSettings.torchEnabled] +
     * [CameraSettings.torchStrength]) decides:
     *
     *  - FIRST apply (nothing committed yet — cold start): everything
     *    applies verbatim, so a restart re-lights from the stored intent.
     *  - [torchIntent] declared (the automation `torch` action, through the
     *    applier's intent-honored apply): the write IS a torch request — it
     *    applies and commits as written, LED included, even when the pair
     *    already equals the previously applied snapshot. A snapshot can
     *    carry the requested value while the LED sits opposite (a live-only
     *    flip after an earlier convergence): the pair diff then reads
     *    "untouched" and the untouched branch would converge the request
     *    away toward the live fact instead of moving the LED (the
     *    0.2.3-beta.1 on-device repro, issue #21). Only an explicit intent
     *    escapes that ambiguity — a carry whose pair happens to equal the
     *    snapshot must still converge, or an untouched save would stomp a
     *    live-only lit LED (the #16 guarantee below).
     *  - Torch pair MOVED: an explicit torch write — the in-app quick-setting
     *    toggle, a scheduler torch action, a test-restore undo — is applied
     *    and committed as written, LED included.
     *  - Torch pair UNCHANGED: this write does not touch torch, so the
     *    stored intent converges to the live LED fact ([isTorchOn]) through
     *    ONE DataStore write on the wired [setSettingsPersister] save path —
     *    and the LED-driving below runs per the CONVERGED value, because
     *    driving per the raw value would extinguish a live-only lit LED (the
     *    web torch route and the deterrence coordinator diverge from the
     *    stored pair by design).
     *
     * This replaces the per-writer convergence protocol — the since-deleted
     * `CameraSettings.convergeTorch` plus a transform-diff verdict in
     * CameraSettingsEditor, a live-fact snapshot in SettingsWebHandler's
     * camera-section rebuild, and an action-type verdict in
     * AutomationActionExecutor: three writers each re-deriving the same
     * invariant, and three consecutive fixes to it (22fe040, e84b36d,
     * 565a690; issue #16's dashboard-save-kills-the-LED repro) before the
     * verdict moved to its single owner here. A write built from a stale
     * base can still read as untouched while the LED has since been flipped
     * live-only; the convergence then restates the live fact, which is the
     * same document the next untouched write would store anyway.
     *
     * The LED-driving side is untouched: [applyCameraControls]' torch branch
     * re-asserts torch on every controls apply (what makes torch survive
     * camera rebinds) and honors [torchDrivenOn] — never extinguishing a
     * torch lit by the system flashlight tile.
     */
    suspend fun applySettings(settings: CameraSettings, torchIntent: Boolean? = null) {
        val boundSettings = activeSettings
        val torchTouched = !settingsEverApplied ||
            torchIntent != null ||
            boundSettings.torchEnabled != settings.torchEnabled ||
            boundSettings.torchStrength != settings.torchStrength
        val effective = if (torchTouched) {
            settings
        } else {
            val converged = settings.copy(torchEnabled = isTorchOn())
            if (converged != settings) {
                val persister = persistSettings
                if (persister == null) {
                    Log.w(TAG, "applySettings: no settings persister wired; torch convergence not persisted")
                } else {
                    Log.d(
                        TAG,
                        "applySettings: torch untouched — converging stored intent to the live fact (on=${converged.torchEnabled})",
                    )
                    persister(converged)
                }
            }
            converged
        }
        settingsEverApplied = true
        activeSettings = effective
        if (outputRotationChanged(boundSettings, effective)) {
            // The ImageCapture bakes the output rotation in at bind time
            // (target rotation); a change rebinds through the same arbiter
            // consult the resolution seam rides — a busy camera picks the
            // change up at the next natural rebind, since the builder reads
            // [activeSettings] live.
            when (
                val action = CameraSessionArbiter.decide(
                    demandState(CameraSessionArbiter.Trigger.RebindIfFree)
                )
            ) {
                is CameraSessionArbiter.BindingAction -> {
                    withContext(Dispatchers.Main) {
                        executeBinding(action)
                    }
                }
                else -> Log.d(TAG, "applySettings: deferring output-rotation rebind until next active session")
            }
        }
        if (effective.resolution.size != currentResolution) {
            currentResolution = effective.resolution.size
            // One arbiter consult: rebind now while the camera is free, park
            // the change otherwise (ResolutionApplyPolicy beneath it).
            when (
                val action = CameraSessionArbiter.decide(
                    demandState(CameraSessionArbiter.Trigger.RebindIfFree)
                )
            ) {
                is CameraSessionArbiter.BindingAction -> {
                    withContext(Dispatchers.Main) {
                        executeBinding(action)
                    }
                    applyCameraControls(effective, forceFocusReapply = false)
                    return
                }
                else -> {
                    pendingResolution = effective.resolution.size
                    Log.d(TAG, "applySettings: deferring resolution change until next active session")
                }
            }
        }

        applyCameraControls(effective, forceFocusReapply = false)
    }

    fun applyPendingResolution() {
        val res = pendingResolution ?: return
        // Consult BEFORE clearing: a Noop verdict (an exclusive session
        // started between the park and this flush) must keep the change
        // parked — a later resume's flush re-reads it. Clearing first would
        // silently drop the parked change, and [currentResolution] must stay
        // on the bound size so the settings path keeps seeing the change as
        // pending.
        when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.RebindIfFree)
            )
        ) {
            is CameraSessionArbiter.BindingAction -> {
                pendingResolution = null
                currentResolution = res
                executeBinding(action)
                Log.d(TAG, "applyPendingResolution: applied deferred resolution $res")
            }
            else -> Log.d(TAG, "applyPendingResolution: camera busy; resolution $res stays parked")
        }
    }

    /**
     * The one post-bind controls-apply scheduler, shared by every session
     * producer ([bindUseCases] and [bindRecording]): one apply per
     * [ControlsApplyPolicy] rung, each re-asserting [activeSettings] with
     * [forceFocusReapply] so a fresh bind's metering state matches too. Each
     * rung carries the [bindGeneration] it was scheduled under, and the fire
     * time consults [BindChoreography.rungAction]: a rung landing after a
     * newer bind drops itself — the new bind schedules its own rungs, and
     * this rung's force-focus reapply would otherwise cancel a tap-to-focus
     * on the session that replaced it — while a surviving apply still
     * resolves its target at fire time through [liveCamera], so a rung
     * landing after an unbind is a caught-exception no-op.
     */
    private fun schedulePostBindControlsApply() {
        val generation = bindGeneration
        BindChoreography.postBindRungs(generation, ControlsApplyPolicy.applyDelaysMs()).forEach { rung ->
            controlsHandler.postDelayed({
                if (BindChoreography.rungAction(rung, bindGeneration) ==
                    BindChoreography.RungAction.ApplyControls
                ) {
                    applyCameraControls(activeSettings, forceFocusReapply = true)
                }
            }, rung.delayMs)
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyCameraControls(settings: CameraSettings, forceFocusReapply: Boolean) {
        // The live session is the target — during a recording bind [liveCamera]
        // carries the recording session's instance, and every settings emission
        // (dashboard PUT via the Settings Applier, on-device editor) must reach
        // the session actually running.
        val cam = liveCamera ?: return

        try {
            cam.cameraControl.setZoomRatio(clampZoom(settings.zoomRatio, cam))
        } catch (e: Exception) {
            Log.w(TAG, "Zoom failed", e)
        }

        try {
            if (cam.cameraInfo.hasFlashUnit()) {
                if (settings.torchEnabled) {
                    // Per-level torch strength (API 33+): the strength call
                    // itself switches the flash on, so it replaces — rather
                    // than follows — enableTorch(true). Calling enableTorch
                    // first flashed the default level and then stepped it.
                    // The policy's null verdict (unsupported SDK, single-level
                    // flash, unknown max) or a failed drive falls back to the
                    // binary torch, so light always reaches the subject.
                    val levelApplied = settings.torchStrength != null &&
                        applyTorchStrength(cam, settings.torchStrength)
                    if (!levelApplied) {
                        drivenTorchStrengthLevel = null
                        cam.cameraControl.enableTorch(true)
                    }
                    torchDrivenOn = true
                } else if (torchDrivenOn) {
                    // Only extinguish a torch this service lit — see
                    // [torchDrivenOn]. The level this apply drove feeds
                    // [torchState] and dies with the off state.
                    drivenTorchStrengthLevel = null
                    cam.cameraControl.enableTorch(false)
                    torchDrivenOn = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Torch failed", e)
        }

        try {
            val expState = cam.cameraInfo.exposureState
            // Clamp-and-skip is the plan's decision; null means the device
            // already sits on the requested index.
            CameraControlPlan.exposureIndex(
                settings,
                expState.exposureCompensationRange.lower,
                expState.exposureCompensationRange.upper,
                expState.exposureCompensationIndex,
            )?.let { cam.cameraControl.setExposureCompensationIndex(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Exposure compensation failed", e)
        }

        // Center AF/AE metering only re-fires when the focus-relevant fields
        // moved (or after a fresh bind): a plain settings apply — every
        // slider tick reaches here twice — must not cancel a deliberate
        // tap-to-focus. The re-fire verdict lives in FocusApplyPolicy; which
        // metering fires per focus mode lives in CameraControlPlan.
        if (forceFocusReapply || FocusApplyPolicy.needsReapply(lastFocusApplied, settings)) {
            try {
                startMetering(
                    cam,
                    0.5f,
                    0.5f,
                    CameraControlPlan.meteringOnApply(settings.focusMode),
                )
                lastFocusApplied = settings
            } catch (e: Exception) {
                Log.w(TAG, "Focus failed", e)
            }
        }

        // The decisions live in the pure, tested CameraControlPlan; this is
        // translation only. Each group applies in its own call: a single
        // unsupported key (an OEM missing optical stabilization, a manual
        // shutter outside the sensor's range) must not drop every other
        // control with it — the old one-bundle try/catch did exactly that.
        val plan = CameraControlPlan.from(settings, _availableIsoRange.value, sensorExposureTimeRange)
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)

        applyCaptureOptions(camera2Control, "fps") {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(plan.fpsLower, plan.fpsUpper)
            )
        }

        applyCaptureOptions(camera2Control, "video stabilization") {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (plan.videoStabilizationOn) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                },
            )
        }

        applyCaptureOptions(camera2Control, "optical stabilization") {
            setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (plan.opticalStabilizationOn) {
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                } else {
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                },
            )
        }

        applyCaptureOptions(camera2Control, "scene/ae") {
            setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, plan.sceneMode)
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, plan.aeMode)
            plan.aeLock?.let {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, it)
            }
        }

        if (plan.sensorSensitivity != null || plan.sensorExposureTimeNs != null) {
            applyCaptureOptions(camera2Control, "manual exposure") {
                plan.sensorSensitivity?.let {
                    setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
                }
                plan.sensorExposureTimeNs?.let {
                    setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
                }
            }
        }

        applyCaptureOptions(camera2Control, "white balance") {
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, plan.awbMode)
        }

        // Manual WB is conveyed by AWB OFF above; the Kelvin value maps to
        // sensor gains through the pure KelvinGainsPolicy (blackbody
        // approximation) applied in TRANSFORM_MATRIX mode with an identity
        // color transform, so the gains do the correction.
        plan.colorTemperatureKelvin?.let { kelvin ->
            val gains = KelvinGainsPolicy.gainsFor(kelvin)
            applyCaptureOptions(camera2Control, "kelvin gains") {
                setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(gains[0], gains[1], gains[2], gains[3]),
                )
                setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    android.hardware.camera2.params.ColorSpaceTransform(
                        arrayOf(
                            android.util.Rational(1, 1), android.util.Rational(0, 1), android.util.Rational(0, 1),
                            android.util.Rational(0, 1), android.util.Rational(1, 1), android.util.Rational(0, 1),
                            android.util.Rational(0, 1), android.util.Rational(0, 1), android.util.Rational(1, 1),
                        ),
                    ),
                )
            }
            Log.d(TAG, "Manual white balance Kelvin: $kelvin → gains ${gains.joinToString()}")
        }

        if (plan.afMode != null) {
            applyCaptureOptions(camera2Control, "autofocus") {
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, plan.afMode)
                plan.lensFocusDistance?.let {
                    setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                }
            }
        }
    }

    /**
     * Applies one capture-option group in isolation. CameraX rejects the
     * whole options bundle when one key is unsupported on the device, so
     * grouping (and catching) per concern keeps an OEM gap from silently
     * disabling unrelated controls.
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private inline fun applyCaptureOptions(
        camera2Control: Camera2CameraControl,
        what: String,
        configure: CaptureRequestOptions.Builder.() -> Unit,
    ) {
        runCatching {
            val builder = CaptureRequestOptions.Builder()
            builder.apply(configure)
            camera2Control.setCaptureRequestOptions(builder.build())
        }.onFailure { Log.w(TAG, "Advanced settings ($what) failed", it) }
    }

    /**
     * The strength half of the torch apply: the API 33+
     * [android.hardware.camera2.CameraManager.turnOnTorchWithStrengthLevel]
     * path, translated by [TorchStrengthPolicy] (null verdict = skip
     * silently). Runs on the BOUND camera's camera2 id — the same camera
     * whose flash unit backs enableTorch — resolved through
     * Camera2CameraInfo. The CameraManager-level API needs no bound session.
     * Returns true only when the level reached the flash; on any skip or
     * failure the caller falls back to the binary torch, so light always
     * reaches the subject.
     */
    private fun applyTorchStrength(cam: Camera, requestedStrength: Int?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val level = TorchStrengthPolicy.effectiveLevel(
            requestedStrength,
            _torchStrengthMaxLevel.value,
            Build.VERSION.SDK_INT,
        ) ?: return false
        return runCatching {
            val torchCameraId = Camera2CameraInfo.from(cam.cameraInfo).cameraId
            val cameraManager =
                context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cameraManager.turnOnTorchWithStrengthLevel(torchCameraId, level)
            // The level that reached the flash is the torch fact's driven
            // strength (the torchState flow's On payload); a failed drive
            // leaves the previous value in place.
            drivenTorchStrengthLevel = level
            // Read-back verification (the check CTS performs): diagnostic
            // only — a mismatch is logged, never retried or re-derived, since
            // the level re-asserts on the next settings apply anyway. HALs
            // that lag the set answer with the stale level; the log line is
            // how we find out on real devices.
            val applied = cameraManager.getTorchStrengthLevel(torchCameraId)
            if (applied != level) {
                Log.w(TAG, "Torch strength read-back mismatch (set=$level device=$applied)")
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "Torch strength failed (level=$level)", e)
            false
        }
    }

    fun onActivityResume() {
        updateDemand { it.copy(isActivityForeground = true) }
        // One arbiter consult. Its demand input is previewRequested ALONE
        // (see the arbiter) — keep-alive-only sessions are not restored here;
        // that asymmetry is preserved, not fixed.
        when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.ActivityResumed)
            )
        ) {
            CameraSessionArbiter.CameraSessionAction.ApplyPendingResolution -> {
                applyPendingResolution()
                Log.d(TAG, "onActivityResume: restored preview")
            }
            is CameraSessionArbiter.BindingAction -> {
                executeBinding(action)
                Log.d(TAG, "onActivityResume: restored preview")
            }
            else -> {}
        }
    }

    fun onActivityStop() {
        updateDemand { it.copy(isActivityForeground = false) }
        when (
            CameraSessionArbiter.decide(demandState(CameraSessionArbiter.Trigger.ActivityStopping))
        ) {
            CameraSessionArbiter.CameraSessionAction.DetachSurface -> {
                preview?.surfaceProvider = null
                Log.d(TAG, "onActivityStop: detached preview surface for background operation")
            }
            else -> {}
        }
    }

    fun release() {
        // Final teardown — device-bound bookkeeping, no gate to consult: no
        // state exempts releasing the camera.
        unregisterCameraAddedWait()
        cameraProvider?.unbindAll()
        cameraProvider = null
        clearBoundUseCases()
        _cameraState.value = CameraState.Idle
        _isRawCaptureSupported.value = false
        // Demand reset. PRESERVED un-paired combo — a known, visible decision,
        // not something to "fix" here: previewRequested, exclusiveSessionRefCount
        // and currentPreviewView are cleared but keepAliveRefCount is NOT.
        // Stale keep-alive demand survives teardown (a post-release acquire
        // would inherit the old count and keep the KeepAliveLifecycle
        // considered active via getEffectiveLifecycleOwner). The behavior is
        // kept EXACTLY as the loose fields left it; pairing the reset is a
        // deliberate behavior change for whoever takes it.
        updateDemand {
            it.copy(previewRequested = false, exclusiveSessionRefCount = 0, currentPreviewView = null)
        }
        lastFocusApplied = null
        try {
            analysisExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CameraService"

        internal const val MAX_ANALYSIS_WIDTH = 1280
        internal const val MAX_ANALYSIS_HEIGHT = 720
        internal const val FOUR_THREE_ANALYSIS_WIDTH = 960
        internal const val FOUR_THREE_ANALYSIS_HEIGHT = 720

        /** Fail-fast line for bind attempts before the CAMERA permission exists. */
        private const val CAMERA_PERMISSION_REQUIRED_MESSAGE =
            "Camera permission is not granted. Grant it, then retry."

        /**
         * The observed-camera-state line for ERROR_CAMERA_FATAL_ERROR —
         * camera2's ERROR_CAMERA_SERVICE, whose documented answer is a device
         * reboot (CameraX taxonomy, self-healing §5; blueprint P0 item 4).
         * The [CameraState.Error.recovery] verdict carries the structured
         * REBOOT_REQUIRED alongside, so consumers need not string-match; the
         * message is the human-readable form the dashboard renders today.
         */
        const val CAMERA_FATAL_MESSAGE = "Camera fatal error — reboot the device"
    }
}

/**
 * The combination ladder, largest first: every subset of [base]
 * (optionally plus [extra]). Shared by preview-start and recording-start
 * so both fall back identically on constraint-limited devices. Pure list
 * math — the provider interaction stays in [CameraService]. The canonical
 * implementation lives in the pure bind choreography
 * ([BindChoreography.orderedCombinations], whose recording plan emits this
 * ladder in slot terms); this camera-package re-export keeps the service's
 * call sites and the camera-package math tests unchanged.
 */
internal fun <T> orderedCombinations(base: List<T>, extra: T?): List<List<T>> =
    BindChoreography.orderedCombinations(base, extra)

/**
 * The streaming analysis size: small captures pass through; large captures
 * drop to the desired aspect's window — 960x720 for 4:3 (the full sensor
 * width), the 16:9 ceiling otherwise. The aspect is the encode resolution's,
 * not the capture's: a 4:3 encode tier must not crop 16:9 analysis frames.
 * Pure ints — the `Size` wrapping stays at the call site.
 */
internal fun analysisSizeFor(
    captureWidth: Int,
    captureHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    aspect: StreamAspect = StreamAspect.WIDE_16_9,
): Pair<Int, Int> {
    if (captureWidth <= maxWidth && captureHeight <= maxHeight) {
        return captureWidth to captureHeight
    }

    return when (aspect) {
        StreamAspect.WIDE_16_9 -> maxWidth to maxHeight
        StreamAspect.STANDARD_4_3 ->
            CameraService.FOUR_THREE_ANALYSIS_WIDTH to CameraService.FOUR_THREE_ANALYSIS_HEIGHT
    }
}
