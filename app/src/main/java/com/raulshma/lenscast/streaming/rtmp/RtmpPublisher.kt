package com.raulshma.lenscast.streaming.rtmp

import android.util.Log
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A connect/publish rejection with a message worth surfacing on the status line. */
internal class RtmpPublishException(message: String) : Exception(message)

/**
 * The lifecycle surface [RtmpOutput] drives; [RtmpPublisher] implements it in
 * production, JVM tests substitute a fake that records the calls (the
 * [com.raulshma.lenscast.streaming.rtsp.RtspServerHandle] pattern).
 */
internal interface RtmpPublisherHandle {
    /** Starts the supervised connect/publish/reconnect loop; idempotent. */
    fun start()

    /** Clean close (FCUnpublish + DeleteStream when connected); idempotent. */
    fun stop()

    /** One encoded H.264 access unit from the encoded-stream hub. */
    fun feedVideo(nalUnits: List<EncodedNalUnit>)

    /** One AAC access unit from the encoded-stream hub. */
    fun feedAudio(aacData: ByteArray)

    /** The current lifecycle state — the status row / status snapshot value. */
    fun status(): RtmpStatus
}

/**
 * The RTMP publisher client — the socket half of the push output, hand-rolled
 * over the pure pieces ([RtmpHandshake], [Amf0]/[RtmpCommands],
 * [RtmpChunkWriter]/[RtmpChunkReader], [FlvTag]); no external dependency.
 *
 * One supervised coroutine on [Dispatchers.IO] runs the whole ladder per
 * attempt: TCP/TLS connect → handshake → `connect` → `releaseStream` →
 * `createStream` → `publish("live")`, each reply awaited with a deadline;
 * then a writer coroutine drains the media channel while the session
 * coroutine keeps reading (chunk-size changes, the acknowledgement window,
 * ping events, and any late commands). A dropped connection restarts the
 * ladder under a capped backoff while the output is wanted.
 *
 * Media arrives from the encoder drain threads through a bounded
 * drop-oldest channel, so a slow server sheds frames instead of blocking the
 * encoders. Video passes only from a keyframe AU onward — the RTSP join
 * gate's twin — and the AVC/AAC sequence headers are (re)sent whenever the
 * underlying parameter sets changed (encoder restart), always ahead of the
 * frames that need them. H.265 has no standard RTMP mapping; the output
 * owner rejects it before a publisher is ever built.
 */
