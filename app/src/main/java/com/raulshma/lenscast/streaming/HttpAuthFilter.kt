package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.HttpResult.ResponseBody.Text

/**
 * The HTTP translation of the [WebAuthGate] policy: the four `/api/auth/`
 * routes plus the authenticate-then-CSRF gate for protected routes. The auth
 * decisions themselves stay behind the gate's seam; this module only maps
 * requests to gate calls and gate answers to status codes, bodies, and
 * cookie headers. The [StreamingServer] module reads the request body off
 * the socket and applies the security headers.
 */
class HttpAuthFilter(
    private val webAuthGate: WebAuthGate,
    private val port: Int,
) {

    /** True for the login route, whose body the transport reads first. */
    fun isLoginRoute(method: String, uri: String): Boolean =
        method == "POST" && uri == "/api/auth/login"

    /**
     * Auth routes that need no body. Null when [uri]+[method] is not one of
     * them (including the login route — see [handleLogin]).
     */
    fun handleBodylessAuthRoute(
        method: String,
        uri: String,
        headers: Map<String, String?>,
    ): HttpResult? = when {
        uri == "/api/auth/status" -> HttpResult(
            statusCode = 200,
            mimeType = "application/json",
            body = Text("""{"required":${webAuthGate.isEnabled}}"""),
            headers = NO_STORE,
        )
        uri == "/api/auth/session" && method == "GET" -> HttpResult(
            statusCode = 200,
            mimeType = "application/json",
            body = Text("""{"authenticated":${webAuthGate.authenticate(headers["cookie"])}}"""),
            headers = NO_STORE,
        )
        uri == "/api/auth/logout" && method == "POST" -> handleLogout(headers)
        else -> null
    }

    /**
     * The login route. [contentLength] and [body] are what the transport
     * read off the socket with its single-read semantics.
     */
    fun handleLogin(
        remoteIp: String?,
        contentLength: Int,
        body: ByteArray,
    ): HttpResult {
        if (!webAuthGate.isEnabled) {
            return HttpResult(
                statusCode = 200,
                mimeType = "application/json",
                body = Text("""{"required":false}"""),
                headers = NO_STORE,
            )
        }
        if (contentLength <= 0) {
            return HttpResult.jsonError(400, "Missing request body")
        }
        val credentials = parseLoginCredentials(body)
            ?: return HttpResult.jsonError(400, "Invalid request")
        if (credentials.username.isEmpty() || credentials.password.isEmpty()) {
            return HttpResult.jsonError(400, "Missing credentials")
        }
        val result = webAuthGate.login(remoteIp, credentials.username, credentials.password)
        if (!result.success) {
            // The typed reason picks the status; the gate's message is the
            // client's error payload, forwarded verbatim.
            val status = if (result.failure == WebAuthGate.LoginFailure.NotConfigured) 500 else 401
            return HttpResult.jsonError(status, result.error.orEmpty())
        }
        return HttpResult(
            statusCode = 200,
            mimeType = "application/json",
            body = Text("""{"success":true}"""),
            headers = NO_STORE + (
                "Set-Cookie" to
                    "${WebAuthGate.COOKIE_NAME}=${result.token}; " +
                    "Path=${WebAuthGate.COOKIE_PATH}; " +
                    "Max-Age=${WebAuthGate.SESSION_MAX_AGE_SEC}; HttpOnly; SameSite=Lax"
                ),
        )
    }

    /**
     * The protected-route gate. Null when the request may proceed: the
     * route is public, or the cookie authenticates and state-changing
     * methods pass the CSRF check.
     */
    fun authorize(
        method: String,
        uri: String,
        headers: Map<String, String?>,
    ): HttpResult? {
        if (!isProtectedRoute(uri)) return null
        if (!webAuthGate.authenticate(headers["cookie"])) {
            return HttpResult.jsonError(401, "Authentication required")
        }
        if (method != "GET" && !isCsrfSafe(headers)) {
            return HttpResult.jsonError(403, "CSRF check failed")
        }
        return null
    }

    fun isProtectedRoute(uri: String): Boolean =
        uri.startsWith("/api/") || uri == "/stream" || uri == "/audio" ||
            uri.startsWith("/snapshot")

    private fun handleLogout(headers: Map<String, String?>): HttpResult {
        if (!webAuthGate.authenticate(headers["cookie"])) {
            return HttpResult.jsonError(401, "Authentication required")
        }
        if (!isCsrfSafe(headers)) {
            return HttpResult.jsonError(403, "CSRF check failed")
        }
        webAuthGate.logout(webAuthGate.tokenFromCookie(headers["cookie"]))
        return HttpResult(
            statusCode = 200,
            mimeType = "application/json",
            body = Text("""{"success":true}"""),
            headers = NO_STORE + (
                "Set-Cookie" to
                    "${WebAuthGate.COOKIE_NAME}=; Path=${WebAuthGate.COOKIE_PATH}; " +
                    "Max-Age=0; HttpOnly; SameSite=Lax"
                ),
        )
    }

    private fun isCsrfSafe(headers: Map<String, String?>): Boolean =
        webAuthGate.isCsrfSafe(
            originHeader = headers["origin"] ?: headers["referer"],
            hasRequestedWithHeader = headers.containsKey("x-requested-with"),
            port = port,
        )

    data class LoginCredentials(val username: String, val password: String)

    companion object {
        private val NO_STORE = mapOf("Cache-Control" to "no-store")

        /**
         * The flat `{"username","password"}` string fields of a login body.
         * A dependency-free reader for two string fields — full escape
         * handling, last-wins on duplicates — so this module stays testable
         * without a socket or a JSON runtime. Null when the body is not
         * exactly one JSON object: trailing garbage and raw control
         * characters fail closed rather than parsing a prefix.
         */
        internal fun parseLoginCredentials(body: ByteArray): LoginCredentials? {
            val text = String(body, Charsets.UTF_8)
            val parsed = parseStringFields(text) ?: return null
            return LoginCredentials(
                username = parsed["username"] ?: "",
                password = parsed["password"] ?: "",
            )
        }

        private fun parseStringFields(text: String): Map<String, String>? {
            val reader = JsonReader(text)
            reader.skipWhitespace()
            if (!reader.consume('{')) return null
            val fields = mutableMapOf<String, String>()
            reader.skipWhitespace()
            if (reader.consume('}')) return reader.endOfInput()?.let { fields }
            while (true) {
                reader.skipWhitespace()
                val key = reader.readString() ?: return null
                reader.skipWhitespace()
                if (!reader.consume(':')) return null
                reader.skipWhitespace()
                fields[key] = reader.readValueAsText() ?: return null
                reader.skipWhitespace()
                if (reader.consume(',')) continue
                if (reader.consume('}')) return reader.endOfInput()?.let { fields }
                return null
            }
        }

        private class JsonReader(val text: String) {
            var pos = 0

            fun skipWhitespace() {
                while (pos < text.length && text[pos].isWhitespace()) pos++
            }

            /** Non-null after a complete parse: only whitespace may follow. */
            fun endOfInput(): Unit? {
                skipWhitespace()
                return if (pos == text.length) Unit else null
            }

            fun consume(c: Char): Boolean {
                if (pos < text.length && text[pos] == c) {
                    pos++
                    return true
                }
                return false
            }

            fun readString(): String? {
                if (!consume('"')) return null
                val out = StringBuilder()
                while (pos < text.length) {
                    val c = text[pos++]
                    when (c) {
                        '"' -> return out.toString()
                        '\\' -> {
                            if (pos >= text.length) return null
                            when (val e = text[pos++]) {
                                '"', '\\', '/' -> out.append(e)
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (pos + 4 > text.length) return null
                                    val hex = text.substring(pos, pos + 4)
                                    val code = hex.toIntOrNull(16) ?: return null
                                    out.append(code.toChar())
                                    pos += 4
                                }
                                else -> return null
                            }
                        }
                        // Raw control characters are never valid in JSON strings.
                        in '\u0000'..'\u001F' -> return null
                        else -> out.append(c)
                    }
                }
                return null
            }

            /**
             * A field value as the client's contract reads it: quoted
             * strings verbatim, `null` as empty, other literals raw.
             * Nested values are consumed balanced and passed through raw —
             * like the previous coercion, they never match credentials.
             */
            fun readValueAsText(): String? {
                if (pos < text.length && text[pos] == '"') return readString()
                if (pos < text.length && (text[pos] == '{' || text[pos] == '[')) {
                    return readBalanced()
                }
                val start = pos
                while (pos < text.length && text[pos] !in ",}") pos++
                val raw = text.substring(start, pos).trim()
                if (raw.isEmpty()) return null
                if (raw == "null") return ""
                return raw
            }

            private fun readBalanced(): String? {
                val start = pos
                var depth = 0
                var inString = false
                while (pos < text.length) {
                    val c = text[pos++]
                    if (inString) {
                        if (c == '\\') {
                            if (pos >= text.length) return null
                            pos++
                        } else if (c == '"') {
                            inString = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return text.substring(start, pos)
                        }
                    }
                }
                return null
            }
        }
    }
}
