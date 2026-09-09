package com.raulshma.lenscast.capture.ml

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.raulshma.lenscast.capture.model.DetectionClassPolicy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The LiteRT (TensorFlow Lite Task Vision) object detector behind the ML
 * motion gate: EfficientDet-Lite0 int8 with COCO metadata, loaded lazily from
 * the on-demand downloaded model file ([DetectionModelStore], resolved
 * through [modelFileProvider]) on first use ([classify]). The model ships
 * outside the APK; a missing file is a *retryable* unavailability, not a
 * failure — the next classify after [DetectionModelStore.requestDownload]
 * lands finds and loads it.
 *
 * Input is the camera's NV21 frame: it takes the cheap YuvImage→JPEG
 * round-trip and a power-of-two downscale (long edge ≤
 * [MAX_ANALYSIS_EDGE_PX]) before the task library resizes to the model's own
 * 320x320 input. The round-trip exists because the task library consumes
 * bitmaps only, and the downscale keeps the decode — the expensive step —
 * off the full-resolution path.
 *
 * Failure is graceful and never suppresses alerts: a missing model, a broken
 * init, an undecodable frame, and an inference error all map to
 * [Classification.Unavailable], logged at most once per [WARN_INTERVAL_MS]
 * (the caller feeds frames, so per-frame logging would flood the logcat).
 * Only a real init failure latches the engine off for the process lifetime
 * ([DetectorInitGate]); a missing model file never does. The caller decides
 * the fail-open semantics; the class allow-list lives in
 * [DetectionClassPolicy].
 */
