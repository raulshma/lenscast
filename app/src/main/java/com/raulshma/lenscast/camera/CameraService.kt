package com.raulshma.lenscast.camera


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.UseCase
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.camera.model.HdrMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

@Suppress("DEPRECATION")
class CameraService(private val context: Context) {

    private class KeepAliveLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun activate() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun deactivate() {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var frameListener: ((Bitmap) -> Unit)? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private val keepAliveLifecycle = KeepAliveLifecycle()
    private var keepAliveRefCount = 0

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _availableZoomRange = MutableStateFlow<ClosedFloatingPointRange<Float>>(1f..10f)
    val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _availableZoomRange.asStateFlow()

    private val _availableExposureRange = MutableStateFlow<ClosedRange<Int>>(-12..12)
    val availableExposureRange: StateFlow<ClosedRange<Int>> = _availableExposureRange.asStateFlow()

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

    fun isKeepAliveActive(): Boolean = keepAliveRefCount > 0

    fun getEffectiveLifecycleOwner(): LifecycleOwner {
        return if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner!!
    }

    fun setFrameListener(listener: ((Bitmap) -> Unit)?) {
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
                    val logicalLabel = buildCameraLabel(lensFacing, logicalFocalLength, cameraId)
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
                            val label = buildCameraLabel(lensFacing, focalLength, physId)
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

            // Remove duplicated lenses that share the same focal length and facing (OEMs often duplicate them)
            val distinctLenses = lenses.distinctBy { Pair(it.lensFacing, it.focalLength) }

            // Sort: back cameras sorted by focal length (ascending), then front cameras
            val sorted = distinctLenses.sortedWith(
                compareBy<CameraLensInfo> { it.lensFacing != CameraSelector.LENS_FACING_BACK }
                    .thenBy { it.focalLength }
            )

            _availableLenses.value = sorted

            // We MUST default to the MAIN logical back camera. Direct binding to physical
            // cameras on start causes black screen on many OEM drivers.
            val logicalBackIndex = sorted.indexOfFirst {
                it.lensFacing == CameraSelector.LENS_FACING_BACK && sorted.firstOrNull { l -> l.lensFacing == CameraSelector.LENS_FACING_BACK }?.id == it.id
            }.coerceAtLeast(0)
            
            _selectedLensIndex.value = logicalBackIndex

            if (sorted.isNotEmpty()) {
                currentCameraSelector = sorted[logicalBackIndex].cameraSelector
                _isFrontCamera.value = sorted[logicalBackIndex].lensFacing == CameraSelector.LENS_FACING_FRONT
            }

            Log.d(TAG, "Enumerated ${sorted.size} cameras, default index=$logicalBackIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Camera enumeration failed, falling back to default", e)
            // Fallback — create basic entries
            _availableLenses.value = listOf(
                CameraLensInfo(
                    id = "0",
                    label = "Back",
                    lensFacing = CameraSelector.LENS_FACING_BACK,
                    focalLength = 0f,
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
                ),
                CameraLensInfo(
                    id = "1",
                    label = "Front",
                    lensFacing = CameraSelector.LENS_FACING_FRONT,
                    focalLength = 0f,
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
                )
            )
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

    private fun buildCameraLabel(lensFacing: Int, focalLength: Float, cameraId: String): String {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return "Front"
        }
        // Back camera label based on focal length ranges
        return when {
            focalLength <= 0f -> "Camera $cameraId"
            focalLength < 2.5f -> "Ultrawide"
            focalLength < 5f -> "Wide"
            focalLength < 8f -> "2x"
            focalLength < 15f -> "3x"
            focalLength < 25f -> "5x"
            else -> "${focalLength.toInt()}mm"
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

        val needsKeepAlive = keepAliveRefCount == 0 && lifecycleOwner == null
        if (needsKeepAlive) {
            keepAliveRefCount++
            keepAliveLifecycle.activate()
            Log.d(TAG, "selectLens: temporarily activated keep-alive for lens change")
        }

        val pv = currentPreviewView
        if (pv != null) {
            Log.d(TAG, "selectLens: restarting preview with new lens")
            startPreview(pv)
        } else {
            Log.w(TAG, "selectLens: no previewView available, trying rebind")
            rebindUseCases()
        }

        if (needsKeepAlive) {
            keepAliveRefCount = max(0, keepAliveRefCount - 1)
            if (keepAliveRefCount == 0) {
                keepAliveLifecycle.deactivate()
                Log.d(TAG, "selectLens: deactivated temporary keep-alive")
            }
            Log.d(TAG, "selectLens: ref count after temporary release: $keepAliveRefCount")
        }
    }

    fun rebindUseCases() {
        val provider = cameraProvider
        Log.d(TAG, "rebindUseCases: provider=${provider != null}, refCount=$keepAliveRefCount, lifecycleOwner=${lifecycleOwner != null}")
        
        if (provider == null) {
            Log.w(TAG, "rebindUseCases: cameraProvider is null, cannot rebind")
            return
        }
        
        val owner = if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner
        if (owner == null) {
            Log.w(TAG, "rebindUseCases: no lifecycle owner available and no keep-alive, cannot rebind")
            return
        }
        
        val pv = currentPreviewView

        val useCases = mutableListOf<UseCase>()
        if (preview != null) {
            if (pv != null && isActivityForeground) {
                preview!!.surfaceProvider = pv.surfaceProvider
                useCases.add(preview!!)
            }
        }
        imageCapture?.let { useCases.add(it) }
        imageAnalysis?.let { useCases.add(it) }

        if (useCases.isEmpty()) {
            Log.w(TAG, "rebindUseCases: no use cases to rebind (preview=$preview, imageCapture=$imageCapture, imageAnalysis=$imageAnalysis)")
            return
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                owner, currentCameraSelector,
                *useCases.toTypedArray()
            )
            Log.d(TAG, "rebindUseCases: rebound ${useCases.size} use cases")
        } catch (e: Exception) {
            Log.e(TAG, "rebindUseCases: failed", e)
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun startPreview(previewView: PreviewView) {
        val provider = cameraProvider
        if (provider == null) {
            Log.e(TAG, "startPreview: cameraProvider is null")
            return
        }
        val owner = if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner
        if (owner == null) {
            Log.e(TAG, "startPreview: no lifecycle owner available")
            return
        }
        currentPreviewView = previewView
        Log.d(TAG, "startPreview: starting with selector=$currentCameraSelector, resolution=$currentResolution")

        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    currentResolution,
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        // Intentionally DO NOT bind ResolutionSelector to Preview. 
        // Let CameraX decide the best display aspect ratio natively to prevent surface bind failures.
        val previewBuilder = Preview.Builder()
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        val selectedLens = _availableLenses.value.getOrNull(_selectedLensIndex.value)
        if (selectedLens?.physicalCameraId != null) {
            val physId = selectedLens.physicalCameraId
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physId)
            androidx.camera.camera2.interop.Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physId)
            androidx.camera.camera2.interop.Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physId)
            Log.d(TAG, "startPreview: applied physicalCameraId $physId to all use cases")
        }

