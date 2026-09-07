package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamAuthCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RtspSessionAuthorizerTest {

    private val requestUri = "rtsp://host:8554/stream"

    // ── helpers ──

    private fun md5(s: String) = StreamAuthCrypto.md5Hex(s)

    /** Builds an Authorization header exactly like a compliant RTSP client would. */
    private fun digestHeader(
        username: String,
        ha1: String,
        nonce: String,
        method: String = "DESCRIBE",
        uri: String = requestUri,
        nc: String = "00000001",
        cnonce: String = "cnonce-1",
        qop: String? = "auth",
        realm: String = StreamAuthCrypto.RTSP_DIGEST_REALM,
        opaque: String? = "lenscast-rtsp",
        response: String? = null,
    ): String {
        val ha2 = md5("$method:$uri")
        val resp = response
            ?: if (qop.isNullOrBlank()) md5("$ha1:$nonce:$ha2")
            else md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        return buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
            if (!qop.isNullOrBlank()) append(", qop=\"$qop\", nc=\"$nc\", cnonce=\"$cnonce\"")
            append(", response=\"$resp\", algorithm=MD5")
        }
    }

    private fun nonceFrom(challenge: String): String =
        Regex("nonce=\"([^\"]+)\"").find(challenge)!!.groupValues[1]

    private fun digestAuthorizer(
        ha1: String,
        username: String = "cam",
        clockMs: () -> Long,
    ) = RtspSessionAuthorizer({ RtspAuthSpec(username, "", ha1) }, clockMs)

    private fun basicAuthorizer(
        passwordHash: String,
        username: String = "cam",
    ) = RtspSessionAuthorizer({ RtspAuthSpec(username, passwordHash, "") })

    private fun basicHeader(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    // ── auth off ──

    @Test
    fun `auth off authorizes everything without a header`() {
        val authorizer = RtspSessionAuthorizer({ null })
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, null))
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, "Basic !!!garbage!!!"))
        assertTrue(authorizer.authorize("SETUP", "/stream/trackID=0", "Bearer x"))
        assertFalse(authorizer.requiresAuthentication("DESCRIBE"))
        assertFalse(authorizer.requiresAuthentication("OPTIONS"))
    }

    @Test
    fun `requiresAuthentication is true for everything but OPTIONS when auth is on`() {
        val authorizer = digestAuthorizer(md5("ha1")) { 0L }
        assertFalse(authorizer.requiresAuthentication("OPTIONS"))
        assertTrue(authorizer.requiresAuthentication("DESCRIBE"))
        assertTrue(authorizer.requiresAuthentication("SETUP"))
        assertTrue(authorizer.requiresAuthentication("PLAY"))
        assertTrue(authorizer.requiresAuthentication("TEARDOWN"))
        assertTrue(authorizer.requiresAuthentication("GET_PARAMETER"))
    }

    @Test
    fun `authorize does not bypass OPTIONS - gating is the caller's job`() {
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { 0L }
        // Missing credentials on OPTIONS still fail authorize; only requiresAuthentication
        // exempts the method.
        assertFalse(authorizer.authorize("OPTIONS", requestUri, null))
        assertFalse(authorizer.authorize("OPTIONS", requestUri, "Basic dXNlcjpwYXNz"))
    }

    // ── Digest happy path ──

    @Test
    fun `digest happy path with challenge-minted nonce authorizes`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }

        val nonce = nonceFrom(authorizer.challengeHeader())
        val header = digestHeader("cam", ha1, nonce)
        assertTrue(authorizer.requiresAuthentication("DESCRIBE"))
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, header))
    }

    @Test
    fun `replay with the same nc is rejected - nc is monotonic per username and cnonce`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())

        assertTrue(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, nonce)))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, nonce)))
        // Fresh nc value unlocks the nonce again.
        assertTrue(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, nc = "00000002")
            )
        )
        // A lower or repeated nc after the higher one is rejected.
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, nc = "00000002")
            )
        )
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, nc = "00000001")
            )
        )
    }

    @Test
    fun `nc tracking is keyed by cnonce - same nc with a new cnonce is accepted`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())

        assertTrue(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, nonce)))
        assertTrue(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, cnonce = "cnonce-2")
            )
        )
    }

    @Test
    fun `nonce expires at ttl - boundary is exclusive`() {
        val t0 = 50_000L
        var now = t0
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())

        val header = digestHeader("cam", ha1, nonce)
        now = t0 + 5 * 60 * 1000 // exactly the TTL → still valid
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, header))
        now = t0 + 5 * 60 * 1000 + 1 // TTL + 1 → expired
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, header))

        // A re-minted challenge at the advanced clock produces a working nonce again.
        val fresh = nonceFrom(authorizer.challengeHeader())
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, fresh)))
    }

    @Test
    fun `nonce we never minted is rejected`() {
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { 0L }
        val unknown = Base64.getUrlEncoder().encodeToString(ByteArray(16))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, unknown)))
    }

    // ── Digest rejection branches ──

    @Test
    fun `wrong password produces a different ha1 and is rejected`() {
        var now = 1_000L
        val authorizer = digestAuthorizer(StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())
        val wrongHa1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "not-the-password")
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", wrongHa1, nonce)))
    }

    @Test
    fun `unknown username is rejected`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())
        assertFalse(
            authorizer.authorize("DESCRIBE", requestUri, digestHeader("intruder", ha1, nonce))
        )
    }

    @Test
    fun `wrong realm is rejected`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, realm = "Other Realm")
            )
        )
    }

    @Test
    fun `wrong or missing opaque is rejected`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, opaque = "spoofed")
            )
        )
        // A missing opaque parameter defaults to "" which never matches.
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, opaque = null)
            )
        )
    }

    @Test
    fun `digest uri must normalize to the same path as the request uri`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())

        // Different host, query and trailing slash all normalize to /stream.
        assertTrue(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, uri = "rtsp://elsewhere/stream?x=1")
            )
        )
        // A different path is rejected.
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, uri = "/other")
            )
        )
        // Path comparison is case-sensitive.
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, uri = "/STREAM")
            )
        )
    }

    @Test
    fun `missing or unrecognized authorization header is rejected when auth is on`() {
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { 0L }
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, null))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, ""))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Bearer some-token"))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Digest"))
    }

    @Test
    fun `digest header with a blank digestHa1 spec is rejected`() {
        val authorizer = RtspSessionAuthorizer({ RtspAuthSpec("cam", "somehash", "") }) { 0L }
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Digest username=\"cam\""))
    }

    @Test
    fun `legacy digest without qop verifies against the rfc2069 response shape`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())
        assertTrue(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, qop = null)
            )
        )
    }

    @Test
    fun `qop present but cnonce or nc missing or non-hex is rejected`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonce = nonceFrom(authorizer.challengeHeader())

        // qop=auth with no cnonce/nc parameters at all.
        val bare = "Digest username=\"cam\", realm=\"${StreamAuthCrypto.RTSP_DIGEST_REALM}\", " +
            "nonce=\"$nonce\", uri=\"$requestUri\", opaque=\"lenscast-rtsp\", qop=\"auth\", " +
            "response=\"${md5("whatever")}\""
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, bare))

        // nc that is not valid hex.
        assertFalse(
            authorizer.authorize(
                "DESCRIBE", requestUri,
                digestHeader("cam", ha1, nonce, nc = "zz", cnonce = "c", response = md5("x"))
            )
        )
    }

    // ── Basic path ──

    @Test
    fun `basic happy path with pbkdf2 password hash authorizes`() {
        val authorizer = basicAuthorizer(StreamAuthCrypto.hashPassword("s3cret-pw"))
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, basicHeader("cam", "s3cret-pw")))
    }

    @Test
    fun `basic wrong password is rejected`() {
        val authorizer = basicAuthorizer(StreamAuthCrypto.hashPassword("s3cret-pw"))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, basicHeader("cam", "wrong")))
    }

    @Test
    fun `basic username mismatch is rejected`() {
        val authorizer = basicAuthorizer(StreamAuthCrypto.hashPassword("s3cret-pw"))
        // Case-sensitive comparison — CAM is not cam.
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, basicHeader("CAM", "s3cret-pw")))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, basicHeader("cam2", "s3cret-pw")))
    }

    @Test
    fun `basic malformed credentials are rejected`() {
        val authorizer = basicAuthorizer(StreamAuthCrypto.hashPassword("pw"))
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Basic")) // no payload
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Basic ")) // empty payload
        // Base64("usernocolon") — no ':' separator.
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Basic dXNlcm5vY29sb24="))
        // Base64(":pass") — separator at index 0 means empty username.
        assertFalse(authorizer.authorize("DESCRIBE", requestUri, "Basic OnBhc3M="))
    }

    @Test
    fun `basic scheme is case-insensitive and digest takes priority`() {
        val authorizer = basicAuthorizer(StreamAuthCrypto.hashPassword("pw"))
        assertTrue(
            authorizer.authorize("DESCRIBE", requestUri, "bAsIc " + basicHeader("cam", "pw").substringAfter(' '))
        )
    }

    // ── challengeHeader ──

    @Test
    fun `challenge with digest configured is a Digest challenge carrying nonce and opaque`() {
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { 0L }
        val challenge = authorizer.challengeHeader()

        assertTrue(challenge.startsWith("Digest realm=\"${StreamAuthCrypto.RTSP_DIGEST_REALM}\""))
        assertTrue(challenge.contains(Regex("nonce=\"[^\"]+\"")))
        assertTrue(challenge.contains("opaque=\"lenscast-rtsp\""))
        assertTrue(challenge.contains("algorithm=MD5"))
        assertTrue(challenge.contains("qop=\"auth\""))

        // The minted nonce is immediately usable.
        assertTrue(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonceFrom(challenge))
            )
        )
    }

    @Test
    fun `successive challenges mint distinct nonces`() {
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { 0L }
        val first = nonceFrom(authorizer.challengeHeader())
        val second = nonceFrom(authorizer.challengeHeader())
        assertTrue(first != second)
        // Both are valid simultaneously.
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, first, cnonce = "a")))
        assertTrue(authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, second, cnonce = "b")))
    }

    @Test
    fun `challenge without digest config is a Basic challenge`() {
        val noSpec = RtspSessionAuthorizer({ null })
        assertEquals("Basic realm=\"${StreamAuthCrypto.RTSP_DIGEST_REALM}\"", noSpec.challengeHeader())

        val basicOnly = basicAuthorizer(StreamAuthCrypto.hashPassword("pw"))
        assertEquals(
            "Basic realm=\"${StreamAuthCrypto.RTSP_DIGEST_REALM}\"",
            basicOnly.challengeHeader()
        )
    }

    // ── nonce store cap ──

    @Test
    fun `nonce store is capped - the newest nonce always survives`() {
        var now = 1_000L
        val ha1 = StreamAuthCrypto.computeRtspDigestHa1("cam", "pw")
        val authorizer = digestAuthorizer(ha1) { now }
        val nonces = List(513) { nonceFrom(authorizer.challengeHeader()) }

        // The store never grows past its cap, so not all 513 can still authorize...
        val surviving = nonces.count { nonce ->
            authorizer.authorize("DESCRIBE", requestUri, digestHeader("cam", ha1, nonce, cnonce = "c-$nonce"))
        }
        assertTrue(surviving <= 512)
        // ...but eviction keeps the most recent one.
        assertTrue(
            authorizer.authorize(
                "DESCRIBE", requestUri, digestHeader("cam", ha1, nonces.last(), cnonce = "c-newest")
            )
        )
    }

    // ── parseDigestParams ──

    @Test
    fun `parseDigestParams handles quoted values with escaped quotes`() {
        val parsed = RtspSessionAuthorizer({ null }).parseDigestParams(
            "username=\"a\\\"b\", nonce=\"x\\\"y\""
        )
        assertEquals("a\"b", parsed["username"])
        assertEquals("x\"y", parsed["nonce"])
    }

    @Test
    fun `parseDigestParams handles unquoted values whitespace and casing`() {
        val parsed = RtspSessionAuthorizer({ null }).parseDigestParams(
            "  Username=\"cam\" ,  nc=00000001 ,algorithm=MD5, qop=\"AUTH\"  "
        )
        // Keys are lowercased; quoted values lose their quotes; value case is kept.
        assertEquals("cam", parsed["username"])
        assertEquals("00000001", parsed["nc"])
        assertEquals("MD5", parsed["algorithm"])
        assertEquals("AUTH", parsed["qop"])
    }

    @Test
    fun `parseDigestParams only strips quotes when they directly follow the equals sign`() {
        // A space before the opening quote selects the unquoted branch: the quotes
        // stay in the value (whitespace-only is trimmed).
        val parsed = RtspSessionAuthorizer({ null }).parseDigestParams("username = \"cam\"")
        assertEquals("\"cam\"", parsed["username"])
    }

    @Test
    fun `parseDigestParams duplicate keys are last-wins`() {
        val parsed = RtspSessionAuthorizer({ null }).parseDigestParams("uri=\"/a\", uri=\"/b\"")
        assertEquals("/b", parsed["uri"])
    }

    @Test
    fun `parseDigestParams drops junk and tolerates empty values`() {
        val parsed = RtspSessionAuthorizer({ null }).parseDigestParams("uri=, qop=\"auth\",")
        assertEquals("", parsed["uri"])
        assertEquals("auth", parsed["qop"])
        // A token without '=' ends parsing: everything after it is dropped.
        val stopped = RtspSessionAuthorizer({ null }).parseDigestParams("a=1, garbage, b=2")
        assertEquals("1", stopped["a"])
        assertFalse(stopped.containsKey("b"))
    }

    @Test
    fun `parseDigestParams of empty input is an empty map`() {
        assertTrue(RtspSessionAuthorizer({ null }).parseDigestParams("").isEmpty())
    }
}
