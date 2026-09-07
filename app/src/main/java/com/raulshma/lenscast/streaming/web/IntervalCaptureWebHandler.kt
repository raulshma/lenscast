package com.raulshma.lenscast.streaming.web

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.capture.IntervalCaptureScheduler
import com.raulshma.lenscast.capture.model.IntervalCaptureConfig
import com.raulshma.lenscast.streaming.model.IntervalCaptureStatusDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** /api/capture/interval/... — WorkManager-driven interval capture control. */
class IntervalCaptureWebHandler(private val context: Context) {

    private val statusAdapter by lazy { WebJson.moshi.adapter(IntervalCaptureStatusDto::class.java) }
    private val configAdapter by lazy { WebJson.moshi.adapter(IntervalCaptureConfig::class.java) }
    private val successAdapter by lazy { WebJson.moshi.adapter(SuccessResponse::class.java) }

    suspend fun status(): String {
        // WorkManager's status query blocks; keep it off the caller's thread.
        val snapshot = withContext(Dispatchers.IO) { IntervalCaptureScheduler.getStatus(context) }
        return statusAdapter.toJson(
            IntervalCaptureStatusDto(
                isRunning = snapshot.isRunning,
                completedCaptures = snapshot.completedCaptures,
            )
        )
    }

    suspend fun start(body: String): String {
        val config = configAdapter.fromJson(if (body.isNotEmpty()) body else "{}")
            ?: IntervalCaptureConfig()

        IntervalCaptureScheduler.start(
            context = context,
            intervalSeconds = config.intervalSeconds.coerceIn(1, 3600),
            totalCaptures = config.totalCaptures,
            flashMode = config.flashMode.name,
            completedCaptures = 0,
        )

        Log.d(TAG, "Interval capture started: every ${config.intervalSeconds}s, total=${config.totalCaptures}, flash=${config.flashMode}")
        return successAdapter.toJson(SuccessResponse())
    }

    suspend fun stop(): String {
        IntervalCaptureScheduler.stop(context)
        Log.d(TAG, "Interval capture stopped")
        return successAdapter.toJson(SuccessResponse())
    }

    companion object {
        private const val TAG = "IntervalCaptureWebHandler"
    }
}
