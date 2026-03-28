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
import androidx.lifecycle.LifecycleOwner
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("DEPRECATION")
class CameraService(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var frameListener: ((Bitmap) -> Unit)? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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
        if (index < 0 || index >= lenses.size) return
        val lens = lenses[index]
        _selectedLensIndex.value = index
        currentCameraSelector = lens.cameraSelector
        _isFrontCamera.value = lens.lensFacing == CameraSelector.LENS_FACING_FRONT
        val pv = currentPreviewView
        if (pv != null) {
            startPreview(pv)
        }
    }

    fun startPreview(previewView: PreviewView) {
        val provider = cameraProvider
        if (provider == null) {
            Log.e(TAG, "startPreview: cameraProvider is null")
            return
        }
        val owner = lifecycleOwner
        if (owner == null) {
            Log.e(TAG, "startPreview: lifecycleOwner is null")
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
        preview = Preview.Builder()
            .build()
            .also {
                Log.d(TAG, "startPreview: setting surfaceProvider")
                it.surfaceProvider = previewView.surfaceProvider
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
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

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val result = if (rotation != 0f) {
                val matrix = Matrix()
                matrix.postRotate(rotation)
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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

    private var currentResolution: android.util.Size = android.util.Size(1920, 1080)

    suspend fun applySettings(settings: CameraSettings) {
        val cam = camera ?: return

        try {
            cam.cameraControl.setZoomRatio(settings.zoomRatio).await()
        } catch (e: Exception) {
            Log.w(TAG, "Zoom failed", e)
        }

        try {
            val expState = cam.cameraInfo.exposureState
            val lower = expState.exposureCompensationRange.lower
            val upper = expState.exposureCompensationRange.upper
            val value = settings.exposureCompensation.coerceIn(lower, upper)
            if (value != expState.exposureCompensationIndex) {
                try {
                    val method = cam.cameraControl.javaClass.getMethod(
                        "setExposureCompensation", Int::class.javaPrimitiveType
                    )
                    method.invoke(cam.cameraControl, value)
                } catch (_: Exception) { }
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
                    ).await()
                }
                FocusMode.MANUAL -> { }
                else -> {
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(center).build()
                    ).await()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Focus failed", e)
        }

        if (settings.resolution.size != currentResolution) {
            currentResolution = settings.resolution.size
            val pv = currentPreviewView
            if (pv != null) {
                withContext(Dispatchers.Main) {
                    startPreview(pv)
                }
            }
        }
    }

    private var currentPreviewView: PreviewView? = null

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