        preview = previewBuilder.build().also {
            Log.d(TAG, "startPreview: setting surfaceProvider")
            it.surfaceProvider = previewView.surfaceProvider
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
                // Try binding all 3 use cases first
                camera = provider.bindToLifecycle(
                    owner,
                    currentCameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                Log.d(TAG, "startPreview: bound with Preview + ImageCapture + ImageAnalysis")
            } catch (e: Exception) {
                // Some devices (LIMITED hardware) can't bind 3 use cases;
                // fall back to Preview + ImageAnalysis (streaming is priority)
                Log.w(TAG, "startPreview: 3 use-case binding failed, falling back to 2", e)
                provider.unbindAll()
                imageCapture = null
                camera = provider.bindToLifecycle(
                    owner,
                    currentCameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "startPreview: bound with Preview + ImageAnalysis (fallback)")
            }

            camera?.let { cam ->
                cam.cameraInfo.zoomState.value?.let { zoom ->
                    _availableZoomRange.value = 1f..zoom.maxZoomRatio.coerceAtMost(20f)
                }
                val expState = cam.cameraInfo.exposureState
                _availableExposureRange.value = expState.exposureCompensationRange.lower..
                        expState.exposureCompensationRange.upper
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: failed to bind camera", e)
            _cameraState.value = CameraState.Error("Failed to start preview: ${e.message}")
        }
    }

