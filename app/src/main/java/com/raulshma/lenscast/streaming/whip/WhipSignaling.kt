package com.raulshma.lenscast.streaming.whip

import com.raulshma.lenscast.core.Base64Codec

/** A WHIP signaling rejection with a message worth surfacing on the status line. */
class WhipSignalingException(message: String) : Exception(message)

/** One WHIP HTTP exchange: method, absolute URL, headers, optional body. */
data class WhipHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is WhipHttpRequest &&
        method == other.method && url == other.url && headers == other.headers &&
        body?.contentEquals(other.body) == (other.body?.contentEquals(body))

    override fun hashCode(): Int = method.hashCode() * 31 + url.hashCode()
}

/** The transport's answer: status code, lower-cased header map, body bytes. */
data class WhipHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun bodyText(): String = String(body, Charsets.UTF_8)
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * The transport seam behind [WhipSignaling]. Production rides
 * [HttpsWhipHttpClient] (HttpsURLConnection with the system/user trust
 * stores); JVM tests substitute a scripted fake — the
 * [com.raulshma.lenscast.streaming.rtmp.RtmpPublisherTest] loopback pattern's
 * interface-shaped cousin, since the WHIP layer needs canned responses, not a
 * socket dialect.
 */
interface WhipHttpClient {
    fun execute(request: WhipHttpRequest): WhipHttpResponse
}

/**
 * The pure RFC 9725 mechanics, split from the libwebrtc session so every
 * wire-facing decision is JVM-testable:
 *
 * - the offer POST: `POST {endpoint}` with `Content-Type: application/sdp`
 *   and the offer SDP as the body; the optional credential rides an
 *   `Authorization` header (bearer token from the setting, else HTTP Basic
 *   from the URL userinfo);
 * - the answer: `201 Created`, the answer SDP as the response body and the
 *   session resource in the `Location` header (absolute or relative to the
 *   endpoint — both are resolved);
 * - the teardown: `DELETE {resource}` — any 2xx, or 404/410 (already gone),
 *   settles a session cleanly.
 */
object WhipSignaling {

    const val CONTENT_TYPE_SDP = "application/sdp"

    /** The answer parse: the session resource URL plus the answer SDP text. */
    data class WhipAnswer(val resourceUrl: String, val answerSdp: String)

    /**
     * The Authorization header value for a push: the token setting wins
     * (RFC 9725's bearer), URL userinfo supplies Basic otherwise, and with
     * neither there is no header (an open endpoint).
     */
    fun authorizationHeader(token: String?, basicUser: String?, basicPassword: String?): String? = when {
        !token.isNullOrBlank() -> "Bearer ${token.trim()}"
        !basicUser.isNullOrBlank() ->
            "Basic " + Base64Codec.encode("$basicUser:${basicPassword ?: ""}".toByteArray(Charsets.UTF_8))
        else -> null
    }

    /** The RFC 9725 §5 offer request. */
    fun offerRequest(url: WhipUrl, token: String?, offerSdp: String): WhipHttpRequest {
        val headers = mutableMapOf(
            "Content-Type" to CONTENT_TYPE_SDP,
            "Accept" to CONTENT_TYPE_SDP,
        )
        authorizationHeader(token, url.username, url.password)?.let { headers["Authorization"] = it }
        return WhipHttpRequest(
            method = "POST",
            url = url.resourceUrl,
            headers = headers,
            body = offerSdp.toByteArray(Charsets.UTF_8),
        )
    }

    /** The RFC 9725 §6 resource teardown. Same credential rule as the offer. */
    fun deleteRequest(resourceUrl: String, token: String?, basicUser: String?, basicPassword: String?): WhipHttpRequest {
        val headers = mutableMapOf<String, String>()
        authorizationHeader(token, basicUser, basicPassword)?.let { headers["Authorization"] = it }
        return WhipHttpRequest(method = "DELETE", url = resourceUrl, headers = headers, body = null)
    }

    /**
     * The 201 verdict: the answer SDP is the body, the session resource is
     * the `Location` header resolved against the endpoint (absolute or
     * relative — servers do both). Anything else is a readable refusal.
     */
    fun parseOfferResponse(response: WhipHttpResponse, endpointUrl: String): WhipAnswer {
        if (response.statusCode != 201) {
            val detail = response.bodyText().take(200).ifBlank { response.header("reason-phrase") ?: "" }
            throw WhipSignalingException("WHIP server answered ${response.statusCode} to the offer${if (detail.isBlank()) "" else ": $detail"}")
        }
        val answer = response.bodyText()
        if (answer.isBlank()) throw WhipSignalingException("WHIP server returned an empty answer SDP")
        val location = response.header("location")
            ?: throw WhipSignalingException("WHIP server returned no Location header for the session resource")
        return WhipAnswer(resolveResource(location, endpointUrl), answer)
    }

    /** Absolute `Location` values pass through; root-relative ones join the endpoint's origin. */
    fun resolveResource(location: String, endpointUrl: String): String {
        val trimmed = location.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val origin = endpointUrl.substringBefore("://").let { scheme ->
            "$scheme://${endpointUrl.substringAfter("://").substringBefore('/')}"
        }
        return if (trimmed.startsWith("/")) origin + trimmed else "$origin/$trimmed"
    }

    /** True when a DELETE settles the session: any 2xx, or 404/410 (already gone). */
    fun isDeleteSettled(statusCode: Int): Boolean =
        statusCode in 200..299 || statusCode == 404 || statusCode == 410
}

/**
 * The one-shot ICE half of the offer: folds gathered `a=candidate:` lines
 * into the SDP body that is POSTed, so the server never has to trickle.
 * libwebrtc's local description after gathering completion already carries
 * the candidates inline — [injectCandidates] is idempotent and only appends
 * lines that are missing — but the injection lives here as a pure step so the
 * JVM tests can pin it, and so a late candidate that landed after the
 * gather deadline still makes it into the offer. Candidates are media-level
 * attributes: each line is appended to the end of every `m=` section.
 */
object WhipOfferBuilder {

    fun injectCandidates(sdp: String, candidateLines: List<String>): String {
        if (candidateLines.isEmpty()) return sdp
        val missing = candidateLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { line -> sdp.lineSequence().none { it.trim() == line } }
        if (missing.isEmpty()) return sdp

        val lines = sdp.split("\r\n", "\n").toMutableList()
        var sectionStart = -1
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("m=", ignoreCase = true)) {
                if (sectionStart >= 0) out.addAll(missing)
                sectionStart = out.size
            }
            out.add(line)
        }
        if (sectionStart >= 0) out.addAll(missing)
        return out.joinToString("\r\n")
    }
}
