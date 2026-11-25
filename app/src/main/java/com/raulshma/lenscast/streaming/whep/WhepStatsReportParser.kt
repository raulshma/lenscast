package com.raulshma.lenscast.streaming.whep

import java.util.Locale

/** One libwebrtc `RTCStats` row, reduced to what [WhepStatsReportParser] reads: its type and its members. */
class WhepStatRow(
    val type: String,
    val id: String,
    val members: Map<String, Any?>,
)

/** The video a stats report says one viewer session has sent: cumulative totals, plus the codec and frame size it carried. */
data class WhepVideoOutbound(
    val bytesSent: Long,
    val framesEncoded: Long,
    val codec: String?,
    val width: Int?,
    val height: Int?,
)

/**
 * The pure half-facing of a libwebrtc stats report: which row describes the
 * viewer's video, and what it says. The endpoint hands in the report's rows
 * and gets one [WhepVideoOutbound] to feed [WhepStreamStats], so the reading
 * of the library's shapes is JVM-tested without libwebrtc.
 *
 * Member values arrive boxed exactly as the JNI glue builds them — 32-bit
 * members as `Integer`, 64-bit ones as `Long`, doubles as `Double` — so every
 * numeric member is read as any [Number]. Absent members are simply missing
 * from the map: libwebrtc only emits `frameWidth`/`frameHeight` once frames
 * were encoded, and `codecId` only once the payload type is known.
 */
object WhepStatsReportParser {

    const val TYPE_OUTBOUND_RTP = "outbound-rtp"
    const val TYPE_CODEC = "codec"

    private const val MEMBER_KIND = "kind"
    private const val MEMBER_CODEC_ID = "codecId"
    private const val MEMBER_MIME_TYPE = "mimeType"
    private const val MEMBER_BYTES_SENT = "bytesSent"
    private const val MEMBER_FRAMES_ENCODED = "framesEncoded"
    private const val MEMBER_FRAME_WIDTH = "frameWidth"
    private const val MEMBER_FRAME_HEIGHT = "frameHeight"
    private const val KIND_VIDEO = "video"

    /**
     * The video `outbound-rtp` row's counters, or null when the report
     * describes no video send (before the stream exists, or an audio-only
     * one). Every RTP stream stat carries a `kind` naming its media; a
     * libwebrtc older than that member is recognized by the codec its
     * `codecId` resolves to instead. Should a report ever carry more than one
     * video send stream, the one that sent the most bytes answers (ties by
     * id) — the endpoint's once-per-session report census shows when that
     * happens.
     */
    fun videoOutbound(rows: Collection<WhepStatRow>): WhepVideoOutbound? {
        val byId = rows.associateBy { it.id }
        val outbound = rows.filter { it.type == TYPE_OUTBOUND_RTP }
        val carriesKind = outbound.any { it.members[MEMBER_KIND] != null }
        val video = outbound.filter { row ->
            if (carriesKind) {
                row.members[MEMBER_KIND] == KIND_VIDEO
            } else {
                mimeTypeOf(row, byId)?.startsWith("video/", ignoreCase = true) == true
            }
        }
        val chosen = video
            .filter { row -> row.number(MEMBER_BYTES_SENT) != null && row.number(MEMBER_FRAMES_ENCODED) != null }
            .maxWithOrNull(compareBy<WhepStatRow> { it.number(MEMBER_BYTES_SENT) }.thenBy { it.id })
            ?: return null
        return WhepVideoOutbound(
            bytesSent = chosen.number(MEMBER_BYTES_SENT)!!.toLong(),
            framesEncoded = chosen.number(MEMBER_FRAMES_ENCODED)!!.toLong(),
            codec = mimeTypeOf(chosen, byId)?.takeIf { it.isNotBlank() }?.let(::codecWireName),
            width = chosen.number(MEMBER_FRAME_WIDTH)?.toInt()?.takeIf { it > 0 },
            height = chosen.number(MEMBER_FRAME_HEIGHT)?.toInt()?.takeIf { it > 0 },
        )
    }

    /**
     * The codec's wire name from its mime type: `video/VP8` → `vp8`,
     * `video/H264` → `h264` — the lowercase sub-type, in the vocabulary the
     * encoded lane's `h264`/`h265` already uses.
     */
    fun codecWireName(mimeType: String): String =
        mimeType.substringAfter('/', mimeType).trim().lowercase(Locale.ROOT)

    /** The mime type a stream row's `codecId` resolves to, or null when it names no codec row with one. */
    private fun mimeTypeOf(row: WhepStatRow, byId: Map<String, WhepStatRow>): String? {
        val codecId = row.members[MEMBER_CODEC_ID] as? String ?: return null
        val codec = byId[codecId] ?: return null
        return codec.members[MEMBER_MIME_TYPE] as? String
    }

    /** A numeric member as a finite non-negative double, whatever its boxed type; null when absent or unusable. */
    private fun WhepStatRow.number(member: String): Double? =
        (members[member] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
}
