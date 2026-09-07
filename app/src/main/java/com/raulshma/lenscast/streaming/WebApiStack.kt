package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.streaming.web.ApiRouter
import com.raulshma.lenscast.streaming.web.GalleryWebHandler

/**
 * The Web API modules handed to each StreamingServer at construction. The
 * transport layer receives them at the seam; it never grows its own.
 */
data class WebApiStack(
    val router: ApiRouter,
    val gallery: GalleryWebHandler,
    val capture: PhotoCaptureManager,
)
