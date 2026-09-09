package com.raulshma.lenscast.streaming.rtsp

/**
 * Pure-Kotlin H.265 NAL parsing helpers (no Android dependencies), the HEVC
 * sibling of [H264NalParser]. The H.265 NAL header is TWO bytes —
 * forbidden(1) | type(6) | layer-id-high(1) in byte 0, layer-id-low(5) |
 * temporal-id-plus1(3) in byte 1 — so the type decode is
 * `(byte0 shr 1) and 0x3F`, never the H.264 `and 0x1F` read.
 *
 * Start-code framing is identical to H.264 (Annex-B 3-/4-byte start codes),
 * so extraction and start-code stripping deliberately reuse
 * [H264NalParser] rather than duplicating the scanners.
 *
 * Deliberately NOT handled: PACI, aggregation packets, and any H.265
 * parameter-set *decoding* (profile/tier/level stay unparsed — the SDP
 * carries the raw parameter sets base64-encoded, no profile-level-id).
 */
object H265NalParser {

    /** NAL type of a header-stripped H.265 NAL unit; -1 when too short to carry the 2-byte header. */
    fun nalType(nal: ByteArray): Int = if (nal.size < 2) -1 else (nal[0].toInt() shr 1) and 0x3F

    /** VPS (type 32) — the H.265 analogue of nothing in H.264; new parameter set at the front. */
    fun isVps(nal: ByteArray): Boolean = nalType(nal) == NAL_TYPE_VPS

    /** SPS (type 33). */
    fun isSps(nal: ByteArray): Boolean = nalType(nal) == NAL_TYPE_SPS

    /** PPS (type 34). */
    fun isPps(nal: ByteArray): Boolean = nalType(nal) == NAL_TYPE_PPS

    /**
     * IDR slice: both variants — IDR_W_RADL (19) and IDR_N_LP (20). CRA
     * (type 21) is deliberately NOT treated as a keyframe verdict: CRA
     * pictures may reference pictures before them (RASL), so joining on CRA
     * is not guaranteed decodable the way IDR is.
     */
    fun isIdr(nal: ByteArray): Boolean = nalType(nal) == NAL_TYPE_IDR_W_RADL || nalType(nal) == NAL_TYPE_IDR_N_LP

    /**
     * Whether any NAL unit in the access unit is an IDR slice. The
     * authoritative keyframe verdict for the wire — MediaCodec's
     * `BUFFER_FLAG_KEY_FRAME` is vendor-dependent, exactly as for H.264.
     */
    fun containsIdr(nalUnits: List<ByteArray>): Boolean = nalUnits.any { isIdr(it) }

    /**
     * Splits an Annex-B buffer (3- or 4-byte start codes — the same framing
     * as H.264) into header-stripped NAL units. Delegates to
     * [H264NalParser.extractAnnexBNalUnits]; H.265 adds nothing to the framing.
     */
    fun extractAnnexBNalUnits(data: ByteArray): List<ByteArray> =
        H264NalParser.extractAnnexBNalUnits(data)

    /** Strips a leading Annex-B start code from a csd buffer, if present. */
    fun stripStartCode(bytes: ByteArray): ByteArray = H264NalParser.stripStartCode(bytes)

    /**
     * Builds the H.265 `a=fmtp` value (RFC 7798 §7.1): the semicolon-separated
     * `sprop-vps;sprop-sps;sprop-pps` base64 triple. Null unless ALL three
     * parameter sets are available — there is no other fmtp attribute to
     * carry, so the caller omits the whole fmtp line rather than emitting an
     * empty one (the H.264 ladder keeps its line and drops only the sprop).
     */
    fun buildFmtp(vpsBase64: String?, spsBase64: String?, ppsBase64: String?): String? {
        if (vpsBase64.isNullOrEmpty() || spsBase64.isNullOrEmpty() || ppsBase64.isNullOrEmpty()) {
            return null
        }
        return "sprop-vps=$vpsBase64;sprop-sps=$spsBase64;sprop-pps=$ppsBase64"
    }

    private const val NAL_TYPE_VPS = 32
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PPS = 34
    private const val NAL_TYPE_IDR_W_RADL = 19
    private const val NAL_TYPE_IDR_N_LP = 20
}
