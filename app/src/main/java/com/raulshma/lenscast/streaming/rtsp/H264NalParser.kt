package com.raulshma.lenscast.streaming.rtsp

/**
 * Pure-Kotlin H.264 NAL parsing helpers (no Android dependencies) so the RTP/RTSP
 * payload path stays unit-testable on the JVM.
 */
object H264NalParser {

    /** Splits a MediaCodec output buffer into NAL units (no start codes / length prefixes). */
    fun extractNalUnits(data: ByteArray): List<ByteArray> {
        val annexB = extractAnnexBNalUnits(data)
        if (annexB.isNotEmpty()) return annexB
        return extractAvccNalUnits(data)
    }

    /** Annex-B parsing: NAL units delimited by 3- or 4-byte start codes. */
    fun extractAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < data.size) {
            val startCodeLen = when {
                offset + 3 < data.size &&
                    data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 0.toByte() &&
                    data[offset + 3] == 1.toByte() -> 4

                offset + 2 < data.size &&
                    data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 1.toByte() -> 3

                else -> break
            }

            val nalStart = offset + startCodeLen
            var nalEnd = data.size
            var scan = nalStart
            while (scan + 2 < data.size) {
                if (data[scan] == 0.toByte() && data[scan + 1] == 0.toByte()) {
                    if (data[scan + 2] == 1.toByte()) {
                        nalEnd = scan
                        break
                    }
                    if (scan + 3 < data.size && data[scan + 2] == 0.toByte() && data[scan + 3] == 1.toByte()) {
                        nalEnd = scan
                        break
                    }
                }
                scan++
            }

            if (nalStart < nalEnd) {
                result.add(data.copyOfRange(nalStart, nalEnd))
            }

            offset = nalEnd
        }

        return result
    }

    /** AVCC parsing: NAL units prefixed with 4-byte big-endian lengths. Returns empty on any inconsistency. */
    fun extractAvccNalUnits(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + 4 <= data.size) {
            val nalSize =
                ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            offset += 4

            if (nalSize <= 0 || offset + nalSize > data.size) {
                return emptyList()
            }

            result.add(data.copyOfRange(offset, offset + nalSize))
            offset += nalSize
        }

        return if (offset == data.size) result else emptyList()
    }

    /** Strips a leading Annex-B start code from a csd buffer, if present. */
    fun stripStartCode(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size && bytes[start] == 0.toByte()) start++
        if (start < bytes.size && bytes[start] == 1.toByte()) start++
        return if (start < bytes.size) bytes.copyOfRange(start, bytes.size) else bytes
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /**
     * Whether any NAL unit in the access unit is an IDR slice (type 5). The
     * authoritative keyframe verdict for the wire: MediaCodec's
     * `BUFFER_FLAG_KEY_FRAME` is vendor-dependent and is not set by every
     * encoder, so downstream consumers (RTP fan-out, HLS segment cutting,
     * the WS join path) must not rely on the flag alone.
     */
    fun containsIdr(nalUnits: List<ByteArray>): Boolean =
        nalUnits.any { nalType(it) == NAL_TYPE_IDR }

    /**
     * Builds the H.264 `a=fmtp` value. Never emits a dangling `;`; omits
     * `sprop-parameter-sets` unless both parameter sets are available.
     */
    fun buildFmtp(profileLevelId: String, spsBase64: String?, ppsBase64: String?): String {
        val fmtp = StringBuilder("packetization-mode=1;profile-level-id=$profileLevelId")
        if (!spsBase64.isNullOrEmpty() && !ppsBase64.isNullOrEmpty()) {
            fmtp.append(";sprop-parameter-sets=$spsBase64,$ppsBase64")
        }
        return fmtp.toString()
    }

    fun profileLevelId(sps: ByteArray?): String {
        if (sps != null && sps.size >= 4) {
            return "%02x%02x%02x".format(
                sps[1].toInt() and 0xFF,
                sps[2].toInt() and 0xFF,
                sps[3].toInt() and 0xFF
            )
        }
        return "42c01f"
    }

    private const val NAL_TYPE_IDR = 5
}
