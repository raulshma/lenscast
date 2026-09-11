package com.raulshma.lenscast.streaming.rtmp

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The publisher's whole connect/publish ladder against a scripted in-process
 * RTMP server over real loopback sockets: the plain handshake exchange, the
 * `connect` → `createStream` → `publish("live")` command ladder with real
 * `_result`/`onStatus` replies, the Set Chunk Size announcement, the AVC/AAC
 * sequence headers ahead of their media, and the readable error status when
 * the server rejects the connect. [RtmpPublisher] runs on Dispatchers.IO and
 * android.util.Log returns defaults under unitTests.returnDefaultValues.
 */
class RtmpPublisherTest {

    // ── fakes ──

    private class FakeSource : EncodedSource {
        override val sps: ByteArray = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        override val pps: ByteArray = byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEC.toByte())
        override val vps: ByteArray? = null
        override val videoCodec: RtspVideoCodec = RtspVideoCodec.H264
        override val audioSpecificConfig: ByteArray = byteArrayOf(0x12, 0x10)

        val keyFrameRequests = AtomicInteger(0)

        override fun requestKeyFrame() {
            keyFrameRequests.incrementAndGet()
        }
    }

    /**
     * The scripted server: plain handshake, then _result/onStatus replies for
     * the command ladder, and capture queues for the media messages.
     */
    private class FakeRtmpServer(private val rejectConnect: Boolean = false) {
        private val server = ServerSocket()
        private val worker = Thread { serve() }
        val port: Int
        val video = LinkedBlockingQueue<ByteArray>()
        val audio = LinkedBlockingQueue<ByteArray>()
        val setChunkSizeValue = AtomicInteger(0)

        init {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            port = server.localPort
            worker.isDaemon = true
            worker.start()
        }

        private fun serve() {
            val conn = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            try {
                conn.soTimeout = 15_000
                val input = conn.getInputStream()
                val output = conn.getOutputStream()

                // Handshake: read C0/C1, answer S0/S1, read C2, answer S2.
                readFully(input, 1 + RtmpHandshake.HANDSHAKE_SIZE)
                val s1 = ByteArray(RtmpHandshake.HANDSHAKE_SIZE)
                Random().nextBytes(s1)
                output.write(RtmpHandshake.RTMP_VERSION)
                output.write(s1)
                output.flush()
                readFully(input, RtmpHandshake.HANDSHAKE_SIZE)
                output.write(ByteArray(RtmpHandshake.HANDSHAKE_SIZE))
                output.flush()

                val commands = LinkedBlockingQueue<RtmpCommand>()
                var readerRef: RtmpChunkReader? = null
                val reader = RtmpChunkReader { typeId, _, _, payload ->
                    when (typeId) {
                        RtmpChunkProtocol.TYPE_SET_CHUNK_SIZE -> {
                            if (payload.size >= 4) {
                                val value = ((payload[0].toInt() and 0xFF) shl 24) or
                                    ((payload[1].toInt() and 0xFF) shl 16) or
                                    ((payload[2].toInt() and 0xFF) shl 8) or
                                    (payload[3].toInt() and 0xFF)
                                setChunkSizeValue.set(value and 0x7FFFFFFF)
                                readerRef?.setChunkSize(value and 0x7FFFFFFF)
                            }
                        }
                        RtmpChunkProtocol.TYPE_COMMAND_AMF0 -> {
                            RtmpCommand.parse(payload)?.let { commands.put(it) }
                        }
                        RtmpChunkProtocol.TYPE_VIDEO -> if (video.size < 8) video.put(payload.copyOf())
                        RtmpChunkProtocol.TYPE_AUDIO -> if (audio.size < 8) audio.put(payload.copyOf())
                    }
                }
                readerRef = reader
                val writer = RtmpChunkWriter(output)
                var connectAnswered = false
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    reader.feed(buffer, 0, n)
                    while (true) {
                        val command = commands.poll() ?: break
                        when {
                            command.name == "connect" && !connectAnswered -> {
                                connectAnswered = true
                                val reply = if (rejectConnect) {
                                    Amf0.encode(
                                        listOf(
                                            AmfValue.Str("_error"),
                                            AmfValue.Number(1.0),
                                            AmfValue.Null,
                                            AmfValue.Obj(
                                                listOf(
                                                    "level" to AmfValue.Str("error"),
                                                    "code" to AmfValue.Str("NetConnection.Connect.Rejected"),
                                                    "description" to AmfValue.Str("bad app"),
                                                ),
                                            ),
                                        ),
                                    )
                                } else {
                                    Amf0.encode(
                                        listOf(AmfValue.Str("_result"), AmfValue.Number(1.0), AmfValue.Null),
                                    )
                                }
                                writer.write(RtmpChunkProtocol.CS_COMMAND, 0, RtmpChunkProtocol.TYPE_COMMAND_AMF0, 0, reply)
                            }
                            command.name == "createStream" -> {
                                val reply = Amf0.encode(
                                    listOf(
                                        AmfValue.Str("_result"),
                                        AmfValue.Number(3.0),
                                        AmfValue.Null,
                                        AmfValue.Number(1.0),
                                    ),
                                )
                                writer.write(RtmpChunkProtocol.CS_COMMAND, 0, RtmpChunkProtocol.TYPE_COMMAND_AMF0, 0, reply)
                            }
                            command.name == "publish" -> {
                                val reply = Amf0.encode(
                                    listOf(
                                        AmfValue.Str("onStatus"),
                                        AmfValue.Number(0.0),
                                        AmfValue.Null,
                                        AmfValue.Obj(
                                            listOf(
                                                "level" to AmfValue.Str("status"),
                                                "code" to AmfValue.Str("NetStream.Publish.Start"),
                                            ),
                                        ),
                                    ),
                                )
                                writer.write(RtmpChunkProtocol.CS_COMMAND, 0, RtmpChunkProtocol.TYPE_COMMAND_AMF0, 1, reply)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // The publisher's stop closes the socket mid-read — the normal end.
            } finally {
                runCatching { conn.close() }
                runCatching { server.close() }
            }
        }

        private fun readFully(input: InputStream, count: Int): ByteArray {
            val out = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(out, read, count - read)
                if (n < 0) throw IllegalStateException("fake server: client truncated at $read/$count")
                read += n
            }
            return out
        }
    }

    /** Polls the status queue until [matches] holds or the deadline passes. */
    private fun awaitStatus(
        statuses: LinkedBlockingQueue<RtmpStatus>,
        timeoutMs: Long = 15_000,
        matches: (RtmpStatus) -> Boolean,
    ): RtmpStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: RtmpStatus? = null
        while (System.currentTimeMillis() < deadline) {
            val status = statuses.poll(100, TimeUnit.MILLISECONDS)
            if (status != null) {
                if (matches(status)) return status
                last = status
            }
        }
        fail("status never matched; last seen: $last")
        @Suppress("UNREACHABLE_CODE")
        return last ?: RtmpStatus.Idle
    }

    // ── the happy ladder ──

    @Test
    fun `the connect ladder completes and media flows behind its sequence headers`() {
        val server = FakeRtmpServer()
        val source = FakeSource()
        val statuses = LinkedBlockingQueue<RtmpStatus>()
        val url = RtmpUrl.parse("rtmp://127.0.0.1:${server.port}/live/key")!!
        val publisher = RtmpPublisher(url, source, onStatus = { statuses.put(it) })

        try {
            publisher.start()
            awaitStatus(statuses) { it is RtmpStatus.Connected }
            // The publish confirmation re-requests a keyframe.
            assertEquals(1, source.keyFrameRequests.get())
            // The publisher announced its outgoing chunk size before any command.
            assertEquals(RtmpChunkProtocol.PUBLISHER_OUT_CHUNK_SIZE, server.setChunkSizeValue.get())

            // One keyframe AU and one audio frame: each leads with its sequence header.
            publisher.feedVideo(listOf(EncodedNalUnit(byteArrayOf(0x65, 1, 2), isKeyFrame = true)))
            publisher.feedAudio(byteArrayOf(0x21, 0x00, 0x4C, 0x50))

            val videoSequenceHeader = server.video.poll(5, TimeUnit.SECONDS)
            assertNotNull("no video sequence header arrived", videoSequenceHeader)
            assertEquals(0x17, videoSequenceHeader!![0].toInt() and 0xFF) // key | AVC
            assertEquals(0, videoSequenceHeader[1].toInt()) // AVC sequence header
            val videoNalu = server.video.poll(5, TimeUnit.SECONDS)
            assertNotNull("no video AU arrived", videoNalu)
            assertEquals(0x17, videoNalu!![0].toInt() and 0xFF)
            assertEquals(1, videoNalu[1].toInt()) // AVC NALUs
            val audioSequenceHeader = server.audio.poll(5, TimeUnit.SECONDS)
            assertNotNull("no audio sequence header arrived", audioSequenceHeader)
            assertEquals(0x00, audioSequenceHeader!![1].toInt())
            val audioRaw = server.audio.poll(5, TimeUnit.SECONDS)
            assertNotNull("no raw AAC frame arrived", audioRaw)
            assertEquals(0x01, audioRaw!![1].toInt())
        } finally {
            publisher.stop()
        }
    }

    @Test
    fun `a mid-GOP video feed does not reach the wire before a keyframe`() {
        val server = FakeRtmpServer()
        val source = FakeSource()
        val statuses = LinkedBlockingQueue<RtmpStatus>()
        val url = RtmpUrl.parse("rtmp://127.0.0.1:${server.port}/live/key")!!
        val publisher = RtmpPublisher(url, source, onStatus = { statuses.put(it) })

        try {
            publisher.start()
            awaitStatus(statuses) { it is RtmpStatus.Connected }

            // A P-frame AU first: the join gate must hold it back (and the
            // missing parameter sets re-request a keyframe).
            publisher.feedVideo(listOf(EncodedNalUnit(byteArrayOf(0x41, 1), isKeyFrame = false)))
            val keyframeAu = listOf(EncodedNalUnit(byteArrayOf(0x65, 1, 2), isKeyFrame = true))
            publisher.feedVideo(keyframeAu)

            val first = server.video.poll(5, TimeUnit.SECONDS)
            assertNotNull("no video at all arrived", first)
            assertEquals(0, first!![1].toInt()) // sequence header first, not the dropped P-frame
            val second = server.video.poll(5, TimeUnit.SECONDS)
            assertNotNull(second)
            assertEquals(1, second!![1].toInt()) // then the keyframe AU
            assertTrue(source.keyFrameRequests.get() >= 1)
        } finally {
            publisher.stop()
        }
    }

    // ── the rejection path ──

    @Test
    fun `a connect rejection surfaces a readable error and the ladder retries`() {
        val server = FakeRtmpServer(rejectConnect = true)
        val source = FakeSource()
        val statuses = LinkedBlockingQueue<RtmpStatus>()
        val url = RtmpUrl.parse("rtmp://127.0.0.1:${server.port}/live/key")!!
        val publisher = RtmpPublisher(url, source, onStatus = { statuses.put(it) })

        try {
            publisher.start()
            awaitStatus(statuses) { it is RtmpStatus.Connecting }
            val error = awaitStatus(statuses) { status ->
                status is RtmpStatus.Error && status.message.contains("NetConnection.Connect.Rejected")
            }
            assertTrue((error as RtmpStatus.Error).message.contains("RTMP connect rejected"))
            // The capped-backoff loop keeps trying while the output is wanted.
            awaitStatus(statuses) { it is RtmpStatus.Connecting }
        } finally {
            publisher.stop()
        }
    }

    @Test
    fun `a refused port lands as an error status instead of hanging`() {
        // Bind, read the port, release: connections are refused fast on loopback.
        val dead = ServerSocket()
        dead.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val port = dead.localPort
        dead.close()

        val statuses = LinkedBlockingQueue<RtmpStatus>()
        val url = RtmpUrl.parse("rtmp://127.0.0.1:$port/live/key")!!
        val publisher = RtmpPublisher(url, FakeSource(), onStatus = { statuses.put(it) })

        try {
            publisher.start()
            awaitStatus(statuses) { it is RtmpStatus.Error }
        } finally {
            publisher.stop()
        }
    }
}
