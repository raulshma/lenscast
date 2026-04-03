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
import java.util.concurrent.TimeUnit

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
    private var frameListener: ((ByteArray, Int, Int, Int) -> Unit)? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var previewRequested = false
    private var exclusiveSessionRefCount = 0
    private var currentPreviewView: PreviewView? = null
    private var activeSettings = CameraSettings()

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

    fun beginExclusiveSession() {
        exclusiveSessionRefCount++
        Log.d(TAG, "Exclusive camera session started (count=$exclusiveSessionRefCount)")
    }

    fun endExclusiveSession() {
        exclusiveSessionRefCount = max(0, exclusiveSessionRefCount - 1)
        Log.d(TAG, "Exclusive camera session ended (count=$exclusiveSessionRefCount)")
    }

    fun getEffectiveLifecycleOwner(): LifecycleOwner {
        return if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner!!
    }

    fun getCurrentCameraSelector(): CameraSelector = currentCameraSelector

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
        
        val owner = if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner
        if (owner == null) {
            Log.w(TAG, "rebindUseCases: no lifecycle owner available and no keep-alive, cannot rebind")
            return
        }

        bindUseCases(provider, owner)
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

        val owner = if (keepAliveRefCount > 0) keepAliveLifecycle else lifecycleOwner
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
                camera = bindBestAvailableCombination(provider, owner)
            } catch (e: Exception) {
                Log.e(TAG, "bindUseCases: failed to bind camera", e)
                _cameraState.value = CameraState.Error("Failed to start camera: ${e.message}")
                return
            }

            camera?.let { cam ->
                cam.cameraInfo.zoomState.value?.let { zoom ->
                    _availableZoomRange.value = 1f..zoom.maxZoomRatio.coerceAtMost(20f)
                }
                val expState = cam.cameraInfo.exposureState
                _availableExposureRange.value = expState.exposureCompensationRange.lower..
                        expState.exposureCompensationRange.upper
            }

            applyCameraControls(activeSettings)
        } catch (e: Exception) {
            Log.e(TAG, "bindUseCases: failed to bind camera", e)
            _cameraState.value = CameraState.Error("Failed to start camera: ${e.message}")
        }
    }

    private fun bindBestAvailableCombination(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
    ): Camera {
        val preferredCombinations = buildList<List<UseCase>> {
            if (preview != null && imageCapture != null && imageAnalysis != null) {
                add(listOf(preview!!, imageCapture!!, imageAnalysis!!))
                add(listOf(preview!!, imageCapture!!))
                add(listOf(preview!!, imageAnalysis!!))
            }
            if (imageCapture != null && imageAnalysis != null) {
                add(listOf(imageCapture!!, imageAnalysis!!))
            }
            imageCapture?.let { add(listOf(it)) }
            imageAnalysis?.let { add(listOf(it)) }
        }

        var lastError: Exception? = null
        preferredCombinations.forEach { useCases ->
            try {
                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    owner,
                    currentCameraSelector,
                    *useCases.toTypedArray()
                )

                preview = useCases.filterIsInstance<Preview>().firstOrNull()
                imageCapture = useCases.filterIsInstance<ImageCapture>().firstOrNull()
                imageAnalysis = useCases.filterIsInstance<ImageAnalysis>().firstOrNull()

                Log.d(
                    TAG,
                    "bindBestAvailableCombination: bound ${useCases.joinToString { it.javaClass.simpleName }}"
                )
                return boundCamera
            } catch (e: Exception) {
                lastError = e
                Log.w(
                    TAG,
                    "bindBestAvailableCombination: failed for ${useCases.joinToString { it.javaClass.simpleName }}",
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
            val cropRect = imageProxy.cropRect
            val width = cropRect.width()
            val height = cropRect.height()
            val rotation = imageProxy.imageInfo.rotationDegrees
            val yuvData = yuvToNv21(imageProxy)
            if (yuvData != null) {
                frameListener?.invoke(yuvData, width, height, rotation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null

        val ySize = width * height
        val uvSize = ySize / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Copy Y plane with row/pixel stride and crop handling.
        val yBuffer = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var yOut = 0
        val yCropTop = crop.top
        val yCropLeft = crop.left
        for (row in 0 until height) {
            val rowStart = (row + yCropTop) * yRowStride + yCropLeft * yPixelStride
            var srcIndex = rowStart
            for (col in 0 until width) {
                nv21[yOut++] = yBuffer.get(srcIndex)
                srcIndex += yPixelStride
            }
        }

        // Copy UV planes interleaved as VU (NV21) with independent strides and crop.
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvCropTop = crop.top / 2
        val uvCropLeft = crop.left / 2
        var uvPos = ySize

        for (row in 0 until uvHeight) {
            val uRowStart = (row + uvCropTop) * uRowStride + uvCropLeft * uPixelStride
            val vRowStart = (row + uvCropTop) * vRowStride + uvCropLeft * vPixelStride
            var uIndex = uRowStart
            var vIndex = vRowStart
            for (col in 0 until uvWidth) {
                nv21[uvPos++] = vBuffer.get(vIndex)
                nv21[uvPos++] = uBuffer.get(uIndex)
                uIndex += uPixelStride
                vIndex += vPixelStride
            }
        }

        return nv21
    }

    private fun getStreamingAnalysisResolution(captureResolution: Size): Size {
        if (captureResolution.width <= MAX_ANALYSIS_WIDTH &&
            captureResolution.height <= MAX_ANALYSIS_HEIGHT
        ) {
            return captureResolution
        }

        val isFourThree = captureResolution.width * 3 >= captureResolution.height * 4 - 8 &&
            captureResolution.width * 3 <= captureResolution.height * 4 + 8

        return if (isFourThree) {
            Size(960, 720)
        } else {
            Size(MAX_ANALYSIS_WIDTH, MAX_ANALYSIS_HEIGHT)
        }
    }

    private var pendingResolution: Size? = null
    private var currentResolution: Size = Size(1920, 1080)

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    suspend fun applySettings(settings: CameraSettings) {
        activeSettings = settings
        if (settings.resolution.size != currentResolution) {
            currentResolution = settings.resolution.size
            if (hasActiveCameraDemand() && exclusiveSessionRefCount == 0) {
                withContext(Dispatchers.Main) {
                    rebindUseCases()
                }
                applyCameraControls(settings)
                return
            } else {
                pendingResolution = settings.resolution.size
                Log.d(TAG, "applySettings: deferring resolution change until next active session")
            }
        }

        applyCameraControls(settings)
    }

    fun applyPendingResolution() {
        val res = pendingResolution ?: return
        pendingResolution = null
        currentResolution = res
        if (hasActiveCameraDemand() && exclusiveSessionRefCount == 0) {
            rebindUseCases()
        }
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

    private var isActivityForeground = true

    fun onActivityResume() {
        isActivityForeground = true
        if (previewRequested && exclusiveSessionRefCount == 0) {
            if (pendingResolution != null) {
                applyPendingResolution()
            } else {
                rebindUseCases()
            }
            Log.d(TAG, "onActivityResume: restored preview")
        }
    }

    fun onActivityStop() {
        isActivityForeground = false
        if (currentPreviewView != null) {
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
        clearBoundUseCases()
        _cameraState.value = CameraState.Idle
        previewRequested = false
        exclusiveSessionRefCount = 0
        currentPreviewView = null
        try {
            analysisExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CameraService"
        private const val MAX_ANALYSIS_WIDTH = 1280
        private const val MAX_ANALYSIS_HEIGHT = 720
    }
}
