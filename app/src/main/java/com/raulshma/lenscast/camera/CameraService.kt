package com.raulshma.lenscast.camera

 
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.Camera
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
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

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

    fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    fun setFrameListener(listener: ((Bitmap) -> Unit)?) {
        frameListener = listener
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            cameraProvider = provider
            _cameraState.value = CameraState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            _cameraState.value = CameraState.Error(e.message ?: "Camera initialization failed")
            Result.failure(e)
        }
    }

    @Suppress("DEPRECATION")
    fun startPreview(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        currentPreviewView = previewView

        preview = Preview.Builder()
            .setTargetResolution(currentResolution)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(currentResolution)
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(currentResolution)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            owner,
            currentCameraSelector,
            preview,
            imageCapture,
            imageAnalysis
        )

        camera?.let { cam ->
            cam.cameraInfo.zoomState.value?.let { zoom ->
                _availableZoomRange.value = 1f..zoom.maxZoomRatio.coerceAtMost(20f)
            }
            val expState = cam.cameraInfo.exposureState
            _availableExposureRange.value = expState.exposureCompensationRange.lower..
                    expState.exposureCompensationRange.upper
        }
    }

    fun switchCamera(previewView: PreviewView) {
        currentCameraSelector = if (_isFrontCamera.value) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        _isFrontCamera.value = !_isFrontCamera.value
        startPreview(previewView)
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