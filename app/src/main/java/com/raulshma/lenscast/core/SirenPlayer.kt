package com.raulshma.lenscast.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Software siren: a looping two-tone oscillator on the voice-call audio
 * stream, loud enough to deter. One instance owns its playback thread;
 * start() while running is a no-op, stop() is always safe.
 */
class SirenPlayer {

    private val running = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = player
            player.play()
            thread = Thread({
                val buffer = ShortArray(CHUNK_SAMPLES)
                var phase = 0.0
                var samplesInHalf = 0
                var high = false
                try {
                    while (running.get()) {
                        // Two-tone siren: alternates 800 Hz ↔ 1 kHz every
                        // half second — the classic deterrent pattern.
                        for (i in buffer.indices) {
                            if (samplesInHalf >= SAMPLE_RATE_HZ / 2) {
                                samplesInHalf = 0
                                high = !high
                            }
                            samplesInHalf++
                            val frequency = if (high) FREQ_HIGH else FREQ_LOW
                            phase += 2.0 * Math.PI * frequency / SAMPLE_RATE_HZ
                            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                            buffer[i] = (AMPLITUDE * kotlin.math.sin(phase)).toInt().toShort()
                        }
                        player.write(buffer, 0, buffer.size)
                    }
                } catch (_: Exception) {
                }
            }, "SirenPlayer").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "Siren started")
        } catch (e: Exception) {
            Log.w(TAG, "Siren start failed", e)
            running.set(false)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.interrupt()
        thread = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        Log.d(TAG, "Siren stopped")
    }

    fun isRunning(): Boolean = running.get()

    companion object {
        private const val TAG = "SirenPlayer"
        private const val SAMPLE_RATE_HZ = 22_050
        private const val CHUNK_SAMPLES = 2_048
        private const val AMPLITUDE = 20_000.0
        private const val FREQ_LOW = 800.0
        private const val FREQ_HIGH = 1_000.0
    }
}
