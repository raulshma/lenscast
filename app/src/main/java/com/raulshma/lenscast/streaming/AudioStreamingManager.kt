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
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// RtspAudioSource: the mic-capture seam [RtspOutput] drives the RTSP audio
// track through; the method set is already this interface's shape.
class AudioStreamingManager(private val context: Context) : RtspAudioSource {

    data class Config(
        val bitrateKbps: Int = StreamDefaults.AUDIO_BITRATE_KBPS,
        val channelCount: Int = StreamDefaults.AUDIO_CHANNELS,
        val echoCancellation: Boolean = true,
    )

    private val isStreaming = AtomicBoolean(false)
    private val subscribers = ConcurrentHashMap<Long, AudioClientStream>()
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
        return AudioClientStream(subscriberId).also { subscribers[subscriberId] = it }
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
        val snapshot = subscribers.values.toList()
        snapshot.forEach { it.enqueue(chunk) }
    }

    private fun cleanupRecorder() {
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

    private inner class AudioClientStream(
        private val subscriberId: Long,
    ) : InputStream() {

        private val lock = Object()
        private val pendingChunks = ArrayDeque<ByteArray>()

        private var currentChunk: ByteArray? = null
        private var currentOffset = 0
        private var closed = false

        override fun read(): Int {
            val singleByte = ByteArray(1)
            val read = read(singleByte, 0, 1)
            return if (read <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset + length > target.size) {
                throw IndexOutOfBoundsException()
            }
            if (length == 0) return 0

            synchronized(lock) {
                while (true) {
                    val chunk = currentChunk
                    if (chunk != null && currentOffset < chunk.size) {
                        val toCopy = minOf(length, chunk.size - currentOffset)
                        System.arraycopy(chunk, currentOffset, target, offset, toCopy)
                        currentOffset += toCopy
                        if (currentOffset >= chunk.size) {
                            currentChunk = null
                            currentOffset = 0
                        }
                        return toCopy
                    }

                    if (pendingChunks.isNotEmpty()) {
                        currentChunk = pendingChunks.removeFirst()
                        currentOffset = 0
                        continue
                    }

                    if (closed || !isStreaming.get()) {
                        return -1
                    }

                    lock.wait(20L)
                }
            }
        }

        fun enqueue(chunk: ByteArray) {
            synchronized(lock) {
                if (closed) return
                while (pendingChunks.size >= MAX_PENDING_CHUNKS) {
                    pendingChunks.removeFirst()
                }
                pendingChunks.addLast(chunk)
                lock.notifyAll()
            }
        }

        fun shutdown() {
            synchronized(lock) {
                closed = true
                pendingChunks.clear()
                currentChunk = null
                currentOffset = 0
                lock.notifyAll()
            }
        }

        override fun close() {
            subscribers.remove(subscriberId)
            shutdown()
        }
    }

    companion object {
        private const val TAG = "AudioStreamingManager"
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_PENDING_CHUNKS = 6
    }
}
