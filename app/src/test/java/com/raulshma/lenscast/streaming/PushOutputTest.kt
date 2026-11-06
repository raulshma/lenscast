package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtmp.RtmpOutput
import com.raulshma.lenscast.streaming.rtmp.RtmpPublisherHandle
import com.raulshma.lenscast.streaming.rtmp.RtmpStatus
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.raulshma.lenscast.streaming.srt.SrtOutput
import com.raulshma.lenscast.streaming.srt.SrtPublisherHandle
import com.raulshma.lenscast.streaming.srt.SrtStats
import com.raulshma.lenscast.streaming.srt.SrtStatus
import com.raulshma.lenscast.streaming.whip.WhipOutput
import com.raulshma.lenscast.streaming.whip.WhipPublisherHandle
import com.raulshma.lenscast.streaming.whip.WhipStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push outputs' shared lifecycle — enable→start→status transitions, the
 * start-rejection ladder, restart-on-config-change vs. the quiet paths,
 * disable-stops, the status mirror, and the settle-epilogue placement — run
 * once against all three transport configurations (RTMP, SRT, WHIP), each
 * built through its real thin output class exactly as the manager builds it.
 * The per-transport suites
 * ([com.raulshma.lenscast.streaming.rtmp.RtmpOutputTest] and siblings) keep
 * only what is genuinely transport-distinct; the duplicated lifecycle
 * assertions of the three former suites live here, once.
 */
class PushOutputTest {

    // ── fakes ──

    /** Canned [EncodedSource] — the hub-fed transports' codec-gate input. */
    private class FakeSource(
        override val videoCodec: RtspVideoCodec = RtspVideoCodec.H264,
    ) : EncodedSource {
        override val sps: ByteArray = byteArrayOf(0x67, 0x64, 0x00, 0x1F)
        override val pps: ByteArray = byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEC.toByte())
        override val vps: ByteArray? = null
        override val audioSpecificConfig: ByteArray = byteArrayOf(0x12, 0x10)

