package com.raulshma.lenscast.streaming

import java.io.InputStream
import java.util.Locale

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

        /**
         * The one no-store triple: the full do-not-cache contract for live or
         * per-request content. Any responder that used to spell out the
         * Cache-Control/Pragma/Expires trio references this instead — DTO-shape
         * JSON errors are produced by serializers ([com.raulshma.lenscast.core.AppJson]),
         * and the `{"error":…}` shape by [jsonError] below; never interpolate a
         * payload by hand.
         */
        val NO_STORE_HEADERS: Map<String, String> = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma" to "no-cache",
            "Expires" to "0",
        )

        /** The `{"error":"…"}` JSON error answer, with the message escaped by [escapeJsonString]. */
        fun jsonError(statusCode: Int, message: String): HttpResult = HttpResult(
            statusCode = statusCode,
            mimeType = "application/json",
            body = ResponseBody.Text("""{"error":"${escapeJsonString(message)}"}"""),
        )

        /**
         * The one JSON string escaper: quotes, backslashes, and every control
         * character become their `\uXXXX` (or short) escape, so an error
         * message can never break out of the JSON string it lives in.
         */
        internal fun escapeJsonString(value: String): String {
            val out = StringBuilder(value.length + 8)
            for (c in value) {
                when (c) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else ->
                        if (c < ' ') out.append(String.format(Locale.US, "\\u%04x", c.code))
                        else out.append(c)
                }
            }
            return out.toString()
        }

        fun plainText(statusCode: Int, text: String): HttpResult = HttpResult(
            statusCode = statusCode,
            mimeType = "text/plain",
            body = ResponseBody.Text(text),
        )

        fun streamingDisabled(): HttpResult = plainText(503, "Web streaming is disabled")
    }
}
