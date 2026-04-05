package com.raulshma.lenscast.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val galleryDayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
    "EEE, MMM d",
    Locale.getDefault(),
)

private val galleryTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
    "h:mm a",
    Locale.getDefault(),
)

private val viewerDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(
    FormatStyle.MEDIUM,
    FormatStyle.SHORT,
)

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

fun buildGalleryOverview(items: List<CaptureHistory>): GalleryOverview {
    return GalleryOverview(
        totalCount = items.size,
        photoCount = items.count { it.type == CaptureType.PHOTO },
        videoCount = items.count { it.type == CaptureType.VIDEO },
        totalBytes = items.sumOf { it.fileSizeBytes.coerceAtLeast(0L) },
        dayCount = items.mapTo(linkedSetOf()) { it.timestamp.toLocalDate() }.size,
    )
}

fun buildGallerySections(items: List<CaptureHistory>): List<GallerySection> {
    return items
        .groupBy { it.timestamp.toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, entries) ->
            GallerySection(
                key = day.toString(),
                title = formatGallerySectionTitle(day),
                subtitle = galleryDayFormatter.format(day),
                items = entries.sortedByDescending { it.timestamp },
                totalBytes = entries.sumOf { it.fileSizeBytes.coerceAtLeast(0L) },
            )
        }
}

fun resolveMediaModel(filePath: String): Any? {
    if (filePath.startsWith("content://")) {
        return Uri.parse(filePath)
    }
    if (filePath.startsWith("file://")) {
        return Uri.parse(filePath)
    }
    val file = File(filePath)
    if (file.exists()) {
        return file
    }
    return null
}

fun shareGalleryMedia(context: Context, items: List<CaptureHistory>) {
    if (items.isEmpty()) return

    val uris = items.mapNotNull { resolveShareableUri(context, it) }
    if (uris.isEmpty()) return

    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeForCapture(items.first().type)
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
        setDataAndType(uri, mimeTypeForCapture(item.type))
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
    return galleryTimeFormatter.format(timestamp.toZonedDateTime())
}

fun formatViewerDateTime(timestamp: Long): String {
    return viewerDateTimeFormatter.format(timestamp.toZonedDateTime())
}

private fun formatGallerySectionTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> galleryDayFormatter.format(day)
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

private fun mimeTypeForCapture(type: CaptureType): String {
    return when (type) {
        CaptureType.PHOTO -> "image/jpeg"
        CaptureType.VIDEO -> "video/mp4"
    }
}

private fun Long.toLocalDate(): LocalDate = toZonedDateTime().toLocalDate()

private fun Long.toZonedDateTime() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
