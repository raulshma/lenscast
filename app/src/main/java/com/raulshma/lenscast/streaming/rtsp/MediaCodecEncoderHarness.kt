package com.raulshma.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * One MediaCodec lifecycle harness behind [H264Encoder] and [AacEncoder].
 * Both encoders used to hand-roll the identical scaffold — the running
 * guard, the create → configure → start ladder returning Boolean with
 * the guard flipped off on failure, the output-drain loop with its 10ms
 * dequeue poll, INFO_OUTPUT_FORMAT_CHANGED branch, and two-clause exception
 * ladder, and the join-then-release teardown. This class owns that scaffold;
 * the encoders keep only what is genuinely theirs — MediaFormat construction,
 * CSD interpretation, input feeding, and the frame callbacks — plugged in as
 * the constructor strategies below.
 *
 * The guard is an explicit three-phase machine ([Phase]) rather than the old
 * boolean: the AAC input stream's end-of-file path ([requestStop]) stops the
 * loops WITHOUT teardown — the codec stays allocated until the owner's next
 * [stop] (or the next [start] superseding the generation) releases it. A
 * boolean could not represent "loops exited, codec still live", so every EOF
 * used to leak one MediaCodec instance per mic restart; the phase makes the
 * draining state nameable and its teardown mandatory.
 *
 * [CodecLike] is the test seam for the Android-bound MediaCodec: production
 * wraps the real codec in [MediaCodecAdapter], JVM tests drive a fake, so the
 * lifecycle invariants (start idempotence, stop-after-failed-start safety,
 * EOF-drain teardown, drain exception classification) are pinned without a
 * device. The seam mirrors exactly the MediaCodec calls the encoder pair
 * makes — nothing more.
 */
internal class MediaCodecEncoderHarness(
    private val tag: String,
    private val threadName: String,
    /** Logged once per successful fresh start, inside the guard, after the drain thread is up. */
    private val startedMessage: () -> String,
    private val startFailureMessage: String,
    private val outputErrorMessage: String,
    /**
     * Creates the codec for one start attempt, after the running guard has
     * flipped on; may also reset per-session encoder state (pending counters,
     * cached CSD) so a failed attempt leaves the encoder as freshly reset.
     */
    private val createCodec: () -> CodecLike,
    /** Encoder-specific configuration between create and start: capabilities query + MediaFormat. */
    private val configureCodec: (CodecLike) -> Unit,
    /** Invoked on the drain thread when the codec reports INFO_OUTPUT_FORMAT_CHANGED. */
    private val onFormatChanged: (CodecLike) -> Unit,
    /** Invoked on the drain thread for every output buffer (any flags), before release. */
    private val onOutput: (ByteBuffer, OutputInfo) -> Unit,
) {

    /**
     * The output-buffer metadata a dequeue fills — size, offset, flags, and
     * presentation time for the buffer at the dequeued index.
     */
    class OutputInfo {
        var offset: Int = 0
        var size: Int = 0
        var flags: Int = 0
        var presentationTimeUs: Long = 0
    }

    /**
     * The harness's lifecycle phase:
     * - [Idle] — no live codec; [start] builds one.
     * - [Running] — codec started, drain thread up; [requestStop] leaves
     *   this phase for [Draining], and a second [start] here is a no-op.
     * - [Draining] — the EOF path's stop: loops exited, codec still
     *   allocated until the next [stop]/[start] releases it. The phase the
     *   old boolean could not represent, which is where the codecs leaked.
     */
    private enum class Phase { Idle, Running, Draining }

    private val phase = AtomicReference(Phase.Idle)
    private var codec: CodecLike? = null
    private var outputThread: Thread? = null

    /**
     * The loop gate, consumed by the drain loop and the encoders' feed loops
     * — true only while [Phase.Running]; a draining harness reports not
     * running (which is exactly the hub's re-attach probe semantics).
     */
    val isRunning: Boolean
        get() = phase.get() == Phase.Running

    /** The live codec handle — non-null from a successful [start] until [stop] releases it. */
    val activeCodec: CodecLike?
        get() = codec

    /**
     * The guarded create → configure → start ladder. Idempotent: a start
     * while already running reports success without touching the codec. A
     * start while [Phase.Draining] first tears down the EOF-superseded
     * generation, then builds the new one. Any failure along the ladder
     * releases the attempt's codec and drops back to [Phase.Idle], so a
     * later [stop] — and a later [start] retry — stay safe.
     */
    fun start(): Boolean {
        val previous = phase.getAndSet(Phase.Running)
        if (previous == Phase.Running) return true

        // A previous stream's EOF ([requestStop]) leaves its codec live:
        // tear that generation down before building the new one, or every
        // reattach after a mic restart drops one codec on the floor.
        if (previous == Phase.Draining) teardown()
        codec = null

        var attempt: CodecLike? = null
        return try {
            val codec = createCodec().also { attempt = it }
            configureCodec(codec)
            codec.start()
            this.codec = codec

            outputThread = Thread({ drainOutput(codec) }, threadName).apply {
                isDaemon = true
                start()
            }

            Log.d(tag, startedMessage())
            true
        } catch (e: Exception) {
            Log.e(tag, startFailureMessage, e)
            // The attempt's codec is live memory even though the guard never
            // published it ([codec] only assigns after start succeeds) —
            // release it, or every failed start leaks one codec instance.
            attempt?.let { failed -> runCatching { failed.release() } }
            phase.set(Phase.Idle)
            false
        }
    }

    /**
     * Teardown: joins the drain thread ([STOP_JOIN_TIMEOUT_MS], the encoders'
     * original bound), then stop/release under the same silent catch-all.
     * Tears down a [Phase.Running] harness and a [Phase.Draining] one (the
     * EOF path's deferred release); the result says which happened, so
     * encoders can gate their own stop-side state on it.
     */
    fun stop(): Boolean {
        return when (phase.getAndSet(Phase.Idle)) {
            Phase.Idle -> false
            Phase.Running, Phase.Draining -> {
                teardown()
                true
            }
        }
    }

    /**
     * Ends the drain (and any encoder-side feed) loops and moves the machine
     * to [Phase.Draining] — the AAC input-stream end-of-file path asks for
     * the stop this way, and the codec's release is deferred to the owner's
     * next [stop] or [start]. The loops poll the same phase they always
     * polled ([isRunning] goes false either way).
     */
    fun requestStop() {
        phase.compareAndSet(Phase.Running, Phase.Draining)
    }

    /** The join-then-release teardown, shared by [stop] and a Draining [start]. */
    private fun teardown() {
        try {
            outputThread?.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
        }
        outputThread = null

        try {
            codec?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        codec = null
    }

    /**
     * The output-drain loop: the 10ms dequeue poll both encoders used,
     * INFO_OUTPUT_FORMAT_CHANGED routed to [onFormatChanged], output buffers
     * to [onOutput] then released, and the two-clause exception ladder — an
     * IllegalStateException is logged (unless we're tearing down) and breaks;
     * anything else breaks silently.
     */
    private fun drainOutput(codec: CodecLike) {
        val bufferInfo = OutputInfo()

        while (phase.get() == Phase.Running) {
            try {
                val outputBufferIndex = codec.dequeueOutput(bufferInfo, DEQUEUE_TIMEOUT_US)

                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                        onOutput(outputBuffer, bufferInfo)
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec)
                }
            } catch (e: IllegalStateException) {
                if (phase.get() == Phase.Running) Log.e(tag, outputErrorMessage, e)
                break
            } catch (_: Exception) {
                break
            }
        }
    }

    companion object {
        // The exact MediaCodec timings both encoders used inline, pinned by
        // MediaCodecEncoderHarnessTest.
        internal const val DEQUEUE_TIMEOUT_US = 10_000L
        internal const val STOP_JOIN_TIMEOUT_MS = 3_000L
    }
}

