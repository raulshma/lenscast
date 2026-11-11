package com.raulshma.lenscast.camera


import android.content.Context
import android.hardware.camera2.CameraCharacteristics
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
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraControlPlan
import com.raulshma.lenscast.camera.model.KelvinGainsPolicy
import com.raulshma.lenscast.camera.model.CameraState
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
    private var camera: Camera? = null
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

    private var activeSettings = CameraSettings()

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
        val cam = camera ?: run {
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
        val cam = camera ?: return false
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
    fun liveZoomRatio(): Float? = camera?.cameraInfo?.zoomState?.value?.zoomRatio

    fun setTorchEnabled(on: Boolean): Boolean {
        val cam = camera ?: return false
        return try {
            if (!cam.cameraInfo.hasFlashUnit()) {
                Log.w(TAG, "setTorchEnabled: no flash unit")
                return false
            }
            cam.cameraControl.enableTorch(on)
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
            if (torchStateObserver === observer && camera === cam) {
                cam.cameraInfo.torchState.observeForever(observer)
            }
        }
    }

    /**
     * Detach the torch observation from the bound camera's live data. The
     * live data reference is captured before posting: the camera field may
     * already be gone (unbind) by the time this lands on the main thread.
     */
    private fun stopTorchObservation() {
        val observer = torchStateObserver ?: return
        val liveData = camera?.cameraInfo?.torchState
        torchStateObserver = null
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
     */
    fun rebindUseCases() {
        when (
            val action = CameraSessionArbiter.decide(
                demandState(CameraSessionArbiter.Trigger.RebindCheck)
            )
        ) {
            CameraSessionArbiter.CameraSessionAction.Noop ->
                Log.d(TAG, "rebindUseCases: skipped while an exclusive session is active")
            is CameraSessionArbiter.BindingAction -> executeBinding(action)
            else -> {}
        }
    }

    /**
     * The one action → CameraX execution for the binding verdicts, shared by
     * every gated entry point: ensure the provider — only actions that use it
     * reach here, the same passes the old inline gates allowed through — then
     * unbind or bind into the effective lifecycle owner.
     */
    private fun executeBinding(action: CameraSessionArbiter.BindingAction) {
        val provider = ensureCameraProviderAvailable()
        Log.d(TAG, "rebindUseCases: provider=${provider != null}, refCount=${sessionDemand.keepAliveRefCount}, previewRequested=${sessionDemand.previewRequested}, lifecycleOwner=${lifecycleOwner != null}")

        if (provider == null) {
            Log.w(TAG, "rebindUseCases: cameraProvider is null, cannot rebind")
            return
        }

        when (action) {
            CameraSessionArbiter.BindingAction.Unbind -> {
                provider.unbindAll()
                clearBoundUseCases()
                Log.d(TAG, "rebindUseCases: unbound camera because there is no active demand")
            }
            CameraSessionArbiter.BindingAction.Rebind -> {
                val owner = getEffectiveLifecycleOwner()
                if (owner == null) {
                    Log.w(TAG, "rebindUseCases: no lifecycle owner available and no keep-alive, cannot rebind")
                    return
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
     * getters, or the combination ladder directly.
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

        val base = listOfNotNull(preview, imageCapture, imageAnalysis)
        return try {
            // No write-back: the preview-side fields must keep their values so
            // [unbindRecording] can restore the standard binding.
            bindLargestCompatible(provider, owner, base, extra = videoCapture, writeBack = false)
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

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
    ) {
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
            provider.unbindAll()
            try {
                camera = bindLargestCompatible(
                    provider,
                    owner,
                    base = listOfNotNull(preview, imageCapture, imageAnalysis),
                    extra = null,
                    writeBack = true,
                )
            } catch (e: Exception) {
                Log.e(TAG, "bindUseCases: failed to bind camera", e)
                _cameraState.value = CameraState.Error("Failed to start camera: ${e.message}")
                return
            }

            camera?.let { cam ->
                // The live torch fact: observe the camera just bound. Every
                // flip (local enableTorch, a remote writer, the re-asserted
                // strength) republishes [torchState].
                observeTorchState(cam)

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
            }

            // CameraX drops control calls issued synchronously inside the
            // bind window (the camera2 session is still settling): a fresh
            // camera would come up at the platform default 1.0 zoom while
            // activeSettings says otherwise — the restart-restores-zoom
            // contract silently broke on rebinds. Defer the apply past the
            // settle instead.
            controlsHandler.postDelayed({
                applyCameraControls(activeSettings, forceFocusReapply = true)
            }, CONTROLS_APPLY_DELAY_MS)

            // Deferred from the capability check above: rebuild once so the
            // ImageCapture matches the effective photo config.
            if (correctivePhotoConfigRebind) {
                correctivePhotoConfigRebind = false
                rebindUseCases()
            }

            // A successful bind IS camera readiness. The service only used to
            // post Ready from initialize(), so a bind that recovered after a
            // failure left the state stuck on Error — the sticky UI rule then
            // hid a working camera behind the error screen, and the
            // watchdog's recovery verification (which reads Ready) failed
            // even when frames flowed.
            _cameraState.value = CameraState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "bindUseCases: failed to bind camera", e)
            _cameraState.value = CameraState.Error("Failed to start camera: ${e.message}")
        }
    }

    /**
     * Bind the largest compatible use-case combination, largest first: every
     * subset of [base] (optionally plus [extra]), then — via the empty subset —
     * [extra] alone. One ladder for preview-start and recording-start so both
     * fall back identically on constraint-limited devices. [writeBack] updates
     * the preview/capture/analysis fields from whatever actually bound;
     * recording passes false so [unbindRecording] can restore the standard set.
     */
    private fun bindLargestCompatible(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        base: List<UseCase>,
        extra: UseCase?,
        writeBack: Boolean,
    ): Camera {
        val combinations = orderedCombinations(base, extra)

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
        preview?.surfaceProvider = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        camera = null
        boundPhotoConfig = null
        // Unbound camera = torch dark: the live fact dies with the session,
        // and the driven strength goes with it (TorchState's KDoc).
        drivenTorchStrengthLevel = null
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

    suspend fun applySettings(settings: CameraSettings) {
        val boundSettings = activeSettings
        activeSettings = settings
        if (outputRotationChanged(boundSettings, settings)) {
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
        if (settings.resolution.size != currentResolution) {
            currentResolution = settings.resolution.size
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
                    applyCameraControls(settings, forceFocusReapply = false)
                    return
                }
                else -> {
                    pendingResolution = settings.resolution.size
                    Log.d(TAG, "applySettings: deferring resolution change until next active session")
                }
            }
        }

        applyCameraControls(settings, forceFocusReapply = false)
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

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyCameraControls(settings: CameraSettings, forceFocusReapply: Boolean) {
        val cam = camera ?: return

        try {
            cam.cameraControl.setZoomRatio(clampZoom(settings.zoomRatio, cam))
        } catch (e: Exception) {
            Log.w(TAG, "Zoom failed", e)
        }

        try {
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(settings.torchEnabled)
                // Bookkeeping only — the re-assert above is unchanged: the
                // level this apply drives feeds [torchState] (null = device
                // default), and a torch-off apply — or an on-without-level —
                // retires any previous level.
                drivenTorchStrengthLevel = null
                // Per-level torch strength (API 33+): driven ONLY while the
                // torch should be ON — turnOnTorchWithStrengthLevel switches
                // the flash on as a side effect when it was off, so a
                // torch-off apply never touches it. Riding this seam means the
                // level re-asserts on every settings emission and after every
                // rebind, exactly like the binary torch above. The policy's
                // null verdict (unsupported SDK, single-level flash, unknown
                // max) skips silently — enableTorch already did everything
                // the device can do.
                if (settings.torchEnabled && settings.torchStrength != null) {
                    applyTorchStrength(cam, settings.torchStrength)
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

        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            // The decisions live in the pure, tested CameraControlPlan; this is
            // translation only.
            val plan = CameraControlPlan.from(settings, _availableIsoRange.value, sensorExposureTimeRange)

            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(plan.fpsLower, plan.fpsUpper)
            )

            if (plan.videoStabilizationOn) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (plan.opticalStabilizationOn) {
                builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }

            builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, plan.sceneMode)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, plan.aeMode)
            plan.aeLock?.let {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, it)
            }
            plan.sensorSensitivity?.let {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            plan.sensorExposureTimeNs?.let {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, plan.awbMode)
            // Manual WB is conveyed by AWB OFF above; the Kelvin value maps to
            // sensor gains through the pure KelvinGainsPolicy (blackbody
            // approximation) applied in TRANSFORM_MATRIX mode with an identity
            // color transform, so the gains do the correction.
            plan.colorTemperatureKelvin?.let { kelvin ->
                val gains = KelvinGainsPolicy.gainsFor(kelvin)
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(gains[0], gains[1], gains[2], gains[3]),
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    android.hardware.camera2.params.ColorSpaceTransform(
                        arrayOf(
                            android.util.Rational(1, 1), android.util.Rational(0, 1), android.util.Rational(0, 1),
                            android.util.Rational(0, 1), android.util.Rational(1, 1), android.util.Rational(0, 1),
                            android.util.Rational(0, 1), android.util.Rational(0, 1), android.util.Rational(1, 1),
                        ),
                    ),
                )
                Log.d(TAG, "Manual white balance Kelvin: $kelvin → gains ${gains.joinToString()}")
            }

            if (plan.afMode != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, plan.afMode)
                plan.lensFocusDistance?.let {
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                }
            }

            camera2Control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Advanced settings failed", e)
        }
    }

    /**
     * The strength half of the torch apply: the API 33+
     * [android.hardware.camera2.CameraManager.turnOnTorchWithStrengthLevel]
     * path, translated by [TorchStrengthPolicy] (null verdict = skip
     * silently). Runs on the BOUND camera's camera2 id — the same camera
     * whose flash unit backs enableTorch — resolved through
     * Camera2CameraInfo. The CameraManager-level API needs no bound session,
     * but failures (unknown id, hardware quirk, the torch having been killed
     * underneath us) are logged and swallowed: the binary torch above already
     * put light on the subject, strength is the refinement.
     */
    private fun applyTorchStrength(cam: Camera, requestedStrength: Int?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val level = TorchStrengthPolicy.effectiveLevel(
            requestedStrength,
            _torchStrengthMaxLevel.value,
            Build.VERSION.SDK_INT,
        ) ?: return
        runCatching {
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
        }.onFailure { e ->
            Log.w(TAG, "Torch strength failed (level=$level)", e)
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

        /**
         * Post-bind settle window for deferred camera-control applies: CameraX
         * drops zoom/torch calls issued synchronously inside the bind window
         * on some devices (fresh camera then runs at platform defaults even
         * though activeSettings says otherwise), while the same apply a beat
         * later lands. Empirically verified on the DRG (Nokia 6.1 Plus).
         */
        private const val CONTROLS_APPLY_DELAY_MS = 400L
        internal const val MAX_ANALYSIS_WIDTH = 1280
        internal const val MAX_ANALYSIS_HEIGHT = 720
        internal const val FOUR_THREE_ANALYSIS_WIDTH = 960
        internal const val FOUR_THREE_ANALYSIS_HEIGHT = 720

        /** Fail-fast line for bind attempts before the CAMERA permission exists. */
        private const val CAMERA_PERMISSION_REQUIRED_MESSAGE =
            "Camera permission is not granted. Grant it, then retry."
    }
}

/**
 * The combination ladder, largest first: every subset of [base]
 * (optionally plus [extra]). Shared by preview-start and recording-start
 * so both fall back identically on constraint-limited devices. Pure list
 * math — the provider interaction stays in [CameraService] — and tested
 * directly.
 */
internal fun <T> orderedCombinations(base: List<T>, extra: T?): List<List<T>> =
    buildList {
        for (size in base.size downTo 0) {
            subsetsOf(base, size).forEach { subset ->
                add(if (extra != null) subset + extra else subset)
            }
        }
    }

internal fun <T> subsetsOf(items: List<T>, size: Int): List<List<T>> {
    val result = mutableListOf<List<T>>()
    fun recurse(start: Int, current: MutableList<T>) {
        if (current.size == size) {
            result += current.toList()
            return
        }
        for (i in start..items.lastIndex) {
            current += items[i]
            recurse(i + 1, current)
            current.removeAt(current.lastIndex)
        }
    }
    recurse(0, mutableListOf())
    return result
}

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
