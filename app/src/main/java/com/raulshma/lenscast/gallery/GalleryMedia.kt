package com.raulshma.lenscast.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.raulshma.lenscast.capture.CaptureMediaResolver
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.capture.model.CaptureType
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// SimpleDateFormat is mutable, so one instance per thread replaces the shared
// val a DateTimeFormatter allowed; gallery rendering is main-thread only.
private class CachedFormat(private val newFormat: () -> DateFormat) : ThreadLocal<DateFormat>() {
    override fun initialValue(): DateFormat = newFormat()
}

private val galleryDayFormat = CachedFormat {
    SimpleDateFormat("EEE, MMM d", Locale.getDefault())
}

private val galleryTimeFormat = CachedFormat {
    SimpleDateFormat("h:mm a", Locale.getDefault())
}

private val viewerDateTimeFormat = CachedFormat {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
}

data class GalleryOverview(
    val totalCount: Int,
    val photoCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
    val dayCount: Int,
)

data class GallerySection(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<CaptureHistory>,
    val totalBytes: Long,
)

fun buildGalleryOverview(
    items: List<CaptureHistory>,
    timeZone: TimeZone = TimeZone.getDefault(),
): GalleryOverview {
    return GalleryOverview(
        totalCount = items.size,
        photoCount = items.count { it.type == CaptureType.PHOTO },
        videoCount = items.count { it.type == CaptureType.VIDEO },
        totalBytes = items.sumOf { it.fileSizeBytes.coerceAtLeast(0L) },
        dayCount = items.mapTo(linkedSetOf()) { GalleryDates.epochDayOf(it.timestamp, timeZone) }.size,
    )
}

fun buildGallerySections(
    items: List<CaptureHistory>,
    todayEpochDay: Long = GalleryDates.todayEpochDay(),
    timeZone: TimeZone = TimeZone.getDefault(),
): List<GallerySection> {
    return items
        .groupBy { GalleryDates.epochDayOf(it.timestamp, timeZone) }
        .toSortedMap(compareByDescending { it })
        .map { (day, entries) ->
            GallerySection(
                key = GalleryDates.isoDate(day),
                title = formatGallerySectionTitle(day, todayEpochDay, timeZone),
                subtitle = galleryDayFormat.get().format(Date(GalleryDates.dayStartMillis(day, timeZone))),
                items = entries.sortedByDescending { it.timestamp },
                totalBytes = entries.sumOf { it.fileSizeBytes.coerceAtLeast(0L) },
            )
        }
}

/**
 * The display model for a recorded path — [CaptureMediaResolver]'s scheme
 * ladder in one call: a [Uri] for scheme'd paths, a [File] for existing
 * plain paths, null otherwise.
 */
fun resolveMediaModel(filePath: String): Any? = CaptureMediaResolver().displayModel(filePath)

fun shareGalleryMedia(context: Context, items: List<CaptureHistory>) {
    if (items.isEmpty()) return

    val uris = items.mapNotNull { resolveShareableUri(context, it) }
    if (uris.isEmpty()) return

    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = CaptureMediaFormat.mimeFor(items.first().type)
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        val hasPhoto = items.any { it.type == CaptureType.PHOTO }
        val hasVideo = items.any { it.type == CaptureType.VIDEO }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = when {
                hasPhoto && hasVideo -> "*/*"
                hasVideo -> "video/*"
                else -> "image/*"
            }
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }

    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(
        Intent.createChooser(
            intent,
            "Share ${items.size} item${if (items.size == 1) "" else "s"}",
        )
    )
}

fun openMediaExternal(context: Context, item: CaptureHistory) {
    val uri = resolveShareableUri(context, item) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, CaptureMediaFormat.mimeFor(item.type))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Open ${item.fileName}"))
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "Unknown size"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val decimals = if (value >= 10 || unitIndex == 0) 0 else 1
    return "%,.${decimals}f %s".format(Locale.getDefault(), value, units[unitIndex])
}

fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0:00"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

fun formatGalleryTime(timestamp: Long): String {
    return galleryTimeFormat.get().format(Date(timestamp))
}

fun formatViewerDateTime(timestamp: Long): String {
    return viewerDateTimeFormat.get().format(Date(timestamp))
}

/**
 * The section header for a capture day: Today / Yesterday relative to the
 * injected [todayEpochDay] (callers default to now, keeping call sites
 * clean; tests inject a fixed day), otherwise the formatted date.
 */
fun formatGallerySectionTitle(
    dayEpochDay: Long,
    todayEpochDay: Long = GalleryDates.todayEpochDay(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    return when (dayEpochDay) {
        todayEpochDay -> "Today"
        todayEpochDay - 1 -> "Yesterday"
        else -> galleryDayFormat.get().format(Date(GalleryDates.dayStartMillis(dayEpochDay, timeZone)))
    }
}

private fun resolveShareableUri(context: Context, item: CaptureHistory): Uri? {
    val resolved = resolveMediaModel(item.filePath)
    return when (resolved) {
        is Uri -> resolved
        is File -> runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                resolved,
            )
        }.getOrElse {
            Uri.fromFile(resolved)
        }
        else -> null
    }
}
