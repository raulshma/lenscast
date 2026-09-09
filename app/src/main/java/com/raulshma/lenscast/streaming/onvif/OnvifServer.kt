package com.raulshma.lenscast.streaming.onvif

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.RtspUriPolicy
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import java.util.UUID

/**
 * The ONVIF Profile S device service: owns the [WsDiscoveryResponder] and
 * answers the HTTP-side SOAP requests through the pure [OnvifResponses]
 * builders. Configuration arrives as live providers (the same sources the
 * mDNS registration and the RTSP/snapshot URL building read), so a port,
 * resolution, or audio change is reflected on the next request with no
 * restart of anything.
 *
 * [start]/[stop] are idempotent and own only the UDP responder — the HTTP
 * side is served by the streaming server's `/onvif/device_service` dispatch
 * branch, which delegates here through [handle].
 *
 * The whole surface is opt-in (`onvifEnabled`): the toggle starts/stops the
 * discovery responder, and [handle] answers a fault while disabled. Served
 * WITHOUT authentication: ONVIF clients on the LAN cannot hold the web
 * session cookie, so gating the endpoint per-request would disable ONVIF
 * entirely. The endpoint answers only static device metadata plus the two
 * URIs; the RTSP stream itself keeps its own Digest/Basic auth and the
 * snapshot route stays behind the web gate.
 */