        override fun requestKeyFrame() = Unit
    }

    /** Recording base the three transport fakes share — the per-call counters the lifecycle assertions read. */
    private open class Recorder {
        var startCalls = 0
        var stopCalls = 0
    }

    private class FakeRtmpPublisher(private val onStatus: (RtmpStatus) -> Unit) : Recorder(), RtmpPublisherHandle {
        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun feedVideo(nalUnits: List<EncodedNalUnit>) = Unit

        override fun feedAudio(aacData: ByteArray) = Unit

        override fun status(): RtmpStatus = RtmpStatus.Idle

        fun fire(status: RtmpStatus) = onStatus(status)
    }

    private class FakeSrtPublisher(private val onStatus: (SrtStatus) -> Unit) : Recorder(), SrtPublisherHandle {
        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun feedVideo(nalUnits: List<EncodedNalUnit>) = Unit

        override fun feedAudio(aacData: ByteArray) = Unit

        override fun status(): SrtStatus = SrtStatus.Idle

        override fun stats(): SrtStats = SrtStats()

        fun fire(status: SrtStatus) = onStatus(status)
    }

    private class FakeWhipPublisher(private val onStatus: (WhipStatus) -> Unit) : Recorder(), WhipPublisherHandle {
        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) = Unit

        override fun setFrameRate(fps: Int) = Unit

        override fun status(): WhipStatus = WhipStatus.Idle

        fun fire(status: WhipStatus) = onStatus(status)
    }

    // ── the per-transport fixture ──

    /**
     * One transport under the harness: the thin output built as the manager
     * builds it, plus the transport-agnostic seams the shared assertions
     * read (recorders, statuses as [PushStatus], the settle counter).
     */
    private class Fixture(
        val label: String,
        val codecGated: Boolean,
        val validUrl: String,
        val otherValidUrl: String,
        val invalidUrl: String,
        val idle: PushStatus,
        val publishers: MutableList<Recorder>,
        val statuses: MutableList<PushStatus>,
        val settles: MutableList<Unit>,
        val setUrl: (String) -> Unit,
        val configuredUrl: () -> String,
        val setEnabled: (Boolean) -> Boolean,
        val isEnabled: () -> Boolean,
        val isActive: () -> Boolean,
        val status: () -> PushStatus,
        val start: () -> PushOutput.StartResult,
        val stop: () -> Unit,
        val fireConnecting: () -> Unit,
        val fireConnected: () -> Unit,
        val fireError: (String) -> Unit,
    )

    private fun rtmpFixture(codec: RtspVideoCodec = RtspVideoCodec.H264): Fixture {
        val publishers = mutableListOf<Recorder>()
        val statuses = mutableListOf<PushStatus>()
        val settles = mutableListOf<Unit>()
        val output = RtmpOutput(
            source = FakeSource(codec),
            onStatusChanged = { statuses += it },
            publisherFactory = { _, onStatus ->
                FakeRtmpPublisher(onStatus).also { publishers += it }
            },
            settle = { settles += Unit },
        )
        return Fixture(
            label = "RTMP",
            codecGated = true,
            validUrl = "rtmp://example.com/live/key",
            otherValidUrl = "rtmp://elsewhere:1936/live/key",
            invalidUrl = "http://example.com/live/key",
            idle = RtmpStatus.Idle,
            publishers = publishers,
            statuses = statuses,
            settles = settles,
            setUrl = output::setUrl,
            configuredUrl = output::configuredUrl,
            setEnabled = output::setEnabled,
            isEnabled = output::isEnabled,
            isActive = output::isActive,
            status = output::status,
            start = output::start,
            stop = output::stop,
            fireConnecting = { (publishers.last() as FakeRtmpPublisher).fire(RtmpStatus.Connecting) },
            fireConnected = { (publishers.last() as FakeRtmpPublisher).fire(RtmpStatus.Connected) },
            fireError = { message -> (publishers.last() as FakeRtmpPublisher).fire(RtmpStatus.Error(message)) },
        )
    }

    private fun srtFixture(codec: RtspVideoCodec = RtspVideoCodec.H264): Fixture {
        val publishers = mutableListOf<Recorder>()
        val statuses = mutableListOf<PushStatus>()
        val settles = mutableListOf<Unit>()
        val output = SrtOutput(
            source = FakeSource(codec),
            onStatusChanged = { statuses += it },
            publisherFactory = { _, onStatus ->
                FakeSrtPublisher(onStatus).also { publishers += it }
            },
            settle = { settles += Unit },
        )
        return Fixture(
            label = "SRT",
            codecGated = true,
            validUrl = "srt://listener.local:9000?streamid=abc",
            otherValidUrl = "srt://elsewhere.local:9710",
            invalidUrl = "not-an-srt-url",
            idle = SrtStatus.Idle,
            publishers = publishers,
            statuses = statuses,
            settles = settles,
            setUrl = output::setUrl,
            configuredUrl = output::configuredUrl,
            setEnabled = output::setEnabled,
            isEnabled = output::isEnabled,
            isActive = output::isActive,
            status = output::status,
            start = output::start,
            stop = output::stop,
            fireConnecting = { (publishers.last() as FakeSrtPublisher).fire(SrtStatus.Connecting) },
            fireConnected = { (publishers.last() as FakeSrtPublisher).fire(SrtStatus.Connected) },
            fireError = { message -> (publishers.last() as FakeSrtPublisher).fire(SrtStatus.Error(message)) },
        )
    }

    private fun whipFixture(audioAllowed: Boolean = true): Fixture {
        val publishers = mutableListOf<Recorder>()
        val statuses = mutableListOf<PushStatus>()
        val settles = mutableListOf<Unit>()
        val output = WhipOutput(
            audioAllowed = { audioAllowed },
            onStatusChanged = { statuses += it },
            publisherFactory = { _, _, _, _, onStatus ->
                FakeWhipPublisher(onStatus).also { publishers += it }
            },
            settle = { settles += Unit },
        )
        return Fixture(
            label = "WHIP",
            codecGated = false,
            validUrl = "http://example.com:8000/whip",
            otherValidUrl = "http://elsewhere:9000/whip-endpoint",
            invalidUrl = "rtmp://example.com/live/key",
            idle = WhipStatus.Idle,
            publishers = publishers,
            statuses = statuses,
            settles = settles,
            setUrl = output::setUrl,
            configuredUrl = output::configuredUrl,
            setEnabled = output::setEnabled,
            isEnabled = output::isEnabled,
            isActive = output::isActive,
            status = output::status,
            start = output::start,
            stop = output::stop,
            fireConnecting = { (publishers.last() as FakeWhipPublisher).fire(WhipStatus.Connecting) },
            fireConnected = { (publishers.last() as FakeWhipPublisher).fire(WhipStatus.Connected) },
            fireError = { message -> (publishers.last() as FakeWhipPublisher).fire(WhipStatus.Error(message)) },
        )
    }

    private fun allFixtures(): List<Fixture> = listOf(rtmpFixture(), srtFixture(), whipFixture())

    /** The enabled + configured + started ladder every lifecycle test starts from. */
    private fun Fixture.startPassing(): PushOutput.StartResult {
        setEnabled(true)
        setUrl(validUrl)
        return start()
    }

    private fun errorMessageOf(status: PushStatus): String? = when (status) {
        is RtmpStatus.Error -> status.message
        is SrtStatus.Error -> status.message
        is WhipStatus.Error -> status.message
        else -> null
    }

    // ── the validation ladder ──

    @Test
    fun `a disabled output refuses to start with the transport's readable reason`() {
        for (fixture in allFixtures()) {
            val result = fixture.start()
            assertTrue(fixture.label, result is PushOutput.StartResult.Rejected)
            assertEquals(
                fixture.label,
                "${fixture.label} push is disabled",
                (result as PushOutput.StartResult.Rejected).reason,
            )
            assertEquals(fixture.label, 0, fixture.publishers.size)
            assertFalse(fixture.label, fixture.isActive())
            assertEquals(
                fixture.label,
                "${fixture.label} push is disabled",
                errorMessageOf(fixture.status()),
            )
            // A refused manager-driven start settles like a started one.
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    @Test
    fun `an unusable URL refuses with the readable shape hint`() {
        for (fixture in allFixtures()) {
            fixture.setEnabled(true)
            fixture.setUrl(fixture.invalidUrl)

            val result = fixture.start()
            assertTrue(fixture.label, result is PushOutput.StartResult.Rejected)
            assertTrue(
                fixture.label,
                (result as PushOutput.StartResult.Rejected).reason.startsWith("Invalid ${fixture.label} URL"),
            )
            assertEquals(fixture.label, 0, fixture.publishers.size)
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    @Test
    fun `the codec gate follows the transport configuration`() {
        // The H.264-pinned hub consumers (RTMP: no standard H.265 mapping;
        // SRT: the shared TS muxing is H.264-pinned) refuse with the readable
        // codec hint. WHIP sits outside the encoded hub — no codec input
        // exists, so nothing can refuse for the codec setting.
        for (fixture in listOf(
            rtmpFixture(RtspVideoCodec.H265),
            srtFixture(RtspVideoCodec.H265),
            whipFixture(),
        )) {
            fixture.setEnabled(true)
            fixture.setUrl(fixture.validUrl)
            val result = fixture.start()
            if (fixture.codecGated) {
                assertTrue(fixture.label, result is PushOutput.StartResult.Rejected)
                assertEquals(
                    fixture.label,
                    "${fixture.label} push requires the H.264 video codec — the encoded path is currently H.265; " +
                        "switch the RTSP video codec to H.264 in streaming settings",
                    (result as PushOutput.StartResult.Rejected).reason,
                )
                assertEquals(fixture.label, 0, fixture.publishers.size)
            } else {
                assertEquals(fixture.label, PushOutput.StartResult.Started, result)
                assertEquals(fixture.label, 1, fixture.publishers.size)
            }
        }
    }

    // ── the start/stop lifecycle ──

    @Test
    fun `a passing start builds starts and settles once`() {
        for (fixture in allFixtures()) {
            assertEquals(fixture.label, PushOutput.StartResult.Started, fixture.startPassing())

            assertEquals(fixture.label, 1, fixture.publishers.size)
            assertEquals(fixture.label, 1, fixture.publishers.single().startCalls)
            assertTrue(fixture.label, fixture.isActive())
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    @Test
    fun `start is a no-op while already live`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()

            assertEquals(fixture.label, PushOutput.StartResult.Started, fixture.start())
            assertEquals(fixture.label, 1, fixture.publishers.size)
            assertEquals(fixture.label, 1, fixture.publishers.single().startCalls)
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    @Test
    fun `stop stops the publisher goes idle and settles once`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()
            val publisher = fixture.publishers.single()
            fixture.fireConnecting()

            fixture.stop()
            assertEquals(fixture.label, 1, publisher.stopCalls)
            assertFalse(fixture.label, fixture.isActive())
            assertEquals(fixture.label, fixture.idle, fixture.status())
            assertEquals(fixture.label, 2, fixture.settles.size)

            // A second stop is a no-op.
            fixture.stop()
            assertEquals(fixture.label, 1, publisher.stopCalls)
            assertEquals(fixture.label, 2, fixture.settles.size)
        }
    }

    @Test
    fun `disabling stops a live output quietly and arming alone never starts`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()
            val publisher = fixture.publishers.single()
            assertEquals(fixture.label, 1, fixture.settles.size)

            assertTrue(fixture.label, fixture.setEnabled(false))
            assertEquals(fixture.label, 1, publisher.stopCalls)
            assertFalse(fixture.label, fixture.isActive())
            // The disable-stop settles quietly — the manager's disable path
            // never ran the epilogue, and that ordering is preserved.
            assertEquals(fixture.label, 1, fixture.settles.size)

            // Re-arming does not start by itself — the start is a user/API action.
            assertTrue(fixture.label, fixture.setEnabled(true))
            assertFalse(fixture.label, fixture.isActive())
            assertEquals(fixture.label, 1, fixture.publishers.size)

            // Arming is idempotent.
            assertFalse(fixture.label, fixture.setEnabled(true))
        }
    }

    // ── retained config & restart-on-change ──

    @Test
    fun `the url is retained while stopped for the next start`() {
        for (fixture in allFixtures()) {
            fixture.setUrl(fixture.validUrl)
            assertEquals(fixture.label, fixture.validUrl, fixture.configuredUrl())
            assertEquals(fixture.label, 0, fixture.publishers.size)
        }
    }

    @Test
    fun `a url change restarts a live output on the new target without settling`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()
            val first = fixture.publishers.single()
            assertEquals(fixture.label, 1, fixture.settles.size)

            fixture.setUrl(fixture.otherValidUrl)
            assertEquals(fixture.label, 2, fixture.publishers.size)
            assertEquals(fixture.label, 1, first.stopCalls)
            assertEquals(fixture.label, 1, fixture.publishers[1].startCalls)
            assertTrue(fixture.label, fixture.isActive())
            // A restart settles quietly — the epilogue is the manager-driven
            // transitions' alone, and it did not run before the collapse.
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    @Test
    fun `a same-url set never churns a live output`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()

            fixture.setUrl(fixture.validUrl) // trimmed-equal
            fixture.setUrl("  ${fixture.validUrl}  ") // trimmed-equal
            assertEquals(fixture.label, 1, fixture.publishers.size)
            assertEquals(fixture.label, 0, fixture.publishers.single().stopCalls)
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    @Test
    fun `a restart onto an unusable URL lands stopped with the readable error`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()
            val first = fixture.publishers.single()

            fixture.setUrl(fixture.invalidUrl)
            assertFalse(fixture.label, fixture.isActive())
            assertEquals(fixture.label, 1, first.stopCalls)
            assertTrue(fixture.label, errorMessageOf(fixture.status())!!.startsWith("Invalid ${fixture.label} URL"))
            assertEquals(fixture.label, 1, fixture.settles.size)
        }
    }

    // ── the status mirror ──

    @Test
    fun `publisher status transitions mirror through onStatusChanged`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()

            fixture.fireConnecting()
            fixture.fireConnected()
            fixture.fireError("${fixture.label} connect rejected: nope")

            assertEquals(fixture.label, "connecting", fixture.statuses[0].wireName)
            assertEquals(fixture.label, "connected", fixture.statuses[1].wireName)
            assertEquals(
                fixture.label,
                "${fixture.label} connect rejected: nope",
                errorMessageOf(fixture.statuses[2]),
            )
            assertEquals(
                fixture.label,
                "${fixture.label} connect rejected: nope",
                errorMessageOf(fixture.status()),
            )
        }
    }

    @Test
    fun `a stopped output publishes idle after an error state`() {
        for (fixture in allFixtures()) {
            fixture.startPassing()
            fixture.fireError("boom")

            fixture.stop()
            assertEquals(fixture.label, fixture.idle, fixture.status())
            assertEquals(fixture.label, fixture.idle, fixture.statuses.last())
        }
    }

    @Test
    fun `a fresh output is idle disarmed and empty`() {
        for (fixture in allFixtures()) {
            assertEquals(fixture.label, fixture.idle, fixture.status())
            assertFalse(fixture.label, fixture.isEnabled())
            assertFalse(fixture.label, fixture.isActive())
            assertEquals(fixture.label, "", fixture.configuredUrl())
            assertEquals(fixture.label, 0, fixture.settles.size)
        }
    }

    // ── the settle-epilogue matrix ──

    @Test
    fun `the settle epilogue runs on manager-driven transitions only`() {
        for (fixture in allFixtures()) {
            // Manager-driven start: settles.
            fixture.startPassing()
            assertEquals(fixture.label, 1, fixture.settles.size)
            // Internal restart (config change): quiet.
            fixture.setUrl(fixture.otherValidUrl)
            assertEquals(fixture.label, 1, fixture.settles.size)
            // Internal restart (mic-verdict trigger shape — the quiet twin): quiet.
            fixture.setUrl(fixture.validUrl)
            assertEquals(fixture.label, 1, fixture.settles.size)
            // Manager-driven stop: settles.
            fixture.stop()
            assertEquals(fixture.label, 2, fixture.settles.size)
            // Disable-stop of the stopped output: no transition at all.
            fixture.setEnabled(false)
            fixture.setEnabled(true)
            assertEquals(fixture.label, 2, fixture.settles.size)
            // Disable-stop of a live output: quiet (the pre-collapse ordering).
            fixture.startPassing()
            assertEquals(fixture.label, 3, fixture.settles.size)
            fixture.setEnabled(false)
            assertEquals(fixture.label, 3, fixture.settles.size)
            assertFalse(fixture.label, fixture.isActive())
        }
    }
}
