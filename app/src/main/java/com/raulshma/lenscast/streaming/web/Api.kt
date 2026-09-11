package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.SessionRole
import com.raulshma.lenscast.streaming.model.ErrorResponse

/** HTTP methods the Web API router understands — transport-agnostic on purpose. */
enum class ApiMethod { GET, PUT, POST, DELETE }

/**
 * Everything a handler needs to know about a request. Transport details
 * (nanohttpd sessions, headers, streaming bodies) stay in StreamingServer.
 * [sessionRole] is the authenticated caller's role — ADMIN for API-token and
 * auth-off requests, since a token holder is admin and the role gate runs
 * only on the cookie path.
 */
data class ApiRequest(
    val method: ApiMethod,
    val path: String,
    val body: String = "",
    val query: Map<String, String> = emptyMap(),
    val sessionRole: SessionRole = SessionRole.ADMIN,
)

/**
 * A handler's answer. JSON handler responses are always HTTP 200 with the
 * outcome encoded in the payload (the web client's contract); non-200 codes
 * are reserved for routing and transport failures.
 */
data class ApiResponse(
    val httpStatus: Int,
    val contentType: String,
    val body: String,
) {
    companion object {
        private val errorAdapter = AppJson.moshi.adapter(ErrorResponse::class.java)

        fun ok(json: String) = ApiResponse(200, "application/json", json)

        fun notFound() = ApiResponse(404, "application/json", """{"error":"Not found"}""")

        fun error(e: Exception): String {
            val msg = e.message?.take(200)?.replace('\n', ' ') ?: "Internal error"
            return errorAdapter.toJson(ErrorResponse(error = msg))
        }
    }
}