internal class RtmpPublisher(
    private val url: RtmpUrl,
    private val source: EncodedSource,
    private val onStatus: (RtmpStatus) -> Unit,
    private val socketFactory: (RtmpUrl) -> Socket = ::defaultSocket,
) : RtmpPublisherHandle {

    private sealed interface Media {
        data class Video(val nalUnits: List<EncodedNalUnit>) : Media
        data class Audio(val aac: ByteArray) : Media
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wanted = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private val mediaChannel = Channel<Media>(
        capacity = MEDIA_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Cross-thread socket state: the encoder threads feed, the session and
    // writer coroutines own the socket. [writeLock] serializes every write
    // (media, commands, control) onto one stream.
    @Volatile private var socket: Socket? = null
    private val writeLock = Any()

    // The current attempt's collaborators; meaningful only inside runAttempt.
    private var chunkWriter: RtmpChunkWriter? = null
    private var chunkReader: RtmpChunkReader? = null
    private val commandQueue = ConcurrentLinkedQueue<RtmpCommand>()

    // Acknowledgement window accounting (0 = server asked for none).
    @Volatile private var ackWindowBytes = 0
    @Volatile private var bytesOut = 0L
    @Volatile private var lastAckedBytes = 0L

    // Sequence-header bookkeeping: resent when the parameter sets change.
    private var sentVideoSps: ByteArray? = null
    private var sentVideoPps: ByteArray? = null
    private var sentAudioSeq: ByteArray? = null
    private var videoArmed = false
    private var publishStreamId = 0
    private var sessionBaseMs = 0L

    @Volatile private var statusValue: RtmpStatus = RtmpStatus.Idle
    private val random = Random()

    override fun status(): RtmpStatus = statusValue

    private fun publishStatus(status: RtmpStatus) {
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
                publishStatus(RtmpStatus.Connecting)
                try {
                    runAttempt()
                    attempt = 0 // a session that ended cleanly starts over without penalty
                } catch (e: Exception) {
                    if (!wanted.get()) break
                    publishStatus(RtmpStatus.Error(e.message ?: "connection failed"))
                    Log.w(TAG, "RTMP attempt to ${url.hostAndPort} failed: ${e.message}")
                }
                if (!wanted.get()) break
                val backoffMs = backoffMs(attempt)
                attempt += 1
                delay(backoffMs)
            }
            publishStatus(RtmpStatus.Idle)
        }
        session.invokeOnCompletion { scope.cancel() }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        wanted.set(false)
        // Closing the channel ends the writer's drain loop, whose tail is the
        // clean close (FCUnpublish/DeleteStream + socket close). A dead
        // network can block that write, so a delayed force-close guarantees
        // the socket never outlives the stop.
        mediaChannel.close()
        val sock = socket
        scope.launch {
            delay(STOP_FORCE_CLOSE_MS)
            runCatching { sock?.close() }
        }
    }

    // ── the media taps (encoder drain threads) ──

    override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
        if (nalUnits.isEmpty()) return
        mediaChannel.trySend(Media.Video(nalUnits))
    }

    override fun feedAudio(aacData: ByteArray) {
        if (aacData.isEmpty()) return
        mediaChannel.trySend(Media.Audio(aacData))
    }

    // ── one connection attempt: handshake → connect → publish → stream ──

    private suspend fun runAttempt() {
        val sock = socketFactory(url)
        socket = sock
        var writerJob: Job? = null
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = READ_TIMEOUT_MS
            sock.connect(InetSocketAddress(url.host, url.port), CONNECT_TIMEOUT_MS)
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            handshake(input, output)

            val writer = RtmpChunkWriter(output)
            val reader = RtmpChunkReader(::handleMessage)
            synchronized(writeLock) { chunkWriter = writer }
            chunkReader = reader
            ackWindowBytes = 0
            bytesOut = 0
            lastAckedBytes = 0

            // Announce our outgoing chunk size, then actually use it.
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_PROTOCOL_CONTROL,
                RtmpChunkProtocol.TYPE_SET_CHUNK_SIZE,
                0,
                0,
                int32(RtmpChunkProtocol.PUBLISHER_OUT_CHUNK_SIZE),
            )
            writer.setChunkSize(RtmpChunkProtocol.PUBLISHER_OUT_CHUNK_SIZE)

            // connect — app + tcUrl (+ URL userinfo credentials)
            val deadline = nowMs() + COMMAND_TIMEOUT_MS
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_COMMAND,
                RtmpChunkProtocol.TYPE_COMMAND_AMF0,
                0,
                0,
                RtmpCommands.connect(url),
            )
            val connectReply = awaitReply(reader, input, deadline) { it.transactionId == TXN_CONNECT }
            if (connectReply.name == "_error") {
                throw RtmpPublishException("RTMP connect rejected: ${RtmpCommands.errorMessage(connectReply.args)}")
            }

            // releaseStream (no reply expected) → createStream → publish
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_COMMAND,
                RtmpChunkProtocol.TYPE_COMMAND_AMF0,
                0,
                0,
                RtmpCommands.releaseStream(TXN_RELEASE, url.streamKey),
            )
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_COMMAND,
                RtmpChunkProtocol.TYPE_COMMAND_AMF0,
                0,
                0,
                RtmpCommands.createStream(TXN_CREATE),
            )
            val streamReply = awaitReply(reader, input, nowMs() + COMMAND_TIMEOUT_MS) { it.transactionId == TXN_CREATE }
            if (streamReply.name == "_error") {
                throw RtmpPublishException("RTMP createStream rejected: ${RtmpCommands.errorMessage(streamReply.args)}")
            }
            // §7.2.2: the reply is (txn, null command object, stream id) — the
            // id rides behind the command object, so pick the reply's Number.
            val streamId = streamReply.args.filterIsInstance<AmfValue.Number>().firstOrNull()?.value?.toInt()
                ?: throw RtmpPublishException("RTMP createStream reply carried no stream id")
            publishStreamId = streamId

            writeMessage(
                writer,
                RtmpChunkProtocol.CS_COMMAND,
                RtmpChunkProtocol.TYPE_COMMAND_AMF0,
                streamId,
                0,
                RtmpCommands.publish(TXN_PUBLISH, url.streamKey),
            )
            val publishReply = awaitReply(reader, input, nowMs() + COMMAND_TIMEOUT_MS) { it.name == "onStatus" }
            // §7.2.3: the info object rides behind the null command object.
            val onStatusInfo = publishReply.args.filterIsInstance<AmfValue.Obj>().firstOrNull()
            val level = (onStatusInfo?.get("level") as? AmfValue.Str)?.value
            val code = (onStatusInfo?.get("code") as? AmfValue.Str)?.value
            val publishRefused = level == "error" ||
                (code != null && code.startsWith("NetStream.Publish.") && code != "NetStream.Publish.Start")
            if (publishRefused) {
                throw RtmpPublishException(
                    "RTMP publish rejected: ${RtmpCommands.errorMessage(publishReply.args)}",
                )
            }
            if (code != "NetStream.Publish.Start" && level != "status") {
                throw RtmpPublishException("RTMP publish was not confirmed (code=${code ?: "none"})")
            }

            // Live: arm the join gate fresh, note the timestamp base, and
            // re-request a keyframe BEFORE announcing Connected — anything
            // observing the status is entitled to rely on the re-request.
            videoArmed = false
            sentVideoSps = null
            sentVideoPps = null
            sentAudioSeq = null
            sessionBaseMs = nowMs()
            source.requestKeyFrame()
            publishStatus(RtmpStatus.Connected)
            Log.i(TAG, "RTMP publishing ${url.streamKey} to ${url.hostAndPort}/${url.app}")

            writerJob = scope.launch {
                runCatching { drainMedia(writer) }
                    .onFailure { if (wanted.get()) Log.w(TAG, "RTMP media writer ended: ${it.message}") }
            }

            // The read half runs here until the socket dies or stop() closes it.
            readLoop(reader, input)
            if (!wanted.get()) return
            throw IOException("RTMP connection closed by server")
        } finally {
            writerJob?.cancel()
            runCatching { sock.close() }
            synchronized(writeLock) { chunkWriter = null }
            chunkReader = null
            socket = null
        }
    }

    /** C0/C1 out, S0/S1 in, C2 out (the S1 echo), S2 in (accepted as-is). */
    private fun handshake(input: InputStream, output: OutputStream) {
        output.write(RtmpHandshake.c0c1(random))
        output.flush()
        val s0s1 = readFully(input, 1 + RtmpHandshake.HANDSHAKE_SIZE)
        val s1 = RtmpHandshake.s1From(s0s1)
            ?: throw RtmpPublishException("RTMP handshake rejected (bad S0/S1 from server)")
        output.write(RtmpHandshake.c2FromS1(s1))
        output.flush()
        readFully(input, RtmpHandshake.HANDSHAKE_SIZE) // S2 — plain handshake accepts it
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) throw IOException("RTMP handshake truncated at $read/$count")
            read += n
        }
        return out
    }

    // ── replies: read until a command matches ──

    private fun awaitReply(
        reader: RtmpChunkReader,
        input: InputStream,
        deadlineMs: Long,
        matches: (RtmpCommand) -> Boolean,
    ): RtmpCommand {
        while (true) {
            val queued = commandQueue.firstOrNull(matches)
            if (queued != null) {
                commandQueue.remove(queued)
                return queued
            }
            if (nowMs() >= deadlineMs) {
                throw RtmpPublishException("RTMP server did not answer in time")
            }
            pumpReader(reader, input)
        }
    }

    /** One blocking read + parse pass; a read timeout is not an error here. */
    private fun pumpReader(reader: RtmpChunkReader, input: InputStream) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val n = try {
            input.read(buffer)
        } catch (_: SocketTimeoutException) {
            return
        }
        if (n < 0) throw IOException("RTMP connection closed by server")
        if (n > 0) reader.feed(buffer, 0, n)
    }

    private fun readLoop(reader: RtmpChunkReader, input: InputStream) {
        while (wanted.get()) {
            pumpReader(reader, input)
        }
    }

    // ── incoming message dispatch (the reader thread of the attempt) ──

    private fun handleMessage(typeId: Int, streamId: Int, timestampMs: Int, payload: ByteArray) {
        val reader = chunkReader ?: return
        when (typeId) {
            RtmpChunkProtocol.TYPE_SET_CHUNK_SIZE -> {
                if (payload.size >= 4) reader.setChunkSize(readInt32(payload, 0) and 0x7FFFFFFF)
            }
            RtmpChunkProtocol.TYPE_WINDOW_ACK_SIZE -> {
                if (payload.size >= 4) ackWindowBytes = readInt32(payload, 0)
            }
            RtmpChunkProtocol.TYPE_ACKNOWLEDGEMENT -> Unit // the server acking our bytes — tracked, not acted on
            RtmpChunkProtocol.TYPE_USER_CONTROL -> handleUserControl(payload)
            RtmpChunkProtocol.TYPE_COMMAND_AMF0 -> {
                RtmpCommand.parse(payload)?.let { commandQueue.add(it) }
            }
            else -> Unit // data messages and audio/video echoes are not ours to read
        }
    }

    private fun handleUserControl(payload: ByteArray) {
        if (payload.size < 2) return
        val eventType = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (eventType == RtmpChunkProtocol.EVENT_PING_REQUEST && payload.size >= 6) {
            // Ping response mirrors the request's 4-byte payload.
            val response = payload.copyOfRange(2, 6)
            writeControl(
                RtmpChunkProtocol.TYPE_USER_CONTROL,
                byteArrayOf(
                    ((RtmpChunkProtocol.EVENT_PING_RESPONSE shr 8) and 0xFF).toByte(),
                    (RtmpChunkProtocol.EVENT_PING_RESPONSE and 0xFF).toByte(),
                ) + response,
            )
        }
    }

    // ── the media writer ──

    private suspend fun drainMedia(writer: RtmpChunkWriter) {
        var alive = true
        for (message in mediaChannel) {
            if (!alive) continue
            try {
                when (message) {
                    is Media.Video -> writeVideoMessage(writer, message)
                    is Media.Audio -> writeAudioMessage(writer, message)
                }
            } catch (e: Exception) {
                if (wanted.get()) Log.w(TAG, "RTMP media write failed: ${e.message}")
                alive = false
                // Unblocks the read half (which owns this attempt's teardown)
                // instead of leaving both sides stalled on a dead socket.
                runCatching { socket?.close() }
            }
        }
        // Channel closed — the stop path. Clean close while the socket still works.
        cleanClose(writer)
    }

    private fun writeVideoMessage(writer: RtmpChunkWriter, message: Media.Video) {
        val nalUnits = message.nalUnits
        if (!videoArmed) {
            // The join gate: a mid-GOP P-frame AU can never start the wire.
            if (nalUnits.none { it.isKeyFrame }) return
            videoArmed = true
        }
        val sps = source.sps
        val pps = source.pps
        if (sps == null || pps == null) {
            // Keyframe AUs carry SPS/PPS through the assembler's prepend, so
            // the hub's cached sets land before the AU we still need to gate on.
            source.requestKeyFrame()
            videoArmed = false
            return
        }
        val isKeyFrame = nalUnits.any { it.isKeyFrame }
        maybeSendVideoSequenceHeader(writer, sps, pps)
        val tag = FlvTag.avcPacket(nalUnits.map { it.data }, isKeyFrame)
        writeAndTrack(writer, RtmpChunkProtocol.CS_VIDEO, RtmpChunkProtocol.TYPE_VIDEO, publishStreamId, tag)
    }

    private fun writeAudioMessage(writer: RtmpChunkWriter, message: Media.Audio) {
        val asc = source.audioSpecificConfig ?: return // no audio config yet — frames resume when it lands
        maybeSendAudioSequenceHeader(writer, asc)
        val tag = FlvTag.aacFrame(message.aac)
        writeAndTrack(writer, RtmpChunkProtocol.CS_AUDIO, RtmpChunkProtocol.TYPE_AUDIO, publishStreamId, tag)
    }

    private fun maybeSendVideoSequenceHeader(writer: RtmpChunkWriter, sps: ByteArray, pps: ByteArray) {
        if (sentVideoSps?.contentEquals(sps) == true && sentVideoPps?.contentEquals(pps) == true) return
        writeAndTrack(
            writer,
            RtmpChunkProtocol.CS_VIDEO,
            RtmpChunkProtocol.TYPE_VIDEO,
            publishStreamId,
            FlvTag.avcSequenceHeader(sps, pps),
        )
        sentVideoSps = sps.copyOf()
        sentVideoPps = pps.copyOf()
    }

    private fun maybeSendAudioSequenceHeader(writer: RtmpChunkWriter, asc: ByteArray) {
        if (sentAudioSeq?.contentEquals(asc) == true) return
        writeAndTrack(
            writer,
            RtmpChunkProtocol.CS_AUDIO,
            RtmpChunkProtocol.TYPE_AUDIO,
            publishStreamId,
            FlvTag.aacSequenceHeader(asc),
        )
        sentAudioSeq = asc.copyOf()
    }

    /** The clean-close pair: FCUnpublish, DeleteStream, then the socket (which unblocks the read half). */
    private fun cleanClose(writer: RtmpChunkWriter) {
        runCatching {
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_COMMAND,
                RtmpChunkProtocol.TYPE_COMMAND_AMF0,
                publishStreamId,
                0,
                RtmpCommands.fcUnpublish(url.streamKey),
            )
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_COMMAND,
                RtmpChunkProtocol.TYPE_COMMAND_AMF0,
                publishStreamId,
                0,
                RtmpCommands.deleteStream(publishStreamId.toDouble()),
            )
        }
        runCatching { socket?.close() }
    }

    // ── write plumbing: one serialized path + ack-window tracking ──

    private fun writeAndTrack(writer: RtmpChunkWriter, csid: Int, typeId: Int, streamId: Int, payload: ByteArray) {
        val written = writeMessage(writer, csid, typeId, streamId, mediaTimestampMs(), payload)
        maybeAcknowledge(writer, written)
    }

    private fun writeMessage(
        writer: RtmpChunkWriter,
        csid: Int,
        typeId: Int,
        streamId: Int,
        timestampMs: Int,
        payload: ByteArray,
    ): Int = synchronized(writeLock) {
        writer.write(csid, timestampMs, typeId, streamId, payload)
    }

    private fun writeControl(typeId: Int, payload: ByteArray) {
        val writer = synchronized(writeLock) { chunkWriter } ?: return
        val written = writeMessage(writer, RtmpChunkProtocol.CS_PROTOCOL_CONTROL, typeId, 0, 0, payload)
        maybeAcknowledge(writer, written)
    }

    private fun maybeAcknowledge(writer: RtmpChunkWriter, written: Int) {
        bytesOut += written
        val window = ackWindowBytes
        if (window > 0 && bytesOut - lastAckedBytes >= window) {
            lastAckedBytes = bytesOut
            writeMessage(
                writer,
                RtmpChunkProtocol.CS_PROTOCOL_CONTROL,
                RtmpChunkProtocol.TYPE_ACKNOWLEDGEMENT,
                0,
                0,
                int32(bytesOut.toInt()),
            )
        }
    }

    private fun mediaTimestampMs(): Int {
        val ms = nowMs() - sessionBaseMs
        return ms.coerceAtLeast(0).toInt() and 0x7FFFFFFF
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun backoffMs(attempt: Int): Long {
        var ms = INITIAL_BACKOFF_MS
        repeat(attempt.coerceAtLeast(0)) {
            ms = (ms * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return ms
    }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        private const val TAG = "RtmpPublisher"

        private const val TXN_CONNECT = 1.0
        private const val TXN_RELEASE = 2.0
        private const val TXN_CREATE = 3.0
        private const val TXN_PUBLISH = 4.0

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val COMMAND_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val READ_BUFFER_BYTES = 16 * 1024
        private const val MEDIA_CHANNEL_CAPACITY = 128
        private const val STOP_FORCE_CLOSE_MS = 2_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

/** The default transport: a plain socket, or a `javax.net.ssl` TLS socket for `rtmps://`. */
private fun defaultSocket(url: RtmpUrl): Socket =
    if (url.secure) {
        javax.net.ssl.SSLSocketFactory.getDefault().createSocket()
    } else {
        Socket()
    }
