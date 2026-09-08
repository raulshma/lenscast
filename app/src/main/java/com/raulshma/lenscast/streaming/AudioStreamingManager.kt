package com.raulshma.lenscast.streaming

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.rtsp.AacFormat
import android.os.Process
import android.util.Log
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// RtspAudioSource: the mic-capture seam [RtspOutput] drives the RTSP audio
// track through; the method set is already this interface's shape.
// Per-subscriber backpressure (bounded drop-oldest queue, blocking
// InputStream handoff, EOF after shutdown) lives in [AudioSubscriberPipe];
// the manager keeps capture, effects, and the reader fan-out.
class AudioStreamingManager(private val context: Context) : RtspAudioSource {

    data class Config(
        val bitrateKbps: Int = StreamDefaults.AUDIO_BITRATE_KBPS,
        val channelCount: Int = StreamDefaults.AUDIO_CHANNELS,
        val echoCancellation: Boolean = true,
    )

    private val isStreaming = AtomicBoolean(false)
    private val isTalking = AtomicBoolean(false)
    @Volatile private var talkUntilMs = 0L
    @Volatile private var talkbackTrack: android.media.AudioTrack? = null
    private val subscribers = ConcurrentHashMap<Long, AudioSubscriberPipe>()
    private val nextSubscriberId = AtomicLong(1L)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var activeConfig: AacFormat.ResolvedBuffers = AacFormat.ResolvedBuffers()

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    override fun start(config: Config): Boolean {
        if (isStreaming.get()) return true
        if (!MicAccess.isGranted(context)) {
            Log.w(TAG, "Microphone permission is not granted, skipping audio stream")
            return false
        }

        return try {
            val resolved = resolveConfig(config)
            val recorder = buildAudioRecord(resolved, config.echoCancellation)
            recorder.startRecording()

            audioRecord = recorder
            activeConfig = resolved
            isStreaming.set(true)
            startReader(recorder, resolved)

            Log.d(TAG, "Audio streaming started: $resolved")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio streaming", e)
            cleanupRecorder()
            false
        }
    }

    override fun stop() {
        if (!isStreaming.getAndSet(false)) return

        cleanupRecorder()

        val activeSubscribers = subscribers.values.toList()
        subscribers.clear()
        activeSubscribers.forEach { it.shutdown() }

        Log.d(TAG, "Audio streaming stopped")
    }

    override fun openStream(): InputStream? {
        if (!isStreaming.get()) return null
        val subscriberId = nextSubscriberId.getAndIncrement()
        val pipe = AudioSubscriberPipe(onClose = { subscribers.remove(subscriberId) })
        subscribers[subscriberId] = pipe
        return pipe
    }

    override fun isRunning(): Boolean = isStreaming.get()

    override fun getSampleRateHz(): Int = activeConfig.sampleRateHz

    override fun getChannelCount(): Int = activeConfig.channelCount

    fun release() {
        stop()
    }

    private fun resolveConfig(config: Config): AacFormat.ResolvedBuffers {
        // The capture-resolution decision is pure buffer math in [AacFormat];
        // only the platform probe stays here.
        return AacFormat.resolveBuffers(config.channelCount) { sampleRate, channelConfig ->
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, AUDIO_ENCODING)
        }
    }

    private fun buildAudioRecord(config: AacFormat.ResolvedBuffers, echoCancellation: Boolean): AudioRecord {
        val audioSource = if (echoCancellation) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        return AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(config.sampleRateHz)
                    .setChannelMask(config.channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(config.bufferSizeBytes)
            .build()
            .also { record ->
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    throw IllegalStateException("AudioRecord failed to initialize")
                }
                if (echoCancellation) {
                    enableAudioEffects(record.audioSessionId)
                }
            }
    }

    private fun enableAudioEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = true
                }
                if (echoCanceler != null) {
                    Log.d(TAG, "AcousticEchoCanceler enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable AcousticEchoCanceler", e)
            }
        } else {
            Log.d(TAG, "AcousticEchoCanceler not available on this device")
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = true
                }
                if (noiseSuppressor != null) {
                    Log.d(TAG, "NoiseSuppressor enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable NoiseSuppressor", e)
            }
        } else {
            Log.d(TAG, "NoiseSuppressor not available on this device")
        }
    }

    private fun startReader(recorder: AudioRecord, config: AacFormat.ResolvedBuffers) {
        readerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(config.readChunkBytes)
            try {
                while (!Thread.currentThread().isInterrupted && isStreaming.get()) {
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        publish(buffer.copyOf(read))
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isStreaming.get()) {
                    Log.e(TAG, "Audio reader failed", e)
                }
            }
        }, "AudioStreamReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun publish(chunk: ByteArray) {
        // Half-duplex talkback: while the phone speaker plays browser audio,
        // drop mic chunks so the uplink doesn't echo back to the talker.
        if (isTalking.get() && System.currentTimeMillis() < talkUntilMs) return
        isTalking.set(false)
        val snapshot = subscribers.values.toList()
        snapshot.forEach { it.enqueue(chunk) }
    }

    /**
     * Push-to-talk uplink: browser PCM16 mono → phone speaker.
     * Half-duplex: mic fan-out pauses for [holdMs] after each play.
     */
    fun playUplink(pcm16: ByteArray, sampleRateHz: Int = 16_000, holdMs: Long = 800): Boolean {
        if (pcm16.isEmpty()) return false
        return try {
            isTalking.set(true)
            talkUntilMs = System.currentTimeMillis() + holdMs
            var track = talkbackTrack
            val chMask = AudioFormat.CHANNEL_OUT_MONO
            val minBuf = android.media.AudioTrack.getMinBufferSize(
                sampleRateHz, chMask, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(pcm16.size)
            if (track == null || track.state != android.media.AudioTrack.STATE_INITIALIZED || track.sampleRate != sampleRateHz) {
                runCatching { track?.release() }
                track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(chMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
                talkbackTrack = track
            }
            track!!.play()
            var off = 0
            while (off < pcm16.size) {
                val n = track.write(pcm16, off, pcm16.size - off)
                if (n <= 0) break
                off += n
            }
            Log.d(TAG, "Talkback played ${pcm16.size}B @${sampleRateHz}Hz")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Talkback playback failed", e)
            isTalking.set(false)
            false
        }
    }

    fun stopTalkback() {
        isTalking.set(false)
        runCatching { talkbackTrack?.pause() }
        runCatching { talkbackTrack?.flush() }
    }

    private fun cleanupRecorder() {
        runCatching { talkbackTrack?.pause() }
        runCatching { talkbackTrack?.flush() }
        runCatching { talkbackTrack?.release() }
        talkbackTrack = null
        isTalking.set(false)
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        runCatching { readerThread?.interrupt() }
        readerThread = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private const val TAG = "AudioStreamingManager"
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
