package com.raulshma.lenscast.streaming.ws

import android.util.Log
import com.raulshma.lenscast.streaming.AudioStreamingManager
import com.raulshma.lenscast.streaming.WebAuthGate
import fi.iki.elonen.NanoWSD
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The WebSocket sidecar on the streaming server's port: `/ws/video` pushes
 * H.264 AUs (AVCC-framed, configured via a cached avcC) for WebCodecs
 * playback — sub-second latency at a fraction of MJPEG's bandwidth — and
 * `/ws/talkback` takes continuous PCM16 chunks for push-to-talk.
 *
 * Handshakes pass the same [WebAuthGate] as the HTTP surface: auth off lets
 * everything through, auth on requires the session cookie (SameSite=Lax
 * keeps a cross-site page's handshake from attaching it). A rejected
 * handshake throws, which aborts the upgrade with an HTTP error — no 101,
 * no media.
 *
 * Video frames arrive through [feedVideo], the second sink the RTSP output
 * fans out to alongside the HLS ring. Clients joining mid-stream receive the
 * cached parameter sets with their config message and wait for the next
 * keyframe, so no keyframe storms are requested from the encoder.
 */
class WsMediaServer(
    private val bindPort: Int,
    private val audioStreamingManager: AudioStreamingManager,
    private val authGate: WebAuthGate,
) : NanoWSD(bindPort) {

    private val videoClients = CopyOnWriteArrayList<VideoSocket>()
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null

    /** The fan-out sink; wired once by the Streaming Manager at the RTSP output. */
    fun videoSink(): (List<com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit>) -> Unit =
        { nalUnits -> feedVideo(nalUnits) }

    fun feedVideo(nalUnits: List<com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return
        val raw = nalUnits.map { it.data }
        WsVideoProtocol.extractParameterSets(raw)?.let { (sps, pps) ->
            cachedSps = sps
            cachedPps = pps
        }
        if (videoClients.isEmpty()) return
        val isKey = WsVideoProtocol.containsKeyframe(nalUnits)
        val wsFrame = WebSocketFrame(
            WebSocketFrame.OpCode.Binary, true,
            WsVideoProtocol.videoFrameAvcc(WsVideoProtocol.nalUnitsToAvcc(raw), isKey),
        )
        videoClients.forEach { client ->
            runCatching { client.sendFrame(wsFrame) }
        }
    }

    /**
     * Non-null when the dashboard runs HTTPS: the sidecar must serve wss,
     * or browsers refuse the mixed-content ws:// connection.
     */
    @Volatile var tlsServerSocketFactory: javax.net.ssl.SSLServerSocketFactory? = null

    fun startServer(): Boolean = try {
        tlsServerSocketFactory?.let { makeSecure(it, null) }
        start(SOCKET_READ_TIMEOUT, true)
        Log.d(TAG, "WS media server started on $bindPort")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start WS media server", e)
        false
    }

    fun stopServer() {
        stop()
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val path = handshake.uri?.substringBefore("?") ?: ""
        if (path != VIDEO_PATH && path != TALKBACK_PATH) {
            throw IllegalArgumentException("Unknown WS path: $path")
        }
        if (!authGate.authenticate(handshake.headers["cookie"])) {
            throw IllegalStateException("WS handshake rejected on $path: authentication required")
        }
        return when (path) {
            VIDEO_PATH -> VideoSocket(handshake)
            else -> TalkbackSocket(handshake)
        }
    }

    private inner class VideoSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        init {
            videoClients.add(this)
        }

        override fun onOpen() {
            val sps = cachedSps
            val pps = cachedPps
            if (sps != null && pps != null) {
                runCatching {
                    sendFrame(WebSocketFrame(WebSocketFrame.OpCode.Binary, true, WsVideoProtocol.videoConfig(sps, pps)))
                }
            }
            Log.d(TAG, "WS video client connected (${videoClients.size})")
        }

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {
            videoClients.remove(this)
            Log.d(TAG, "WS video client gone (${videoClients.size})")
        }

        override fun onMessage(message: WebSocketFrame) = Unit

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: java.io.IOException) {
            Log.w(TAG, "WS video client error: ${exception.message}")
        }
    }

    private inner class TalkbackSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        override fun onOpen() = Unit

        override fun onClose(code: WebSocketFrame.CloseCode, reason: String?, initiatedByRemote: Boolean) {
            audioStreamingManager.stopTalkback()
        }

        override fun onMessage(message: WebSocketFrame) {
            // First binary after a gap (re)opens the speaker stream; the
            // client signals end-of-talk with a zero-length payload or a
            // text "stop".
            when (message.opCode) {
                WebSocketFrame.OpCode.Binary -> {
                    val payload = message.binaryPayload ?: return
                    if (payload.isEmpty()) {
                        audioStreamingManager.stopTalkback()
                    } else if (!audioStreamingManager.uplinkStreamingActive()) {
                        audioStreamingManager.startUplinkStream()
                        audioStreamingManager.writeUplinkStream(payload)
                    } else {
                        audioStreamingManager.writeUplinkStream(payload)
                    }
                }
                WebSocketFrame.OpCode.Text -> {
                    if (message.textPayload == "stop") audioStreamingManager.stopTalkback()
                }
                else -> Unit
            }
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: java.io.IOException) {
            Log.w(TAG, "WS talkback error: ${exception.message}")
            audioStreamingManager.stopTalkback()
        }
    }

    companion object {
        private const val TAG = "WsMediaServer"
        const val VIDEO_PATH = "/ws/video"
        const val TALKBACK_PATH = "/ws/talkback"
    }
}
