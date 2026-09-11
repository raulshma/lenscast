package com.raulshma.lenscast.capture.ml

import android.util.Log
import com.raulshma.lenscast.update.UpdateHttp
import com.raulshma.lenscast.update.UpdateIntegrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The on-demand home of the YAMNet sound-classification model — the exact
 * lifecycle [DetectionModelStore] runs for the object detector, one class per
 * model so each engine, settings row, and download route reads its own state.
 * The tflite is *not* bundled in the APK; this store downloads it once, on
 * request, into app-private storage and hands the resolved file to the
 * [SoundClassificationEngine].
 *
 * Model provenance:
 * - File: `lite-model_yamnet_classification_tflite_1.tflite` (~3.9 MB), the
 *   TF Hub `lite-model/yamnet/classification/tflite/1` int8-float YAMNet
 *   classifier (MobileNetV1 embedding, 521 AudioSet classes, 16 kHz mono
 *   waveform input, 0.96 s windows); Apache-2.0. The class names are *not* in
 *   the tflite — [YamnetLabels] is the single index→name mapping.
 * - Source: https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite
 *   (the TensorFlow team's task-library mirror of the TF Hub artifact — the
 *   same download host the object-detection model pins).
 *
 * Integrity is fail-closed exactly like the detection model's: the download
 * lands in a `.part` file, is hashed streaming (SHA-256 through the update
 * stack's [UpdateIntegrity]) and must match both the pinned [EXPECTED_SHA256]
 * digest and the exact [EXPECTED_BYTES] length before an atomic same-directory
 * rename installs it. The same gate re-runs at [resolveModelFile] time: an
 * installed file that no longer matches is quarantined and the state demoted,
 * so a model corrupted at rest re-downloads instead of wedging on Ready.
 *
 * [requestDownload] is the one entry point and it is idempotent: a Ready or
 * in-flight model is never re-downloaded, a Failed one always may be.
 */
class AudioModelStore(
    /** App-private directory holding the model file (created on demand). */
    private val modelsDir: File,
    /** Overridable so the install ladder is JVM-testable with tiny fakes. */
    private val expectedSha256Hex: String = EXPECTED_SHA256,
    private val expectedBytes: Long = EXPECTED_BYTES,
    private val connectionFactory: (String) -> HttpURLConnection = {
        UpdateHttp.openConnection(it, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** The model file's lifecycle, published for the settings surface. */
    sealed interface State {
        /** No usable model on disk; nothing in flight. */
        data object NotDownloaded : State

        /** A download is running; [progress] is 0..1 (negative when unknown). */
        data class Downloading(val progress: Float) : State

        /** The verified model is installed at [file]. */
        data class Ready(val file: File) : State

        /** The last attempt failed; the reason is display-ready. */
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Guards one download at a time; [requestDownload] is a no-op while set. */
    private val downloadInFlight = AtomicBoolean(false)

    /**
     * Serializes the resolve-time gate's hash-then-quarantine with the
     * installer's delete-and-rename, so a verdict can never land on a file
     * swapped in mid-hash.
     */
    private val gateLock = Any()

    init {
        // A model installed by a previous run starts the lifecycle at Ready; a
        // corrupt-at-rest one is quarantined by the resolve below, which leaves
        // the NotDownloaded default standing. Off the calling thread — the
        // resolve's integrity re-check hashes the full model.
        scope.launch {
            resolveModelFile()?.let { ready -> _state.value = State.Ready(ready) }
        }
    }

    /**
     * The installed model file for the engine, or null while it is missing or
     * corrupt. Reads the disk, not [state] — the file outlives the process and
     * the engine must find a model downloaded by a previous run even if no
     * collector ever observed the Ready state. The size-then-digest re-check
     * is what keeps a corrupt install from wedging the lifecycle: any mismatch
     * is quarantined and the state demoted off Ready, so the next
     * [requestDownload] re-fetches. Called only on init attempts and download
     * starts, never per audio window.
     */
    fun resolveModelFile(): File? {
        val file = installedFile()
        if (!file.isFile) return null
        synchronized(gateLock) {
            if (file.length() != expectedBytes) {
                quarantineLocked(file, "wrong size (${file.length()} bytes)")
                return null
            }
            val verdict = UpdateIntegrity.verdictFor(
                UpdateIntegrity.sha256Hex(file.inputStream()),
                expectedDigest(),
            )
            if (verdict == UpdateIntegrity.Verdict.Verified) return file
            quarantineLocked(file, "failed the integrity re-check")
            return null
        }
    }

    /**
     * Applies a resolve-time verdict: deletes the file that failed the gate
     * and demotes the state off Ready. Must run under [gateLock] and is
     * skipped while a download is in flight — the in-flight download's own
     * [verifyAndInstall] gate is authoritative for the file it puts in place.
     */
    private fun quarantineLocked(file: File, why: String) {
        if (downloadInFlight.get()) return
        Log.w(TAG, "Installed audio model $why; quarantining")
        file.delete()
        if (_state.value is State.Ready) {
            _state.value = State.Failed(QUARANTINE_REASON)
        }
    }

    /**
     * Requests the model download; idempotent. Ready models and in-flight
     * downloads are never restarted; a Failed state always retries. Fire and
     * forget — progress lands on [state], failures on [State.Failed], never a
     * thrown exception to the caller.
     */
    fun requestDownload() {
        if (_state.value is State.Ready || _state.value is State.Downloading) return
        if (!downloadInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                downloadNow()
            } catch (e: Exception) {
                Log.w(TAG, "Audio model download failed: ${e.message}")
                _state.value = State.Failed(e.message ?: "download failed")
            } finally {
                downloadInFlight.set(false)
            }
        }
    }

    /** The download body, run on the store scope with the single-flight claim held. */
    private fun downloadNow() {
        // Re-checked under the claim: a download that landed while this request
        // was being made must not re-download over a Ready model.
        resolveModelFile()?.let { ready ->
            _state.value = State.Ready(ready)
            return
        }
        modelsDir.mkdirs()
        val partFile = File(modelsDir, PART_SUFFIXED_NAME)
        partFile.delete()
        _state.value = State.Downloading(PROGRESS_UNKNOWN)

        val connection = connectionFactory(MODEL_URL)
        try {
            connection.inputStream.buffered().use { input ->
                partFile.outputStream().buffered().use { output ->
                    val contentLength = connection.contentLength.toLong()
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var totalRead = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            _state.value = State.Downloading(
                                (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            when (val installed = verifyAndInstall(partFile)) {
                is State.Ready -> {
                    Log.i(TAG, "Audio model installed (${installed.file.length()} bytes)")
                    _state.value = installed
                }
                is State.Failed -> _state.value = installed
                else -> _state.value = State.Failed("unexpected install state")
            }
        } catch (e: Exception) {
            // An exception mid-stream leaves a partial file behind; the gate
            // below never ran, so discard it here.
            partFile.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The integrity gate between the bytes arriving and the model existing:
     * exact size, then streaming SHA-256 against the pinned digest, then the
     * atomic same-directory rename. Any failure deletes the `.part` file and
     * comes back as [State.Failed] — a partial model is never installed.
     * Internal + injected expectations so the ladder is JVM-testable.
     */
    internal fun verifyAndInstall(partFile: File): State {
        if (!partFile.isFile || partFile.length() != expectedBytes) {
            partFile.delete()
            return State.Failed("downloaded size ${partFile.length()} bytes, expected $expectedBytes")
        }
        val verdict = UpdateIntegrity.verdictFor(
            UpdateIntegrity.sha256Hex(partFile.inputStream()),
            expectedDigest(),
        )
        if (verdict != UpdateIntegrity.Verdict.Verified) {
            partFile.delete()
            return State.Failed("SHA-256 mismatch; download discarded")
        }
        // Replace, don't overwrite, under the gate lock so a resolver can
        // neither hash the file during this swap nor quarantine the fresh
        // result.
        synchronized(gateLock) {
            val installed = installedFile()
            if (installed.exists() && !installed.delete()) {
                partFile.delete()
                return State.Failed("could not replace the installed model")
            }
            if (!partFile.renameTo(installed)) {
                partFile.delete()
                return State.Failed("could not move model into place")
            }
            return State.Ready(installed)
        }
    }

    private fun installedFile(): File = File(modelsDir, MODEL_FILE_NAME)

    /** The pinned expectation, composed in the update stack's `[scheme]+hex` digest format. */
    private fun expectedDigest(): String = UpdateIntegrity.DIGEST_SCHEME + expectedSha256Hex

    companion object {
        private const val TAG = "AudioModelStore"

        /** [State.Failed] reason after a corrupt install was quarantined by [resolveModelFile]. */
        internal const val QUARANTINE_REASON = "installed model failed verification; download again"

        const val MODEL_FILE_NAME = "lite-model_yamnet_classification_tflite_1.tflite"
        private const val PART_SUFFIXED_NAME = "$MODEL_FILE_NAME.part"

        /**
         * The app-private directory name under filesDir the model installs
         * into — the same directory the object-detection model lives in; the
         * file names never collide.
         */
        const val DEFAULT_DIR_NAME = DetectionModelStore.DEFAULT_DIR_NAME

        /** The versionless TensorFlow task-library download for the model (see class KDoc). */
        const val MODEL_URL =
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/$MODEL_FILE_NAME"

        /**
         * SHA-256 of the exact bytes at [MODEL_URL] — the TF Hub
         * `lite-model/yamnet/classification/tflite/1` artifact, pinned so a
         * corrupted or substituted response can never install.
         */
        const val EXPECTED_SHA256 = "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de"

        /** Exact size of the model at [MODEL_URL]; a size mismatch fails before hashing. */
        const val EXPECTED_BYTES = 4_126_810L

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024

        /** Progress emitted while the server sent no content length. */
        const val PROGRESS_UNKNOWN = -1f

        /** Human-facing size for the settings copy; kept next to [EXPECTED_BYTES]. */
        const val DISPLAY_SIZE_MB = "3.9 MB"
    }
}