class OnvifServer(
    /** The device's LAN address, re-read per request (the mDNS/RTSP source). */
    private val ipAddress: () -> String?,
    private val rtspPort: () -> Int,
    private val webPort: () -> Int,
    private val audioEnabled: () -> Boolean,
    /** The device service's on/off toggle; while off, discovery is stopped and [handle] faults. */
    private val enabled: () -> Boolean = { true },
    private val httpsEnabled: () -> Boolean = { false },
    private val videoWidth: () -> Int = { StreamDefaults.RTSP_VIDEO_WIDTH },
    private val videoHeight: () -> Int = { StreamDefaults.RTSP_VIDEO_HEIGHT },
    private val videoBitrate: () -> Int = { StreamDefaults.RTSP_VIDEO_BITRATE },
    private val videoFps: () -> Int = { StreamDefaults.STREAM_FPS },
    private val videoCodec: () -> RtspVideoCodec = { RtspVideoCodec.H264 },
    private val firmwareVersion: String,
    private val model: String = DEFAULT_MODEL,
    private val serialNumber: String = "",
    /** Injected for JVM tests; production uses the wall clock. */
    private val now: () -> Long = System::currentTimeMillis,
    /** App context for the discovery multicast lock; null skips the lock. */
    private val context: Context? = null,
) {

    /** Stable per-instance discovery identity — generated once, reused forever. */
    private val endpointUrn: String = "urn:uuid:" + UUID.randomUUID()

    private val discoveryScopes: String = listOf(
        "onvif://www.onvif.org/type/video_encoder",
        "onvif://www.onvif.org/type/audio_encoder",
        "onvif://www.onvif.org/hardware/android",
        "onvif://www.onvif.org/name/" + model.replace(' ', '_'),
    ).joinToString(" ")

    private var responder: WsDiscoveryResponder? = null

    /** Idempotent start: brings up the WS-Discovery responder. */
    @Synchronized
    fun start() {
        if (responder != null) return
        responder = WsDiscoveryResponder(
            context = context,
            endpointAddress = endpointUrn,
            xaddrs = { deviceServiceUrl() },
            scopes = discoveryScopes,
        ).also { it.start() }
    }

    /** Idempotent stop: tears the responder down; the HTTP side is stateless. */
    @Synchronized
    fun stop() {
        responder?.stop()
        responder = null
    }

    /**
     * The HTTP-side entry the StreamingServer dispatch branch calls.
     * A null/blank body (the bodyless GET some clients probe with) answers
     * GetSystemDateAndTime; otherwise the parsed operation selects the
     * builder, and anything unknown or malformed gets the shared
     * `ter:ActionNotSupported` fault.
     */
    fun handle(body: String?): String {
        if (!enabled()) return OnvifResponses.fault("The device service is disabled")
        val operation = OnvifRequestParser.operation(body)
        return when (operation) {
            null ->
                if (body.isNullOrBlank()) OnvifResponses.getSystemDateAndTime(now())
                else OnvifResponses.fault("Malformed SOAP body")
            "GetSystemDateAndTime" -> OnvifResponses.getSystemDateAndTime(now())
            "GetCapabilities" -> OnvifResponses.getCapabilities(deviceServiceUrl())
            "GetServices" -> OnvifResponses.getServices(deviceServiceUrl())
            "GetDeviceInformation" -> OnvifResponses.getDeviceInformation(
                manufacturer = MANUFACTURER,
                model = model,
                firmwareVersion = firmwareVersion,
                serialNumber = serialNumber,
                hardwareId = HARDWARE_ID,
            )
            "GetVideoSources" -> OnvifResponses.getVideoSources(
                width = videoWidth(),
                height = videoHeight(),
                fps = videoFps(),
            )
            "GetProfiles" -> OnvifResponses.getProfiles(
                width = videoWidth(),
                height = videoHeight(),
                videoBitrate = videoBitrate(),
                fps = videoFps(),
                audioEnabled = audioEnabled(),
                videoCodec = videoCodec(),
            )
            "GetStreamUri" -> OnvifResponses.getStreamUri(rtspUri())
            "GetSnapshotUri" -> OnvifResponses.getSnapshotUri(snapshotUri())
            else -> OnvifResponses.fault("Unsupported operation: $operation")
        }
    }

    /** The device service URL advertised everywhere (GetServices/capabilities/discovery). */
    private fun deviceServiceUrl(): String =
        "${webScheme()}://${host()}:${webPort()}$DEVICE_SERVICE_PATH"

    private fun rtspUri(): String =
        "rtsp://${host()}:${rtspPort()}/${RtspUriPolicy.DEFAULT_STREAM_PATH}"

    private fun snapshotUri(): String =
        "${webScheme()}://${host()}:${webPort()}/snapshot"

    /** HTTPS mode serves the web port as TLS-only, so every advertised web URL must match. */
    private fun webScheme(): String = if (httpsEnabled()) "https" else "http"

    /** Bracketed IPv6, plain IPv4 — the same URL-host rule NetworkUtils owns. */
    private fun host(): String =
        NetworkUtils.formatHostForUrl(ipAddress() ?: NO_ADDRESS)

    companion object {
        private const val TAG = "OnvifServer"
        const val MANUFACTURER = "LensCast"
        const val HARDWARE_ID = "android"
        const val DEVICE_SERVICE_PATH = "/onvif/device_service"
        const val DEFAULT_MODEL = "LensCast"
        private const val NO_ADDRESS = "127.0.0.1"

        /**
         * Process-wide composition hook. `StreamingManager` builds the HTTP
         * servers and must not grow device-metadata concerns, so it cannot
         * construct this service the way it grows the Web API stack — the
         * composition root (MainApplication) builds it once and composes it
         * here, and the server's constructor default resolves [shared].
         * Tests inject their own instance through the constructor instead.
         */
        @Volatile
        private var composed: OnvifServer? = null

        /** Registers the composition root's instance as the process default. */
        fun compose(instance: OnvifServer) {
            composed = instance
        }

        /**
         * The composed instance, or a best-effort fallback for the window
         * before composition (defaults only — MainApplication composes the
         * real one synchronously during `onCreate`, before any server exists).
         */
        val shared: OnvifServer
            get() = composed ?: fallback

        private val fallback: OnvifServer by lazy {
            Log.w(TAG, "ONVIF answering through the pre-composition fallback")
            OnvifServer(
                ipAddress = { NetworkUtils.getLocalIpAddress() },
                rtspPort = { StreamDefaults.RTSP_PORT },
                webPort = { StreamDefaults.WEB_PORT },
                audioEnabled = { false },
                firmwareVersion = "unknown",
            )
        }
    }
}
