package com.raulshma.lenscast.streaming.srt

import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The lifecycle surface [SrtOutput] drives; [SrtPublisher] implements it in
 * production, JVM tests substitute a fake (the RtmpPublisherHandle pattern).
 */
internal interface SrtPublisherHandle {
    /** Starts the supervised connect/handshake/stream/reconnect loop; idempotent. */
    fun start()

    /** Clean close (shutdown notice + socket release); idempotent. */
    fun stop()

    /** One encoded H.264 access unit from the encoded-stream hub. */
    fun feedVideo(nalUnits: List<EncodedNalUnit>)

    /** One AAC access unit from the encoded-stream hub. */
    fun feedAudio(aacData: ByteArray)

    /** The current lifecycle state — the status row / status snapshot value. */
    fun status(): SrtStatus

    /** The live wire stats (RTT, loss counts) read by the status surfaces. */
    fun stats(): SrtStats
}

/**
 * The SRT push wire stats. `lostFromNak` is the honest count of sequence
 * numbers the listener reported missing — v1 does not retransmit (see the
 * publisher's doc), so the count is a health signal, not a repair ledger.
 */
data class SrtStats(
    val sentDatagrams: Long = 0,
    val lostFromNak: Long = 0,
    val lastAckSequence: Long = -1,
    val rttMs: Double = 0.0,
    val peerLatencyMs: Int = 0,
)

/**
 * The SRT publisher client — the socket half of the push output, hand-rolled
 * over the pure pieces ([SrtPacket], [SrtTsMuxer], [SrtUrl]); no external
 * dependency, like the RTMP publisher before it.
 *
 * One supervised coroutine on [Dispatchers.IO] runs the whole ladder per
 * attempt: UDP connect → induction (WAVEAHAND) → induction response (cookie
 * + peer socket id) → conclusion (cookie echo + HSREQ extension) →
 * conclusion response (HSRESP) — each awaited with a deadline; then a writer
 * coroutine drains the media channel through the TS muxer while the session
 * coroutine keeps reading (full ACKs → RTT stats, NAKs → loss counts,
 * keepalives). A dropped connection restarts the ladder under a capped
 * backoff while the output is wanted.
 *
 * HONEST LIMITATION (v1): NAKed sequence numbers are counted and logged, not
 * retransmitted. MPEG-TS tolerates loss (continuity-counter gaps surface as
 * decodable glitches, not corruption), and the listener's own latency buffer
 * absorbs most bursts — but a lossy path will show picture artifacts where a
 * full SRT stack would repair them. The ACK/NAK machinery here is the
 * bookkeeping a retransmit window would ride.
 *
 * Media arrives from the encoder drain threads through a bounded
 * drop-oldest channel, so a slow path sheds frames instead of blocking the
 * encoders. Video passes only from a keyframe AU onward (the RTMP join
 * gate's twin), so a mid-GOP start never ships undecodable P-frames.
 */
