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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** /api/camera/... — lens enumeration/selection and tap-to-focus. */
class LensWebHandler(private val cameraService: CameraService) {

    private val lensesAdapter by lazy { AppJson.moshi.adapter(LensesResponseDto::class.java) }
    private val lensSelectAdapter by lazy { AppJson.moshi.adapter(LensSelectRequest::class.java) }
    private val tapFocusAdapter by lazy { AppJson.moshi.adapter(TapFocusRequest::class.java) }
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

    companion object {
        private const val TAG = "LensWebHandler"
    }
}