    fun switchCamera(previewView: PreviewView) {
        val lenses = _availableLenses.value
        if (lenses.isEmpty()) {
            // Fallback to simple front/back toggle
            currentCameraSelector = if (_isFrontCamera.value) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            _isFrontCamera.value = !_isFrontCamera.value
            startPreview(previewView)
            return
        }

        // Cycle to next lens
        val currentIndex = _selectedLensIndex.value
        val nextIndex = (currentIndex + 1) % lenses.size
        selectLens(nextIndex)
    }

    private val analysisExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "FrameAnalysis").apply { isDaemon = true }
        }
    }

    private var cachedRotation: Float = -1f
    private var rotationMatrix: Matrix? = null

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val result = if (rotation != 0f) {
                if (rotation != cachedRotation || rotationMatrix == null) {
                    cachedRotation = rotation
                    rotationMatrix = Matrix().apply { postRotate(rotation) }
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
            } else {
                bitmap
            }
            frameListener?.invoke(result)
            if (result !== bitmap) bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private var pendingResolution: android.util.Size? = null
    private var currentResolution: android.util.Size = android.util.Size(1920, 1080)

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    suspend fun applySettings(settings: CameraSettings) {
        if (settings.resolution.size != currentResolution) {
            currentResolution = settings.resolution.size
            if (isActivityForeground) {
                val pv = currentPreviewView
                if (pv != null) {
                    withContext(Dispatchers.Main) {
                        startPreview(pv)
                    }
                    applyCameraControls(settings)
                    return
                }
            } else {
                pendingResolution = settings.resolution.size
                Log.d(TAG, "applySettings: deferring resolution change until foreground")
            }
        }

        applyCameraControls(settings)
    }

    fun applyPendingResolution() {
        val res = pendingResolution ?: return
        pendingResolution = null
        currentResolution = res
        val pv = currentPreviewView ?: return
        startPreview(pv)
        Log.d(TAG, "applyPendingResolution: applied deferred resolution $res")
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyCameraControls(settings: CameraSettings) {
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
        } catch (e: Exception) {
            Log.w(TAG, "Focus failed", e)
        }

        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            
            val fpsRange = Range(settings.frameRate, settings.frameRate)
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            
            if (settings.stabilization) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
            
            when (settings.hdrMode) {
                HdrMode.ON -> builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
                else -> { }
            }
            
            if (settings.iso != null || settings.exposureTime != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                settings.iso?.let {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it)
                }
                settings.exposureTime?.let {
                    builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
                }
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            
            when (settings.whiteBalance) {
               WhiteBalance.AUTO -> builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
               WhiteBalance.DAYLIGHT -> builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
               WhiteBalance.CLOUDY -> builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
               WhiteBalance.INDOOR -> builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
               WhiteBalance.FLUORESCENT -> builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
               WhiteBalance.MANUAL -> {
                   builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
               }
            }
            
            if (settings.focusMode == FocusMode.MANUAL) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                settings.focusDistance?.let {
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                }
            }
            
            settings.sceneMode?.toIntOrNull()?.let {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, it)
            }
            
            camera2Control.setCaptureRequestOptions(builder.build())
            
        } catch (e: Exception) {
            Log.w(TAG, "Advanced settings failed", e)
        }
    }

    private var currentPreviewView: PreviewView? = null
    private var isActivityForeground = true

    fun onActivityResume() {
        isActivityForeground = true
        if (keepAliveRefCount > 0) {
            if (pendingResolution != null) {
                applyPendingResolution()
            } else if (currentPreviewView != null) {
                rebindUseCases()
            } else if (preview != null) {
                preview!!.surfaceProvider = currentPreviewView!!.surfaceProvider
            }
            Log.d(TAG, "onActivityResume: restored preview")
        }
    }

    fun onActivityStop() {
        isActivityForeground = false
        if (keepAliveRefCount > 0) {
            preview?.surfaceProvider = null
            Log.d(TAG, "onActivityStop: detached preview surface for background operation")
        }
    }

    fun isPreviewAvailable(): Boolean = isActivityForeground

    fun getImageCapture(): ImageCapture? = imageCapture
    fun getCamera(): Camera? = camera
    fun getCameraProvider(): ProcessCameraProvider? = cameraProvider
    fun getPreview(): Preview? = preview
    fun getImageAnalysis(): ImageAnalysis? = imageAnalysis
    fun getLifecycleOwner(): LifecycleOwner? = lifecycleOwner

    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        camera = null
        _cameraState.value = CameraState.Idle
    }

    companion object {
        private const val TAG = "CameraService"
    }
}