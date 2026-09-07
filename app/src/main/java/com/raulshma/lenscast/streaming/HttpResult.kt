package com.raulshma.lenscast.streaming

import java.io.InputStream

/**
 * The transport-neutral answer of a responder module. Responder modules
 * ([HttpAuthFilter], `StaticAssetStore`, `MjpegStreamPump`, `MediaResponder`)
 * decide status, mime type, headers, and payload as pure data; the
 * [StreamingServer] module translates the value onto NanoHTTPD responses in
 * one place and applies the security headers.
 */
data class HttpResult(
    val statusCode: Int,
    val mimeType: String,
    val body: ResponseBody,
    val headers: Map<String, String> = emptyMap(),
) {
    sealed interface ResponseBody {
        data class Text(val text: String) : ResponseBody
        data class Bytes(val bytes: ByteArray) : ResponseBody
        data class Stream(val stream: InputStream, val contentLength: Long?) : ResponseBody
    }

    companion object {
        fun jsonError(statusCode: Int, message: String): HttpResult = HttpResult(
            statusCode = statusCode,
            mimeType = "application/json",
            body = ResponseBody.Text("""{"error":"$message"}"""),
        )

        fun plainText(statusCode: Int, text: String): HttpResult = HttpResult(
            statusCode = statusCode,
            mimeType = "text/plain",
            body = ResponseBody.Text(text),
        )

        fun streamingDisabled(): HttpResult = plainText(503, "Web streaming is disabled")
    }
}
