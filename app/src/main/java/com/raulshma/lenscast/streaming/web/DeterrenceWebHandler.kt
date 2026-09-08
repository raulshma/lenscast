package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.SirenPlayer
import com.raulshma.lenscast.streaming.model.SuccessResponse

/**
 * POST /api/deterrence — remote siren toggle for the security-camera loop.
 * The spotlight half of deterrence is the existing torch route; this handler
 * owns only the siren so the audio lifecycle stays in one place.
 */
class DeterrenceWebHandler(private val sirenPlayer: SirenPlayer) {

    private val requestAdapter by lazy { AppJson.moshi.adapter(DeterrenceRequest::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    fun setSiren(body: String): String {
        val request = runCatching { requestAdapter.fromJson(body) }.getOrNull()
            ?: return successAdapter.toJson(SuccessResponse(success = false))
        if (request.siren == true) {
            sirenPlayer.start()
        } else {
            sirenPlayer.stop()
        }
        return successAdapter.toJson(SuccessResponse())
    }

    data class DeterrenceRequest(val siren: Boolean? = null)
}
