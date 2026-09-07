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
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusApplyPolicy
import com.raulshma.lenscast.camera.model.FrameErrorPolicy
import com.raulshma.lenscast.camera.model.ResolutionApplyPolicy
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import java.util.concurrent.TimeUnit

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
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var previewRequested = false
    private var exclusiveSessionRefCount = 0
    private var currentPreviewView: PreviewView? = null
    private var activeSettings = CameraSettings()

    // Last settings whose center AF/AE metering was fired; the focus part of
    // the next apply is gated on it (see FocusApplyPolicy). Reset on release
    // so a fresh session re-establishes metering.
    private var lastFocusApplied: CameraSettings? = null

    private val keepAliveLifecycle = KeepAliveLifecycle()
    private var keepAliveRefCount = 0

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

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

    private var sensorExposureTimeRange: LongRange = 1L..1_000_000_000L

    private val _availableLenses = MutableStateFlow<List<CameraLensInfo>>(emptyList())
    val availableLenses: StateFlow<List<CameraLensInfo>> = _availableLenses.asStateFlow()

    private val _selectedLensIndex = MutableStateFlow(0)
    val selectedLensIndex: StateFlow<Int> = _selectedLensIndex.asStateFlow()

    fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    fun acquireKeepAlive() {
        keepAliveRefCount++
        if (keepAliveRefCount == 1) {
            keepAliveLifecycle.activate()
            Log.d(TAG, "Keep-alive lifecycle activated")
        }
        Log.d(TAG, "Keep-alive ref count: $keepAliveRefCount")
    }

    fun releaseKeepAlive() {
        keepAliveRefCount = max(0, keepAliveRefCount - 1)
        if (keepAliveRefCount == 0) {
            keepAliveLifecycle.deactivate()
            Log.d(TAG, "Keep-alive lifecycle deactivated")
        }
        Log.d(TAG, "Keep-alive ref count: $keepAliveRefCount")
    }

    fun beginExclusiveSession() {
        exclusiveSessionRefCount++
        Log.d(TAG, "Exclusive camera session started (count=$exclusiveSessionRefCount)")
    }

    fun endExclusiveSession() {
        exclusiveSessionRefCount = max(0, exclusiveSessionRefCount - 1)
        Log.d(TAG, "Exclusive camera session ended (count=$exclusiveSessionRefCount)")
    }

    fun getEffectiveLifecycleOwner(): LifecycleOwner? {
        return if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner
    }

    fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: run {
            Log.w(TAG, "tapToFocus: camera not available")
            return
        }
        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS)
                    .build()
            )
            Log.d(TAG, "tapToFocus: x=$x, y=$y")
        } catch (e: Exception) {
            Log.w(TAG, "tapToFocus failed", e)
        }
    }

    fun setFrameListener(listener: ((ByteArray, Int, Int, Int) -> Unit)?) {
        frameListener = listener
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "Starting camera initialization...")
            _cameraState.value = CameraState.Initializing
            val future = ProcessCameraProvider.getInstance(context)
            val provider = withTimeoutOrNull(10_000L) {
                future.await()
            } ?: throw Exception("Camera initialization timed out")
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

    private fun ensureCameraProviderAvailable(): ProcessCameraProvider? {
        cameraProvider?.let { return it }

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

    private fun hasActiveCameraDemand(): Boolean = previewRequested || keepAliveRefCount > 0

    private fun shouldAttachPreview(): Boolean {
        return previewRequested && currentPreviewView != null && isActivityForeground
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
                    val logicalLabel = LensInventory.buildLabel(lensFacing, logicalFocalLength, cameraId)
                    val logicalSelector = buildCameraSelector(info)

                    val logicalCamInfo = CameraLensInfo(
                        id = cameraId,
                        label = logicalLabel,
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
                            val label = LensInventory.buildLabel(lensFacing, focalLength, physId)
                            val selector = CameraSelector.Builder()
                                .requireLensFacing(lensFacing)
                                .setPhysicalCameraId(physId)
                                .build()

                            lenses.add(
                                CameraLensInfo(
                                    id = physId,
                                    label = label,
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

            // Inventory decisions (dedup, order, main-lens default) live in
            // the tested LensInventory; the service only publishes them.
            val sorted = LensInventory.sortLenses(LensInventory.deduplicate(lenses))

            _availableLenses.value = sorted

            // We MUST default to the MAIN logical back camera. Direct binding to physical
            // cameras on start causes black screen on many OEM drivers.
            val logicalBackIndex = LensInventory.defaultBackIndex(sorted)

            _selectedLensIndex.value = logicalBackIndex

            if (sorted.isNotEmpty()) {
                currentCameraSelector = sorted[logicalBackIndex].cameraSelector
                _isFrontCamera.value = sorted[logicalBackIndex].lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            Log.d(TAG, "Enumerated ${sorted.size} cameras, default index=$logicalBackIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Camera enumeration failed, falling back to default", e)
            // Fallback — create basic entries
            _availableLenses.value = LensInventory.fallbackLenses()
            _selectedLensIndex.value = 0
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
        Log.d(TAG, "selectLens: switching to lens $index: ${lens.label}, provider=${cameraProvider != null}, previewView=${currentPreviewView != null}")
        
        _selectedLensIndex.value = index
        currentCameraSelector = lens.cameraSelector
        _isFrontCamera.value = lens.lensFacing == CameraSelector.LENS_FACING_FRONT

        if (hasActiveCameraDemand() && exclusiveSessionRefCount == 0) {
            rebindUseCases()
        } else {
            Log.d(TAG, "selectLens: selector updated; camera will switch on next active session")
        }
    }

    fun rebindUseCases() {
        if (exclusiveSessionRefCount > 0) {
            Log.d(TAG, "rebindUseCases: skipped while an exclusive session is active")
            return
        }

        val provider = ensureCameraProviderAvailable()
        Log.d(TAG, "rebindUseCases: provider=${provider != null}, refCount=$keepAliveRefCount, previewRequested=$previewRequested, lifecycleOwner=${lifecycleOwner != null}")

        if (provider == null) {
            Log.w(TAG, "rebindUseCases: cameraProvider is null, cannot rebind")
            return
        }

        if (!hasActiveCameraDemand()) {
            provider.unbindAll()
            clearBoundUseCases()
            Log.d(TAG, "rebindUseCases: unbound camera because there is no active demand")
            return
        }

        val owner = getEffectiveLifecycleOwner()
        if (owner == null) {
            Log.w(TAG, "rebindUseCases: no lifecycle owner available and no keep-alive, cannot rebind")
            return
        }

        bindUseCases(provider, owner)
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

        currentPreviewView = previewView
        previewRequested = true
        Log.d(TAG, "startPreview: requested with selector=$currentCameraSelector, resolution=$currentResolution")

        val owner = getEffectiveLifecycleOwner()
        if (owner == null) {
            Log.e(TAG, "startPreview: no lifecycle owner available")
            return
        }

        bindUseCases(provider, owner)
    }

    fun stopPreview() {
        previewRequested = false
        preview?.surfaceProvider = null
        currentPreviewView = null

        if (exclusiveSessionRefCount > 0) {
            Log.d(TAG, "stopPreview: preview released while exclusive session remains active")
            return
        }

        rebindUseCases()
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
        Log.d(TAG, "bindUseCases: selector=$currentCameraSelector, resolution=$currentResolution, attachPreview=${shouldAttachPreview()}")

        val captureResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    currentResolution,
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(captureResolutionSelector)
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        val selectedLens = _availableLenses.value.getOrNull(_selectedLensIndex.value)
        if (selectedLens?.physicalCameraId != null) {
            val physId = selectedLens.physicalCameraId
            Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physId)
            Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physId)
            Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physId)
            Log.d(TAG, "bindUseCases: applied physicalCameraId $physId to all use cases")
        }

        val previewView = currentPreviewView
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
            }

            applyCameraControls(activeSettings, forceFocusReapply = true)
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
                    currentCameraSelector,
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
        preview?.surfaceProvider = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        camera = null
    }

    fun switchCamera() {
        val lenses = _availableLenses.value
        if (lenses.isEmpty()) {
            // Fallback to simple front/back toggle
            currentCameraSelector = if (_isFrontCamera.value) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            _isFrontCamera.value = !_isFrontCamera.value
            val previewView = currentPreviewView
            if (previewView != null) {
                startPreview(previewView)
            } else if (hasActiveCameraDemand() && exclusiveSessionRefCount == 0) {
                // Headless switch (preview hidden): just rebind to the new selector.
                rebindUseCases()
            }
            return
        }

        // Cycle to next lens
        val currentIndex = _selectedLensIndex.value
        val nextIndex = (currentIndex + 1) % lenses.size
        selectLens(nextIndex)
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
            val yuvData = yuvToNv21(imageProxy)
            if (yuvData != null) {
                consecutiveFrameErrors = 0
                frameListener?.invoke(yuvData, width, height, rotation)
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
            if (hasActiveCameraDemand() && exclusiveSessionRefCount == 0) {
                rebindUseCases()
                Log.d(TAG, "Auto-recovery: rebind use cases attempted")
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

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    suspend fun applySettings(settings: CameraSettings) {
        activeSettings = settings
        if (settings.resolution.size != currentResolution) {
            currentResolution = settings.resolution.size
            when (
                ResolutionApplyPolicy.decide(
                    demandActive = hasActiveCameraDemand(),
                    exclusiveActive = exclusiveSessionRefCount > 0,
                    resolutionChanged = true,
                )
            ) {
                is ResolutionApplyPolicy.ResolutionDecision.RebindNow -> {
                    withContext(Dispatchers.Main) {
                        rebindUseCases()
                    }
                    applyCameraControls(settings, forceFocusReapply = false)
                    return
                }
                ResolutionApplyPolicy.ResolutionDecision.Defer -> {
                    pendingResolution = settings.resolution.size
                    Log.d(TAG, "applySettings: deferring resolution change until next active session")
                }
            }
        }

        applyCameraControls(settings, forceFocusReapply = false)
    }

    fun applyPendingResolution() {
        val res = pendingResolution ?: return
        pendingResolution = null
        currentResolution = res
        if (
            ResolutionApplyPolicy.decide(
                demandActive = hasActiveCameraDemand(),
                exclusiveActive = exclusiveSessionRefCount > 0,
                resolutionChanged = true,
            ) is ResolutionApplyPolicy.ResolutionDecision.RebindNow
        ) {
            rebindUseCases()
        }
        Log.d(TAG, "applyPendingResolution: applied deferred resolution $res")
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyCameraControls(settings: CameraSettings, forceFocusReapply: Boolean) {
        val cam = camera ?: return

        try {
            cam.cameraControl.setZoomRatio(settings.zoomRatio)
        } catch (e: Exception) {
            Log.w(TAG, "Zoom failed", e)
        }

        try {
            val expState = cam.cameraInfo.exposureState
            val lower = expState.exposureCompensationRange.lower
            val upper = expState.exposureCompensationRange.upper
            val value = settings.exposureCompensation.coerceIn(lower, upper)
            if (value != expState.exposureCompensationIndex) {
                cam.cameraControl.setExposureCompensationIndex(value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exposure compensation failed", e)
        }

        // Center AF/AE metering only re-fires when the focus-relevant fields
        // moved (or after a fresh bind): a plain settings apply — every
        // slider tick reaches here twice — must not cancel a deliberate
        // tap-to-focus. The pure decision lives in FocusApplyPolicy.
        if (forceFocusReapply || FocusApplyPolicy.needsReapply(lastFocusApplied, settings)) {
            try {
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val center = factory.createPoint(0.5f, 0.5f)
                when (settings.focusMode) {
                    FocusMode.CONTINUOUS_PICTURE, FocusMode.CONTINUOUS_VIDEO -> {
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(center)
                                .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                        )
                    }
                    FocusMode.MANUAL -> { }
                    else -> {
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(center).build()
                        )
                    }
                }
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
            // Manual WB is conveyed by AWB OFF above. The Kelvin decision lives
            // in plan.colorTemperatureKelvin (clamped); gains/matrix mapping is
            // sensor-specific, so it stays device-default until a calibrated
            // Kelvin->gains table exists — referenced here so the plan stays the
            // single decision table.
            plan.colorTemperatureKelvin?.let {
                Log.d(TAG, "Manual white balance Kelvin: $it")
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

    private var isActivityForeground = true

    fun onActivityResume() {
        isActivityForeground = true
        when (
            val decision = ResolutionApplyPolicy.decide(
                demandActive = previewRequested,
                exclusiveActive = exclusiveSessionRefCount > 0,
                resolutionChanged = pendingResolution != null,
            )
        ) {
            is ResolutionApplyPolicy.ResolutionDecision.RebindNow -> {
                if (decision.withResolutionChange) {
                    applyPendingResolution()
                } else {
                    rebindUseCases()
                }
                Log.d(TAG, "onActivityResume: restored preview")
            }
            ResolutionApplyPolicy.ResolutionDecision.Defer -> {}
        }
    }

    fun onActivityStop() {
        isActivityForeground = false
        if (currentPreviewView != null) {
            preview?.surfaceProvider = null
            Log.d(TAG, "onActivityStop: detached preview surface for background operation")
        }
    }

    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        clearBoundUseCases()
        _cameraState.value = CameraState.Idle
        previewRequested = false
        exclusiveSessionRefCount = 0
        currentPreviewView = null
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
 * The streaming analysis size: small captures pass through, large 4:3
 * captures drop to 960x720, everything else to the ceiling. Pure ints —
 * the `Size` wrapping stays at the call site.
 */
internal fun analysisSizeFor(
    captureWidth: Int,
    captureHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
): Pair<Int, Int> {
    if (captureWidth <= maxWidth && captureHeight <= maxHeight) {
        return captureWidth to captureHeight
    }

    val isFourThree = captureWidth * 3 >= captureHeight * 4 - 8 &&
        captureWidth * 3 <= captureHeight * 4 + 8

    return if (isFourThree) {
        960 to 720
    } else {
        maxWidth to maxHeight
    }
}
