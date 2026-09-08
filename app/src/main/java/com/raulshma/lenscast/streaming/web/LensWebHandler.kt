package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import android.util.Log
import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.streaming.model.LensDto
import com.raulshma.lenscast.streaming.model.LensSelectRequest
import com.raulshma.lenscast.streaming.model.LensesResponseDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import com.raulshma.lenscast.streaming.model.TapFocusRequest
import com.raulshma.lenscast.streaming.model.TorchRequest
import com.raulshma.lenscast.streaming.model.ZoomRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** /api/camera/... — lens enumeration/selection and tap-to-focus. */
class LensWebHandler(private val cameraService: CameraService) {

    private val lensesAdapter by lazy { AppJson.moshi.adapter(LensesResponseDto::class.java) }
    private val lensSelectAdapter by lazy { AppJson.moshi.adapter(LensSelectRequest::class.java) }
    private val tapFocusAdapter by lazy { AppJson.moshi.adapter(TapFocusRequest::class.java) }
    private val zoomAdapter by lazy { AppJson.moshi.adapter(ZoomRequest::class.java) }
    private val torchAdapter by lazy { AppJson.moshi.adapter(TorchRequest::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    fun getLenses(): String {
        val lenses = cameraService.availableLenses.value
        val selectedIndex = cameraService.selectedLensIndex.value

        val lensDtos = lenses.mapIndexed { index, lens ->
            LensDto(
                index = index,
                id = lens.id,
                label = lens.label,
                focalLength = lens.focalLength.toDouble(),
                isFront = lens.lensFacing == CameraSelector.LENS_FACING_FRONT,
                selected = index == selectedIndex,
            )
        }

        return lensesAdapter.toJson(LensesResponseDto(lenses = lensDtos, selectedIndex = selectedIndex))
    }

    suspend fun selectLens(body: String): String {
        val request = lensSelectAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid lens selection JSON")
        withContext(Dispatchers.Main) {
            cameraService.selectLens(request.index)
        }
        return successAdapter.toJson(SuccessResponse())
    }

    suspend fun tapFocus(body: String): String {
        val request = tapFocusAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid tap focus JSON")

        val x = request.x.toFloat().coerceIn(0f, 1f)
        val y = request.y.toFloat().coerceIn(0f, 1f)

        withContext(Dispatchers.Main) {
            cameraService.tapToFocus(x, y)
        }

        Log.d(TAG, "Tap focus: x=$x, y=$y")
        return successAdapter.toJson(SuccessResponse())
    }

    suspend fun setZoom(body: String): String {
        val parsed = try { zoomAdapter.fromJson(body) } catch (_: Exception) { null }
        val request = parseBodyOrThrow(parsed, "Invalid zoom JSON (expected {\"zoomRatio\": 2.0})")
        val ratio = request.zoomRatio?.toFloat() ?: request.ratio?.toFloat()
            ?: throw IllegalArgumentException("Invalid zoom JSON (expected {\"zoomRatio\": 2.0})")
        val ok = withContext(Dispatchers.Main) { cameraService.setZoomRatio(ratio) }
        if (!ok) throw IllegalStateException("Zoom not available")
        return successAdapter.toJson(SuccessResponse())
    }

    suspend fun setTorch(body: String): String {
        val parsed = try { torchAdapter.fromJson(body) } catch (_: Exception) { null }
        val request = parseBodyOrThrow(parsed, "Invalid torch JSON (expected {\"enabled\": true})")
        val enabled = request.enabled
            ?: throw IllegalArgumentException("Invalid torch JSON (expected {\"enabled\": true})")
        val ok = withContext(Dispatchers.Main) { cameraService.setTorchEnabled(enabled) }
        if (!ok) throw IllegalStateException("Torch not available")
        return successAdapter.toJson(SuccessResponse())
    }

    /** The one body-parse shape behind setZoom/setTorch: null decode becomes the route's 400. */
    private fun <T> parseBodyOrThrow(parsed: T?, message: String): T =
        parsed ?: throw IllegalArgumentException(message)

    companion object {
        private const val TAG = "LensWebHandler"
    }
}
