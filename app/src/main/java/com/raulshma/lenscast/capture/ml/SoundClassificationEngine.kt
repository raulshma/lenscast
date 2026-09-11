package com.raulshma.lenscast.capture.ml

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The MediaPipe Tasks audio classifier behind sound classification: YAMNet
 * (521 AudioSet classes) over the same 16 kHz mono windows the model card
 * specifies — 0.96 s of PCM ([WINDOW_SAMPLES] samples), converted from
 * whatever the mic path captures (48 kHz mono by default; the capture probe
 * ladder may resolve otherwise, so [feed] takes the live format and the
 * conversion is pure math in [toMonoFloat16k]).
 *
 * The engine rides the sound-detection audio path but never blocks it:
 * [feed] (audio reader thread) only copies bytes into a bounded window
 * buffer and hands one window at a time to a single dedicated worker
 * ([CLASSIFIER_THREAD]); a busy worker means the window is dropped, never
 * queued. Every failure mode — API < 24 (tasks-audio's minSdk, the same
 * manifest override the vision detector carries), a missing or broken model,
 * an inference error — maps to "no verdict this window": the caller's sound
 * events are annotate-only and fail open exactly like the ML object gate.
 *
 * The model file is *not* bundled; it is resolved per init attempt through
 * [modelFileProvider] (production: [AudioModelStore.resolveModelFile]), so a
 * download that lands at any moment is picked up by the next window. Only a
 * real init failure latches the engine off for the process lifetime
 * ([DetectorInitGate] — shared with the object detector, same semantics); a
 * missing model never latches. YAMNet carries no class names in the tflite,
 * so a scored output index maps through [YamnetLabels] — the raw top-1
 * (label + score percent) goes to [listener]; thresholding and the
 * allow-list are [com.raulshma.lenscast.capture.model.SoundClassPolicy]'s,
 * not the engine's.
 */
