package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.streaming.web.ApiRouter
import com.raulshma.lenscast.streaming.web.AuthWebHandler
import com.raulshma.lenscast.streaming.web.DetectionEventsWebHandler
import com.raulshma.lenscast.streaming.web.DeterrenceWebHandler
import com.raulshma.lenscast.streaming.web.GalleryWebHandler
import com.raulshma.lenscast.streaming.web.StatusWebHandler

/**
 * The Web API modules handed to each StreamingServer at construction. The
 * transport layer receives them at the seam; it never grows its own.
 */
data class WebApiStack(
    val router: ApiRouter,
    val status: StatusWebHandler,
    val gallery: GalleryWebHandler,
    val capture: PhotoCaptureManager,
    val deterrence: DeterrenceWebHandler,
    val auth: AuthWebHandler,
    val detectionEvents: DetectionEventsWebHandler,
)
