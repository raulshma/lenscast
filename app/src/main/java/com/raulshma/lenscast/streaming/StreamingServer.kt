package com.raulshma.lenscast.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class StreamingServer(
    private val port: Int = 8080,
) : NanoHTTPD(port) {

    private val boundary = "LensCastBoundary"
    private val clientCount = AtomicInteger(0)
    private var latestJpeg: ByteArray? = null
    private val listeners = ConcurrentLinkedQueue<(ByteArray?) -> Unit>()
    private var isRunning = false
    var authUsername: String? = null
    var authPassword: String? = null

    private val controlPageHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>LensCast - IPTV Camera</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body { font-family: sans-serif; max-width: 600px; margin: 40px auto; padding: 0 20px; background: #1a1a2e; color: #e0e0e0; }
            h1 { color: #64b5f6; }
            a { color: #81d4fa; display: block; margin: 10px 0; padding: 12px; background: #16213e; border-radius: 8px; text-decoration: none; }
            a:hover { background: #0f3460; }
            .info { color: #aaa; font-size: 14px; margin-top: 20px; }
        </style>
        </head>
        <body>
            <h1>LensCast Camera</h1>
            <a href="/stream">MJPEG Stream</a>
            <a href="/snapshot">Snapshot</a>
            <a href="/settings">Settings (JSON)</a>
            <p class="info">Stream URL: /stream | Snapshot: /snapshot | Settings: /settings</p>
        </body>
        </html>
    """.trimIndent()

    fun updateFrame(jpegData: ByteArray) {
        latestJpeg = jpegData
        for (listener in listeners) {
            listener(jpegData)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        if (!checkAuth(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "text/plain",
                "Unauthorized"
            ).apply {
                addHeader("WWW-Authenticate", "Basic realm=\"LensCast\"")
            }
        }
        return when (session.uri) {
            "/" -> newFixedLengthResponse(Response.Status.OK, "text/html", controlPageHtml)
            "/stream" -> serveMjpegStream()
            "/snapshot" -> serveSnapshot()
            "/settings" -> serveSettings(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun checkAuth(session: IHTTPSession): Boolean {
        val username = authUsername ?: return true
        val password = authPassword ?: return true
        if (username.isEmpty()) return true

        val authHeader = session.headers["authorization"] ?: return false
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return false

        val encoded = authHeader.substring(6).trim()
        val decoded: String
        try {
            decoded = String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (_: Exception) {
            return false
        }

        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return false
        return parts[0] == username && parts[1] == password
    }

    private fun serveMjpegStream(): Response {
        val clientNum = clientCount.incrementAndGet()
        Log.d(TAG, "Client connected. Total: $clientNum")

        val stream = object : InputStream() {
            private val buffer = ByteArrayOutputStream()
            private var frameQueue = ConcurrentLinkedQueue<ByteArray>()
            private var currentFrame: ByteArrayInputStream? = null
            private var listener: (ByteArray?) -> Unit = {}

            init {
                listener = { data ->
                    if (data != null) {
                        frameQueue.add(data)
                    }
                }
                listeners.add(listener)

                latestJpeg?.let { frameQueue.add(it) }
            }

            override fun read(): Int {
                while (true) {
                    currentFrame?.let { stream ->
                        val b = stream.read()
                        if (b != -1) return b
                        currentFrame = null
                    }

                    val frame = frameQueue.poll()
                    if (frame != null) {
                        buffer.reset()
                        val header = "\r\n--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        val headerBytes = header.toByteArray()
                        val combined = ByteArray(headerBytes.size + frame.size + "\r\n".toByteArray().size)
                        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.size)
                        System.arraycopy(frame, 0, combined, headerBytes.size, frame.size)
                        val ending = "\r\n".toByteArray()
                        System.arraycopy(ending, 0, combined, headerBytes.size + frame.size, ending.size)
                        currentFrame = ByteArrayInputStream(combined)
                    } else {
                        Thread.sleep(16)
                    }
                }
            }

            override fun close() {
                listeners.remove(listener)
                val num = clientCount.decrementAndGet()
                Log.d(TAG, "Client disconnected. Total: $num")
            }
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            stream
        )
    }

    private fun serveSnapshot(): Response {
        val jpeg = latestJpeg
        return if (jpeg != null) {
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No frame available")
        }
    }

    private fun serveSettings(session: IHTTPSession): Response {
        val clients = clientCount.get()
        val hasFrame = latestJpeg != null
        val json = """{"streaming":$hasFrame,"clients":$clients}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    fun getClientCount(): Int = clientCount.get()

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.d(TAG, "Streaming server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            false
        }
    }

    fun stopServer() {
        if (isRunning) {
            stop()
            isRunning = false
            listeners.clear()
            Log.d(TAG, "Streaming server stopped")
        }
    }

    companion object {
        private const val TAG = "StreamingServer"
    }
}