internal class SrtPublisher(
    private val url: SrtUrl,
    private val source: EncodedSource,
    private val onStatus: (SrtStatus) -> Unit,
    private val socketFactory: () -> DatagramSocket = ::defaultSocket,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
) : SrtPublisherHandle {

    private sealed interface Media {
        data class Video(val nalUnits: List<EncodedNalUnit>, val isKeyFrame: Boolean) : Media
        data class Audio(val aac: ByteArray) : Media
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wanted = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private val mediaChannel = Channel<Media>(
        capacity = MEDIA_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var socket: DatagramSocket? = null

    // Cross-thread session state: the encoder threads feed, the writer and
    // session coroutines own the socket.
    @Volatile private var statusValue: SrtStatus = SrtStatus.Idle
    @Volatile private var statsValue = SrtStats()
    @Volatile private var lastReceiveMs = 0L
    @Volatile private var lastSendMs = 0L

    // Per-attempt state, meaningful only inside runAttempt.
    private var muxer = SrtTsMuxer()
    private var sequence = 0
    private var messageNumber = 0
    private var sessionBaseMs = 0L
    private var videoArmed = false
    private var socketId = 0
    private var peerSocketId = 0

    override fun status(): SrtStatus = statusValue
    override fun stats(): SrtStats = statsValue

    private fun publishStatus(status: SrtStatus) {
        statusValue = status
        onStatus(status)
    }

    // ── lifecycle ──

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        wanted.set(true)
        val session = scope.launch {
            var attempt = 0
            while (wanted.get()) {
                publishStatus(SrtStatus.Connecting)
                try {
                    runAttempt()
                    attempt = 0 // a session that ended cleanly starts over without penalty
                } catch (e: Exception) {
                    if (!wanted.get()) break
                    publishStatus(SrtStatus.Error(e.message ?: "connection failed"))
                    Log.w(TAG, "SRT attempt toward ${url.redacted()} failed: ${e.message}")
                }
                if (!wanted.get()) break
                delay(backoffMs(attempt))
                attempt += 1
            }
            publishStatus(SrtStatus.Idle)
        }
        session.invokeOnCompletion { scope.cancel() }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        wanted.set(false)
        mediaChannel.close()
        // The shutdown notice is best-effort: a listener that is gone
        // already cannot read it, and the socket never outlives the grace.
        val sock = socket
        scope.launch {
            runCatching {
                sock?.send(
                    DatagramPacket(
                        SrtPacket.shutdown(timestampUs(), peerSocketId),
                        SrtPacket.HEADER_BYTES,
                        InetSocketAddress(url.host, url.port),
                    ),
                )
            }
            delay(STOP_FORCE_CLOSE_MS)
            runCatching { sock?.close() }
        }
    }

    // ── the media taps (encoder drain threads) ──

    override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return
        mediaChannel.trySend(Media.Video(nalUnits, isKeyFrame = nalUnits.any { it.isKeyFrame }))
    }

    override fun feedAudio(aacData: ByteArray) {
        if (aacData.isEmpty()) return
        mediaChannel.trySend(Media.Audio(aacData))
    }

    // ── one connection attempt: handshake → stream ──

    private fun runAttempt() {
        val sock = socketFactory()
        socket = sock
        muxer = SrtTsMuxer()
        socketId = random.nextInt()
        sequence = random.nextInt() and 0x7FFFFFFF
        messageNumber = 0
        peerSocketId = 0
        videoArmed = false
        statsValue = SrtStats()
        try {
            sock.reuseAddress = true
            sock.soTimeout = READ_TICK_MS
            sock.connect(InetSocketAddress(url.host, url.port))

            val handshakeDeadline = nowMs() + StreamDefaults.SRT_CONNECT_TIMEOUT_MS
            lastReceiveMs = nowMs()
            lastSendMs = 0

            // Induction: announce, then wait for the cookie. A version-5
            // listener answers with the SRT magic in its Extension Field —
            // the spec's own acceptance check — while a legacy answer at
            // version 4 is accepted by the cookie it must carry.
            send(SrtPacket.inductionRequest(timestampUs(), socketId, sequence))
            val induction = awaitHandshake(sock, handshakeDeadline) { hs ->
                hs.handshakeType == SrtPacket.HS_INDUCTION && hs.cookie != 0 &&
                    (hs.version != SrtPacket.HS_VERSION_SRT || hs.extensionField == SrtPacket.HS_MAGIC)
            }
            peerSocketId = induction.socketId

            // Conclusion: echo the cookie, announce our SRT config (HSREQ).
            send(
                SrtPacket.conclusionRequest(
                    timestampUs(),
                    socketId = socketId,
                    peerSocketId = peerSocketId,
                    cookie = induction.cookie,
                    initialSequence = sequence,
                ),
            )
            val conclusion = awaitHandshake(sock, handshakeDeadline) { it.handshakeType == SrtPacket.HS_CONCLUSION }
            // The conclusion response's HSRESP carries the peer's live
            // latency — surfaced as context in the stats, not negotiated.
            SrtPacket.hsRespFrom(conclusionPayload ?: ByteArray(0))?.let { peer ->
                statsValue = statsValue.copy(peerLatencyMs = peer.latencyMs)
            }

            // Live: the join gate re-arms, the TS clock starts, a keyframe is
            // requested BEFORE announcing Connected.
            videoArmed = false
            sessionBaseMs = nowMs()
            lastSendMs = nowMs()
            source.requestKeyFrame()
            publishStatus(SrtStatus.Connected)
            Log.i(TAG, "SRT publishing to ${url.redacted()} (peer socket $peerSocketId)")

            val writer = scope.launch {
                runCatching { drainMedia() }
                    .onFailure { if (wanted.get()) Log.w(TAG, "SRT media writer ended: ${it.message}") }
            }
            try {
                readLoop(sock)
            } finally {
                writer.cancel()
            }
            if (!wanted.get()) return
            throw java.io.IOException("SRT connection closed by peer")
        } finally {
            runCatching { sock.close() }
            socket = null
        }
    }

    /** The raw conclusion response payload, stashed for the HSRESP parse. */
    @Volatile private var conclusionPayload: ByteArray? = null

    /** Blocking reads until a matching handshake arrives or the deadline passes. */
    private fun awaitHandshake(
        sock: DatagramSocket,
        deadlineMs: Long,
        matches: (SrtPacket.Handshake) -> Boolean,
    ): SrtPacket.Handshake {
        val buffer = ByteArray(MAX_INCOMING_BYTES)
        while (nowMs() < deadlineMs) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            lastReceiveMs = nowMs()
            val incoming = SrtPacket.parseIncoming(packet.data, packet.length)
            if (incoming !is SrtPacket.Incoming.Control || incoming.type != SrtPacket.TYPE_HANDSHAKE) continue
            val handshake = SrtPacket.handshakeFrom(incoming.payload) ?: continue
            if (matches(handshake)) {
                if (handshake.handshakeType == SrtPacket.HS_CONCLUSION) {
                    conclusionPayload = incoming.payload
                }
                return handshake
            }
        }
        throw java.io.IOException("SRT handshake not answered in time")
    }

    /**
     * The session read half: full ACKs (RTT + ack tracking), NAKs (loss
     * counting — no retransmit in v1), keepalives (peer liveness), shutdown
     * (peer closed). Idle for [StreamDefaults.SRT_IDLE_TIMEOUT_MS] with no
     * inbound packet at all ends the attempt; the reconnect ladder takes it.
     */
    private fun readLoop(sock: DatagramSocket) {
        val buffer = ByteArray(MAX_INCOMING_BYTES)
        while (wanted.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (nowMs() - lastReceiveMs >= StreamDefaults.SRT_IDLE_TIMEOUT_MS) {
                    throw SocketTimeoutException("SRT peer idle for ${StreamDefaults.SRT_IDLE_TIMEOUT_MS} ms")
                }
                // The keepalive duty rides the read loop's ticks: a quiet
                // media path still tells the listener we are here.
                if (nowMs() - lastSendMs >= StreamDefaults.SRT_KEEPALIVE_INTERVAL_MS) {
                    send(SrtPacket.keepalive(timestampUs(), peerSocketId))
                    lastSendMs = nowMs()
                }
                continue
            }
            lastReceiveMs = nowMs()
            when (val incoming = SrtPacket.parseIncoming(packet.data, packet.length)) {
                is SrtPacket.Incoming.Control -> handleControl(incoming)
                is SrtPacket.Incoming.Data -> Unit // listeners do not send data; ignore defensively
                null -> Unit
            }
        }
    }

    private fun handleControl(control: SrtPacket.Incoming.Control) {
        when (control.type) {
            SrtPacket.TYPE_ACK -> SrtPacket.ackFrom(control.payload)?.let { ack ->
                val stats = statsValue
                val rttMs = ack.rttUs / 1000.0
                statsValue = stats.copy(
                    lastAckSequence = ack.lastAckSequence.toLong(),
                    rttMs = if (stats.rttMs == 0.0) rttMs else stats.rttMs * 0.7 + rttMs * 0.3,
                )
            }
            SrtPacket.TYPE_NAK -> {
                val lost = SrtPacket.nakFrom(control.payload).sumOf { it.last - it.first + 1 }
                if (lost > 0) {
                    statsValue = statsValue.copy(lostFromNak = statsValue.lostFromNak + lost)
                    Log.d(TAG, "SRT listener reported $lost lost packet(s); v1 does not retransmit")
                }
            }
            // Peer keepalives and shutdowns only refresh liveness (done by
            // the caller); a shutdown ends the attempt so the ladder can
            // renegotiate against a restarted listener.
            SrtPacket.TYPE_SHUTDOWN -> throw java.io.IOException("SRT listener sent shutdown")
            else -> Unit
        }
    }

    // ── the media writer ──

    private suspend fun drainMedia() {
        for (media in mediaChannel) {
            try {
                when (media) {
                    is Media.Video -> writeVideo(media)
                    is Media.Audio -> writeAudio(media)
                }
            } catch (e: Exception) {
                if (wanted.get()) Log.w(TAG, "SRT media write failed: ${e.message}")
                // Unblocks the read half (which owns this attempt's teardown)
                // instead of leaving both sides stalled on a dead socket.
                runCatching { socket?.close() }
            }
        }
    }

    private fun writeVideo(media: Media.Video) {
        if (!videoArmed) {
            // The join gate: a mid-GOP P-frame AU can never start the wire.
            if (!media.isKeyFrame) return
            videoArmed = true
        }
        val datagrams = muxer.videoAccessUnit(
            nalUnits = media.nalUnits.map { it.data },
            pts90k = mediaPts90k(),
            isKeyFrame = media.isKeyFrame,
        )
        sendDatagrams(datagrams)
    }

    private fun writeAudio(media: Media.Audio) {
        val datagrams = muxer.audioFrame(media.aac, mediaPts90k())
        sendDatagrams(datagrams)
    }

    /** The session clock: monotonic 90 kHz PTS from the connect moment. */
    private fun mediaPts90k(): Long = ((nowMs() - sessionBaseMs).coerceAtLeast(0)) * 90

    private fun sendDatagrams(datagrams: List<ByteArray>) {
        for (payload in datagrams) {
            send(SrtPacket.dataPacket(sequence, ++messageNumber, timestampUs(), peerSocketId, payload))
            sequence = (sequence + 1) and 0x7FFFFFFF
            statsValue = statsValue.copy(sentDatagrams = statsValue.sentDatagrams + 1)
        }
        lastSendMs = nowMs()
    }

    private fun send(bytes: ByteArray) {
        val sock = socket ?: throw java.io.IOException("SRT socket closed")
        sock.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(url.host, url.port)))
    }

    /** Microseconds since an arbitrary epoch, the SRT timestamp convention. */
    private fun timestampUs(): Long = System.nanoTime() / 1_000 and 0xFFFFFFFFL

    private fun backoffMs(attempt: Int): Long {
        var ms = INITIAL_BACKOFF_MS
        repeat(attempt.coerceAtLeast(0)) {
            ms = (ms * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return ms
    }

    private companion object {
        private const val TAG = "SrtPublisher"

        private const val READ_TICK_MS = 1_000
        private const val MAX_INCOMING_BYTES = 1500
        private const val MEDIA_CHANNEL_CAPACITY = 128
        private const val STOP_FORCE_CLOSE_MS = 2_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

/** The default transport: a plain (unencrypted) UDP datagram socket. */
private fun defaultSocket(): DatagramSocket = DatagramSocket()
