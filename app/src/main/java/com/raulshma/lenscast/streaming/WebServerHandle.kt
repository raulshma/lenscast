package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit

/**
 * The live web transport as [WebTransport] drives it. [StreamingServer]
 * implements it — the interface adds no members the server does not already
 * expose, so the transport's swap seam costs the real server nothing — and
 * JVM tests substitute a fake that records the calls
 * ([com.raulshma.lenscast.streaming.WebTransportTest]).
 *
 * The media/client members carry no-op defaults so a fake only needs what
 * its test exercises (the [RtspServerHandle] pattern): the replace-cycle
 * tests drive [startServer]/[stopServer]/[notifyTransportSwitch] alone.
 */
internal interface WebServerHandle {
    /**
     * Binds and serves. False when the start failed — the port may still be
     * held (NanoHTTPD binds on its server thread before the call reports the
     * failure), so [stopServer] must run for a failed attempt too.
     */
    fun startServer(): Boolean

    /**
     * Releases the socket. Safe on an instance whose start was attempted but
     * failed (the "stop before the next bind" recovery), and a no-op on a
     * cleanly-stopped one.
     */
    fun stopServer()

    /**
     * Tells every open dashboard that the listener about to replace this one
     * speaks [scheme] (http/https), so the page can reload onto it instead of
     * sitting on a dead socket after a TLS toggle. True when the notice was
     * queued for at least one dashboard.
     */
    fun notifyTransportSwitch(scheme: String): Boolean

    /** One rendered M-JPEG frame in (no-op while no viewer is pulling). */
    fun updateFrame(jpeg: ByteArray) {}

    /** The connected MJPEG client count (0 on a fake that does not track viewers). */
    fun getClientCount(): Int = 0

    /** The connected MJPEG client ids, for the Web API's clients list. */
    fun httpClientIds(): List<String> = emptyList()

    /** True kick: close one MJPEG client by id; false when no such client. */
    fun kickHttpClient(clientId: String): Boolean = false

    /** The web-stream toggle lands on the MJPEG pump (a stopped pump serves 404s). */
    fun setWebStreamingEnabled(enabled: Boolean) {}

    /** The encoded hub's WS video fan-out (same-port `/ws/video` clients); no-op while stopped. */
    fun feedVideo(nalUnits: List<EncodedNalUnit>) {}

    /** Live `/ws/video` client count — the encoded-stream policy's WS input. */
    fun videoClientCount(): Int = 0
}