/**
 * The narrow codec surface the harness and the two encoder strategies drive —
 * every MediaCodec call the encoder pair makes, nothing more. MediaCodec is
 * final and Android-bound, so this seam stands in for it at the lifecycle
 * decision points the harness owns; [MediaCodecAdapter] is the production
 * wrapper.
 */
internal interface CodecLike {
    /** MediaCodec.configure(format, null, null, CONFIGURE_FLAG_ENCODE). */
    fun configureEncode(format: MediaFormat)

    fun start()
    fun stop()
    fun release()

    /** Color formats the codec declares for [mimeType] (the capabilities query). */
    fun supportedColorFormats(mimeType: String): Set<Int>

    /** Optional-feature probe behind optional format keys (FEATURE_LowLatency). */
    fun isFeatureSupported(mimeType: String, feature: String): Boolean

    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun setParameters(params: Bundle)

    /** The codec's current output format, read at format-changed time for its csd buffers. */
    fun outputFormat(): MediaFormat

    fun dequeueOutput(info: MediaCodecEncoderHarness.OutputInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int, render: Boolean)
}

/**
 * The production adapter: a real MediaCodec behind the [CodecLike] seam.
 */
@Suppress("DEPRECATION")
internal class MediaCodecAdapter(private val codec: MediaCodec) : CodecLike {

    private val bufferInfo = MediaCodec.BufferInfo()

    override fun configureEncode(format: MediaFormat) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    override fun start() = codec.start()

    override fun stop() = codec.stop()

    override fun release() = codec.release()

    override fun supportedColorFormats(mimeType: String): Set<Int> {
        return codec.codecInfo.getCapabilitiesForType(mimeType).colorFormats.toSet()
    }

    override fun isFeatureSupported(mimeType: String, feature: String): Boolean {
        return codec.codecInfo.getCapabilitiesForType(mimeType).isFeatureSupported(feature)
    }

    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)

    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun setParameters(params: Bundle) {
        codec.setParameters(params)
    }

    override fun outputFormat(): MediaFormat = codec.outputFormat

    override fun dequeueOutput(info: MediaCodecEncoderHarness.OutputInfo, timeoutUs: Long): Int {
        val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        info.offset = bufferInfo.offset
        info.size = bufferInfo.size
        info.flags = bufferInfo.flags
        info.presentationTimeUs = bufferInfo.presentationTimeUs
        return index
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        codec.releaseOutputBuffer(index, render)
    }
}
