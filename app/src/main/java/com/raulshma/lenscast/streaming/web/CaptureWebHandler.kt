package com.raulshma.lenscast.streaming.web

import android.util.Log
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.streaming.model.CaptureResponse

/** /api/capture — photo capture over the Web API, choreographed by PhotoCaptureManager. */
class CaptureWebHandler(private val photoCaptureManager: PhotoCaptureManager) {

    private val responseAdapter by lazy { WebJson.moshi.adapter(CaptureResponse::class.java) }

    suspend fun capturePhoto(): String {
        val fileName = photoCaptureManager.captureToGallery(
            onSaved = { _, _ -> Log.d(TAG, "Photo captured via web") },
            onError = { Log.e(TAG, "Web capture failed", it) },
        )
        return if (fileName == null) {
            responseAdapter.toJson(CaptureResponse(success = false, error = "Camera not available"))
        } else {
            responseAdapter.toJson(CaptureResponse(success = true, fileName = fileName))
        }
    }

    suspend fun highResSnapshot(saveToDisk: Boolean): PhotoCaptureManager.SnapshotResult =
        photoCaptureManager.captureSnapshot(saveToDisk)

    private companion object {
        const val TAG = "CaptureWebHandler"
    }
}