class ObjectDetectionEngine(
    /**
     * Resolves the downloaded model file, or null while it is missing
     * (production: [DetectionModelStore.resolveModelFile]).
     */
    private val modelFileProvider: () -> File?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * One classification attempt. [Success] means inference actually ran —
     * its list may still be empty (nothing detected at all). [Unavailable]
     * means no verdict exists (model missing, model broken, frame undecodable)
     * and the caller must not treat it as "nothing detected".
     */
    sealed interface Classification {
        data class Success(val detections: List<DetectionClassPolicy.Detection>) : Classification
        data object Unavailable : Classification
    }

    private val initStarted = AtomicBoolean(false)
    private val lock = Any()

    private var detector: org.tensorflow.lite.task.vision.detector.ObjectDetector? = null
    private val initGate = DetectorInitGate()

    @Volatile
    private var closed = false
    private var lastWarnMs = 0L

    /**
     * Classify one frame synchronously. The caller owns rate limiting and the
     * inference executor; this method is intentionally blocking and must not
     * run on the frame path.
     */
    fun classify(frame: AnalysisFrame): Classification {
        if (closed) return Classification.Unavailable
        val detector = detectorOrNull() ?: return Classification.Unavailable
        val bitmap = try {
            decodeScaled(frame)
        } catch (e: Exception) {
            warnOncePerMinute("Frame decode failed: ${e.message}")
            return Classification.Unavailable
        } ?: return Classification.Unavailable
        return try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val detections = detector
                .detect(
                    tensorImage,
                    org.tensorflow.lite.task.core.vision.ImageProcessingOptions.builder().build(),
                )
                .flatMap { it.categories }
                .filter { !it.label.isNullOrBlank() }
                .map { category ->
                    DetectionClassPolicy.Detection(
                        label = category.label.lowercase(),
                        score = category.score,
                    )
                }
            Classification.Success(detections)
        } catch (e: Exception) {
            warnOncePerMinute("Inference failed: ${e.message}")
            Classification.Unavailable
        } finally {
            bitmap.recycle()
        }
    }

    /** Releases the native detector; the engine stays safely inert afterwards. */
    fun close() {
        closed = true
        synchronized(lock) {
            detector?.close()
            detector = null
        }
    }

    private fun detectorOrNull(): org.tensorflow.lite.task.vision.detector.ObjectDetector? {
        if (!initGate.canAttempt) return null
        synchronized(lock) {
            detector?.let { return it }
        }
        if (!initStarted.compareAndSet(false, true)) {
            // Another thread is initializing; treat as unavailable until it lands.
            return null
        }
        val modelFile = modelFileProvider() ?: run {
            // No latch: the model may land at any moment via
            // DetectionModelStore.requestDownload, and the next classify must
            // find it. The gate stays open; this is a warn, not a failure —
            // only onInitFailure latches, and a missing file is not one.
            warnOncePerMinute("Detection model not downloaded yet; ML gate passes events through (fail-open)")
            initStarted.set(false)
            return null
        }
        return try {
            val options =
                ObjectDetector.ObjectDetectorOptions
                    .builder()
                    .setBaseOptions(
                        // CPU is the task library's default backend; the thread
                        // count is the only knob we set.
                        BaseOptions.builder()
                            .setNumThreads(NUM_THREADS)
                            .build()
                    )
                    .setMaxResults(MAX_RESULTS)
                    .build()
            val created = ObjectDetector
                .createFromFileAndOptions(
                    modelFile,
                    options,
                )
            synchronized(lock) { detector = created }
            Log.i(TAG, "ML object detector ready (${modelFile.name})")
            created
        } catch (e: FileNotFoundException) {
            // The file vanished between the resolve and the load (a
            // concurrent quarantine can do that) — the missing case again,
            // never a latch: reset the attempt and let the next classify
            // retry.
            initStarted.set(false)
            warnOncePerMinute("Detection model file disappeared during init; retrying")
            null
        } catch (e: Throwable) {
            initGate.onInitFailure()
            warnOncePerMinute("ML object detector unavailable (${e.javaClass.simpleName}: ${e.message}); gate disabled")
            null
        }
    }

    /** NV21 → JPEG → downscaled bitmap, or null when the bytes cannot be decoded. */
    private fun decodeScaled(frame: AnalysisFrame): android.graphics.Bitmap? {
        val yuv = YuvImage(frame.nv21, ImageFormat.NV21, frame.width, frame.height, null)
        val jpeg = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, frame.width, frame.height), JPEG_QUALITY, jpeg)
        val bytes = jpeg.toByteArray()
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(frame.width, frame.height)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
        private const val TAG = "ObjectDetectionEngine"

        private const val NUM_THREADS = 2
        private const val MAX_RESULTS = 8
        private const val JPEG_QUALITY = 80

        /** Decode ceiling: the long edge lands at or under this before the model's own 320px resize. */
        const val MAX_ANALYSIS_EDGE_PX = 640

        private const val WARN_INTERVAL_MS = 60_000L

        /** Largest power-of-two [android.util.BitmapFactory.Options.inSampleSize] keeping the long edge ≤ [maxEdge]. */
        fun sampleSizeFor(width: Int, height: Int, maxEdge: Int = MAX_ANALYSIS_EDGE_PX): Int {
            var sample = 1
            var longEdge = maxOf(width, height)
            while (longEdge / sample > maxEdge && sample < 32) sample *= 2
            return sample
        }
    }
}

/**
 * The engine's init latch as pure state: the one place that decides which
 * unavailability is a *process-lifetime* failure. A real init failure (a
 * present but broken model file, the task library rejecting the bytes)
 * latches — retrying per frame would burn CPU on a permanently broken load.
 * A *missing* model file is not a failure — the download can land at any
 * moment, so the caller must not report it here and the gate stays open; the
 * next classify retries. JVM-tested because the missing-vs-broken distinction
 * is exactly what keeps the gate from silently disabling itself before a late
 * download.
 */
internal class DetectorInitGate {
    private var latched = false

    /** Whether an init attempt may be made at all. */
    val canAttempt: Boolean
        get() = !latched

    /** A present-but-broken model or a task-library init error: latch for the process lifetime. */
    fun onInitFailure() {
        latched = true
    }
}