class SoundClassificationEngine(
    /** Application context; MediaPipe's task factory is context-based. */
    private val context: Context,
    /**
     * Resolves the downloaded YAMNet file, or null while it is missing
     * (production: [AudioModelStore.resolveModelFile]).
     */
    private val modelFileProvider: () -> File?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /**
     * Receives each completed window's raw top-1 — the exact YAMNet display
     * name (or null when the model returned no usable category) and its
     * confidence as a percent. Invoked on the classifier worker, never the
     * audio thread.
     */
    private val listener: (label: String?, scorePercent: Float) -> Unit,
) {

    private val initStarted = AtomicBoolean(false)
    private val lock = Any()

    /**
     * MediaPipe Tasks Audio (like Vision) declares minSdk 24 while the app
     * keeps minSdk 23: below Nougat the library must never be touched —
     * class resolution or its native loader would throw past the fail-open
     * catch ladders — so the engine reports the same no-verdict as a missing
     * model and sound events pass through unlabeled.
     */
    private val mlSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    private var classifier: AudioClassifier? = null
    private val initGate = DetectorInitGate()

    @Volatile
    private var closed = false
    private var lastWarnMs = 0L

    /** The single classification worker; one window in flight at a time. */
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, CLASSIFIER_THREAD).apply { isDaemon = true }
    }
    private val classifying = AtomicBoolean(false)

    /**
     * The un-classified tail of the audio path, guarded by [lock]. Bounded by
     * construction: [feed] drains whole windows out of it, and a busy worker
     * makes it drop the oldest window instead of growing.
     */
    private var pending = ByteArray(0)

    /** Live capture format for the bytes [feed] receives; read per feed, cheap. */
    @Volatile
    private var inputFormat: AudioFormat = AudioFormat()

    /** One feed's capture shape: [sampleRateHz] and [channelCount] of the PCM. */
    data class AudioFormat(val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ, val channelCount: Int = 1)

    /**
     * Feeds one PCM16 chunk off the audio reader thread. Never blocks, never
     * throws: the copy into [pending] is O(chunk), the window assembly is
     * pointer math, and inference happens on the worker. Chunks whose frames
     * do not divide evenly keep their remainder for the next feed.
     */
    fun feed(pcm16: ByteArray, sampleRateHz: Int = inputFormat.sampleRateHz, channelCount: Int = inputFormat.channelCount) {
        if (closed || pcm16.isEmpty()) return
        inputFormat = AudioFormat(sampleRateHz.coerceAtLeast(1), channelCount.coerceAtLeast(1))
        val bytesPerFrame = inputFormat.channelCount * 2
        // The window is 0.96 s of audio at the *capture* rate — WINDOW_SAMPLES
        // counts 16 kHz samples, so the buffered frame count scales by the
        // live rate before the conversion resamples those frames back down
        // (or up) to WINDOW_SAMPLES.
        val windowFrames = (WINDOW_SAMPLES.toLong() * inputFormat.sampleRateHz / TARGET_SAMPLE_RATE_HZ).toInt()
        val windowBytes = windowFrames * bytesPerFrame
        // The reader publishes frame-aligned chunks; a defensive trim keeps a
        // misaligned tail (or a mid-stream format flip's leftover byte) from
        // shifting every later frame.
        val aligned = if (pcm16.size % bytesPerFrame == 0) {
            pcm16
        } else {
            pcm16.copyOf(pcm16.size - pcm16.size % bytesPerFrame)
        }
        val window: ByteArray = synchronized(lock) {
            pending += aligned
            when {
                pending.size < windowBytes -> null
                // A busy worker means backpressure: keep the newest window's
                // bytes and drop the older ones — the detector's events never
                // wait on inference.
                !classifying.compareAndSet(false, true) -> {
                    pending = pending.copyOfRange(
                        pending.size - windowBytes,
                        pending.size,
                    )
                    null
                }
                else -> {
                    val windowBytesOut = pending.copyOf(windowBytes)
                    pending = pending.copyOfRange(windowBytes, pending.size)
                    windowBytesOut
                }
            }
        } ?: return
        executor.execute {
            try {
                classifyWindow(window, bytesPerFrame)
            } finally {
                classifying.set(false)
            }
        }
    }

    /** Releases the native classifier; the engine stays safely inert afterwards. */
    fun close() {
        closed = true
        synchronized(lock) { pending = ByteArray(0) }
        executor.execute {
            synchronized(lock) {
                classifier?.close()
                classifier = null
            }
        }
    }

    /** Classifies one window's bytes on the worker; any failure is a no-verdict. */
    private fun classifyWindow(windowBytes: ByteArray, bytesPerFrame: Int) {
        try {
            val classifier = classifierOrNull() ?: run {
                listener(null, 0f)
                return
            }
            val samples = toMonoFloat16k(
                pcm16 = windowBytes,
                channelCount = bytesPerFrame / 2,
                sampleRateHz = inputFormat.sampleRateHz,
            )
            val audioData = AudioData.create(AUDIO_DATA_FORMAT, samples.size)
            // AudioData's ring buffer is the model's actual input (classify
            // reads it back through getBuffer()); load() is the one API that
            // fills it — getBuffer() itself hands out a defensive copy, so a
            // copy into that would classify an all-zero window.
            audioData.load(samples)
            val result = classifier.classify(audioData)
            val top = result.classificationResults()
                .firstOrNull()
                ?.classifications()
                ?.firstOrNull()
                ?.categories()
                ?.maxByOrNull { it.score() }
            // YAMNet's tflite carries scores, not names: the output index is
            // the only key, and the name lives in the generated class map.
            val label = top?.let { YamnetLabels.labelFor(it.index()) ?: it.categoryName() }
            listener(label, (top?.score() ?: 0f) * 100f)
        } catch (e: Exception) {
            warnOncePerMinute("Sound classification window failed: ${e.message}")
            listener(null, 0f)
        }
    }

    private fun classifierOrNull(): AudioClassifier? {
        if (!mlSupported) {
            warnOncePerMinute("Sound classification unavailable before Android 7.0 (API 24); sound events pass through unlabeled (fail-open)")
            return null
        }
        if (!initGate.canAttempt) return null
        synchronized(lock) {
            classifier?.let { return it }
        }
        if (!initStarted.compareAndSet(false, true)) {
            // Another thread is initializing; no verdict until it lands.
            return null
        }
        val modelFile = modelFileProvider() ?: run {
            // No latch: the download can land at any moment via
            // AudioModelStore.requestDownload, and the next window retries.
            warnOncePerMinute("Audio model not downloaded yet; sound events pass through unlabeled (fail-open)")
            initStarted.set(false)
            return null
        }
        return try {
            val options =
                AudioClassifier.AudioClassifierOptions
                    .builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetBuffer(readModelBuffer(modelFile))
                            .build(),
                    )
                    .setRunningMode(RunningMode.AUDIO_CLIPS)
                    .build()
            val created = AudioClassifier.createFromOptions(context, options)
            synchronized(lock) { classifier = created }
            Log.i(TAG, "YAMNet audio classifier ready (${modelFile.name})")
            created
        } catch (e: FileNotFoundException) {
            // The file vanished between the resolve and the load (a
            // concurrent quarantine can do that) — the missing case again,
            // never a latch: reset the attempt and let the next window retry.
            initStarted.set(false)
            warnOncePerMinute("Audio model file disappeared during init; retrying")
            null
        } catch (e: Throwable) {
            initGate.onInitFailure()
            warnOncePerMinute("YAMNet audio classifier unavailable (${e.javaClass.simpleName}: ${e.message}); classification disabled")
            null
        }
    }

    /**
     * The model bytes as a direct [ByteBuffer] — MediaPipe retains the buffer
     * for the classifier's lifetime, so it must outlive this call and cannot
     * be heap-array backed.
     */
    private fun readModelBuffer(modelFile: File): ByteBuffer {
        modelFile.inputStream().use { input ->
            val buffer = ByteBuffer.allocateDirect(modelFile.length().toInt())
            val channel = java.nio.channels.Channels.newChannel(input)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) break
            }
            if (buffer.hasRemaining()) throw FileNotFoundException("$modelFile truncated")
            buffer.rewind()
            return buffer
        }
    }

    private fun warnOncePerMinute(message: String) {
        synchronized(lock) {
            val now = clockMs()
            if (now - lastWarnMs < WARN_INTERVAL_MS) return
            lastWarnMs = now
        }
        Log.w(TAG, message)
    }

    companion object {
        private const val TAG = "SoundClassification"
        private const val CLASSIFIER_THREAD = "SoundClassification"

        /** YAMNet's input contract: 0.96 s at 16 kHz. */
        const val WINDOW_SAMPLES = 15_360

        /** YAMNet's input rate; the capture path's live rate converts into this. */
        const val TARGET_SAMPLE_RATE_HZ = 16_000

        /** The default capture rate while no feed declared one (StreamDefaults' shared default). */
        const val DEFAULT_SAMPLE_RATE_HZ = com.raulshma.lenscast.core.StreamDefaults.AUDIO_SAMPLE_RATE_HZ

        private const val WARN_INTERVAL_MS = 60_000L

        /**
         * The one input format the classifier is ever built with: 16 kHz mono
         * floats. Lazy so the companion (whose pure math is JVM-tested) never
         * touches the task library until classification really runs.
         */
        private val AUDIO_DATA_FORMAT by lazy {
            AudioDataFormat.builder().setSampleRate(TARGET_SAMPLE_RATE_HZ.toFloat()).setNumOfChannels(1).build()
        }

        /**
         * Pure window conversion: interleaved PCM16 at [sampleRateHz] /
         * [channelCount] → [outSamples] mono floats resampled to
         * [TARGET_SAMPLE_RATE_HZ] by linear interpolation (stereo frames
         * average first). JVM-tested; the same math serves every capture
         * rate the probe ladder resolves (48k/44.1k/32k/24k/16k) and the
         * 16k pass-through short-circuits to a plain decode.
         */
        fun toMonoFloat16k(
            pcm16: ByteArray,
            channelCount: Int,
            sampleRateHz: Int,
            outSamples: Int = WINDOW_SAMPLES,
        ): FloatArray {
            require(channelCount >= 1) { "channelCount must be >= 1" }
            val frames = pcm16.size / (channelCount * 2)
            val out = FloatArray(outSamples)
            val step = sampleRateHz.toDouble() / TARGET_SAMPLE_RATE_HZ
            for (i in 0 until outSamples) {
                val src = i * step
                val i0 = src.toInt()
                if (i0 + 1 >= frames) {
                    out[i] = if (frames == 0) 0f else frameAt(pcm16, channelCount, frames - 1)
                    continue
                }
                val frac = (src - i0).toFloat()
                val a = frameAt(pcm16, channelCount, i0)
                val b = frameAt(pcm16, channelCount, i0 + 1)
                out[i] = a + (b - a) * frac
            }
            return out
        }

        /** Frame [index] averaged to mono, as a float in -1..1. */
        private fun frameAt(pcm16: ByteArray, channelCount: Int, index: Int): Float {
            var sum = 0
            val base = index * channelCount * 2
            for (c in 0 until channelCount) {
                val low = pcm16[base + c * 2].toInt() and 0xFF
                val high = pcm16[base + c * 2 + 1].toInt()
                sum += (high shl 8) or low
            }
            return sum / (channelCount * 32768f)
        }
    }
}
