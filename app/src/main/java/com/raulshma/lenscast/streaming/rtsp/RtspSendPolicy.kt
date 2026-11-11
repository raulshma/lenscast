package com.raulshma.lenscast.streaming.rtsp

/**
 * One access unit queued for a client's writer thread: the `$`-framed
 * interleaved packets in wire order (RTCP sender report first when present,
 * then the RTP packets), already framed at enqueue so the writer only writes.
 * [isVideoAu] separates the video AUs the clients-list frame counter counts
 * from audio AUs, which ride the same queue but are not frames.
 */
class RtspQueuedAu(
    val frames: List<ByteArray>,
    val isKeyframe: Boolean,
    val isVideoAu: Boolean,
) {
    val bytes: Int = frames.sumOf { it.size }
}

/**
 * The per-client send-queue offer verdict: how many whole AUs to drop from
 * the queue's head to make room for the incoming one, whether the drop
 * re-arms the client's keyframe gate, and whether to ask the encoder for a
 * sync frame. Pure so the congestion rules are JVM-tested; the queue, the
 * gate writes, and the encoder request stay in RtspServer.ClientSession.
 *
 * Drop-oldest, never the incoming AU: a congested client gets GOP-cadence
 * refreshes instead of growing latency — the bounded queue caps the delay
 * at [DEFAULT_CAPACITY_AUS] access units (~330 ms at 24 fps).
 */
object RtspSendPolicy {

    /** The queue bound in whole access units (~330 ms at 24 fps). */
    const val DEFAULT_CAPACITY_AUS = 8

    /**
     * Minimum spacing between drop-triggered keyframe requests. Load-bearing:
     * without it, sustained congestion requests a sync frame on every dropped
     * AU and the encoder goes all-intra — a bitrate explosion that worsens
     * the very congestion that triggered it.
     */
    const val KEYFRAME_REQUEST_COOLDOWN_MS = 1_000L

    data class OfferVerdict(
        val dropCount: Int,
        val rearmKeyframe: Boolean,
        val requestKeyframe: Boolean,
    )

    /**
     * [queueSize] is the queue's occupancy before the offer (a lock-free
     * size read — an exact count is not owed, the bound is soft by one AU
     * under race). [lastKeyframeRequestMs] is negative when no drop-triggered
     * request has happened yet this session.
     *
     * A drop re-arms the keyframe gate because the dropped head may have
     * carried the reference the queued P-frames decode against — except
     * when the incoming AU is itself a keyframe: it is the fresh sync point,
     * and re-arming past it would stall delivery a whole GOP for nothing.
     */
    fun onOffer(
        queueSize: Int,
        capacity: Int,
        isKeyframeAu: Boolean,
        nowMs: Long,
        lastKeyframeRequestMs: Long,
    ): OfferVerdict {
        val cap = capacity.coerceAtLeast(1)
        val dropCount = maxOf(0, queueSize + 1 - cap)
        val rearm = dropCount > 0 && !isKeyframeAu
        val request = rearm &&
            (lastKeyframeRequestMs < 0 || nowMs - lastKeyframeRequestMs >= KEYFRAME_REQUEST_COOLDOWN_MS)
        return OfferVerdict(dropCount = dropCount, rearmKeyframe = rearm, requestKeyframe = request)
    }
}