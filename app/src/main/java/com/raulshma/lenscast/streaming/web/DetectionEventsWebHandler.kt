package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.capture.DetectionEvent
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.capture.DetectionStatsPolicy
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.streaming.model.DailyCountDto
import com.raulshma.lenscast.streaming.model.DetectionEventDto
import com.raulshma.lenscast.streaming.model.DetectionEventsResponseDto
import com.raulshma.lenscast.streaming.model.DetectionStatsResponseDto
import com.raulshma.lenscast.streaming.model.LabeledCountDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * /api/detection/events — the detection event feed: GET lists the persisted
 * events newest first (bounded by the `limit` query param, narrowed by the
 * `type` param), DELETE clears the log, GET …/export downloads the log as
 * JSON or CSV, and GET /api/detection/stats aggregates it. Reads and writes
 * go through the shared [DetectionEventStore]; the store's own policy owns
 * the cap and the limit clamp. The per-event JSON serializer ([eventJson]) is
 * the one the SSE stream reuses, so both the polling GET and the live stream
 * carry the exact same event object shape.
 *
 * The export deliberately omits the base64 snapshots — the JSON body would be
 * megabytes and the CSV column meaningless; the fields survive in the
 * regular feed.
 */
class DetectionEventsWebHandler(
    private val eventStore: DetectionEventStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(DetectionEventsResponseDto::class.java) }
    private val eventAdapter by lazy { AppJson.moshi.adapter(DetectionEventDto::class.java) }
    private val exportAdapter by lazy {
        AppJson.moshi.adapter<List<DetectionEventDto>>(
            com.squareup.moshi.Types.newParameterizedType(List::class.java, DetectionEventDto::class.java),
        )
    }
    private val statsAdapter by lazy { AppJson.moshi.adapter(DetectionStatsResponseDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    fun list(limit: Int?, type: String? = null): String {
        val kind = typeFilterOrNull(type)
        if (kind == null && !type.isNullOrBlank()) return unknownTypeError(type)
        val events = eventStore.events(limit, kind?.wireName)
        return responseAdapter.toJson(
            DetectionEventsResponseDto(
                events = events.map(::toDto),
                total = eventStore.count(kind?.wireName),
            ),
        )
    }

    fun clear(): String {
        eventStore.clear()
        return successAdapter.toJson(SuccessResponse())
    }

    /**
     * GET /api/detection/events/export — the whole log (type-filtered) as
     * `format=csv` (default) or `format=json`, snapshots omitted in both
     * shapes (the JSON body would be megabytes and the CSV column
     * meaningless; the fields survive in the regular feed). The JSON shape is
     * a bare array of the feed's event objects minus `snapshotJpegBase64`;
     * the CSV is RFC-4180-quoted with an ISO-8601 UTC timestamp column and
     * rides a text/csv content type so a browser download lands as a real
     * CSV.
     */
    fun export(format: String?, type: String? = null): ApiResponse {
        val kind = typeFilterOrNull(type)
        if (kind == null && !type.isNullOrBlank()) return ApiResponse.ok(unknownTypeError(type))
        val events = eventStore.events(limit = null, type = kind?.wireName).map(::toDto)
        return if (format.equals("json", ignoreCase = true)) {
            ApiResponse.ok(exportAdapter.toJson(events.map { it.copy(snapshotJpegBase64 = null) }))
        } else {
            ApiResponse(200, "text/csv", toCsv(events))
        }
    }

    /** GET /api/detection/stats — the log's aggregate counts. */
    fun stats(): String {
        val stats = DetectionStatsPolicy.build(eventStore.events(), nowMs())
        return statsAdapter.toJson(
            DetectionStatsResponseDto(
                last24h = stats.last24h,
                last7d = stats.last7d,
                allTime = stats.allTime,
                perDay = stats.perDay.map { (day, count) -> DailyCountDto(day, count) },
                totalEvents = stats.totalEvents,
                topZones = stats.topZones.map { (label, count) -> LabeledCountDto(label, count) },
                topLabels = stats.topLabels.map { (label, count) -> LabeledCountDto(label, count) },
            ),
        )
    }

    /** The one DetectionEvent → wire-JSON mapping, shared by the poll and the SSE stream. */
    fun eventJson(event: DetectionEvent): String = eventAdapter.toJson(toDto(event))

    /**
     * The `?type=` query filter, validated: null or blank means unfiltered,
     * a known wire name decodes to its kind — the store's KDoc contract
     * ("callers validate before they filter") is kept here, so an unknown
     * name can never read as an empty feed.
     */
    private fun typeFilterOrNull(type: String?): EventKind? =
        EventKind.fromWireNameOrNull(type)

    /** The unknown-`type` answer: the handler-error payload, per the 200-shape contract. */
    private fun unknownTypeError(type: String): String =
        ApiResponse.error(IllegalArgumentException("Unknown event type '$type' (expected motion, sound, or tamper)"))

    /** The SSE connect-time backlog: the latest [limit] events, chronological (oldest first). */
    fun replayBacklog(limit: Int): List<DetectionEvent> = eventStore.events(limit).reversed()

    /** The store's live event stream (record + clip-link updates), the SSE tail. */
    fun eventFlow(): SharedFlow<DetectionEvent> = eventStore.eventsFlow

    private fun toCsv(events: List<DetectionEventDto>): String {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        timestampFormat.timeZone = TimeZone.getTimeZone("UTC")
        val header = "id,type,source,timestamp,timestampMs,zones,labels,dispatchedActions,clipFileName"
        val rows = events.joinToString("\r\n") { event ->
            listOf(
                event.id,
                event.type,
                event.source,
                timestampFormat.format(Date(event.timestampMs)),
                event.timestampMs.toString(),
                event.zones.joinToString("; "),
                event.labels.joinToString("; "),
                event.dispatchedActions.joinToString("; "),
                event.clipFileName ?: "",
            ).joinToString(",") { DetectionEventCsv.escape(it) }
        }
        return if (rows.isEmpty()) header else "$header\r\n$rows"
    }

    private fun toDto(event: DetectionEvent) = DetectionEventDto(
        id = event.id,
        type = event.type,
        source = event.source,
        timestampMs = event.timestampMs,
        snapshotJpegBase64 = event.snapshotJpegBase64,
        dispatchedActions = event.dispatchedActions,
        zones = event.zones,
        labels = event.labels,
        clipMediaId = event.clipMediaId,
        clipFileName = event.clipFileName,
    )
}

/** RFC 4180 field escaping for the CSV export, pure for tests. */
internal object DetectionEventCsv {

    /** Characters a spreadsheet app treats as a formula prefix in a cell. */
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t')

    /**
     * Quote a field when it carries a comma, quote, newline, or CR, and
     * prefix a guard apostrophe when it *begins* with a formula trigger
     * (`=`, `+`, `-`, `@`, TAB) — otherwise a user-authored zone label like
     * `=SUM(A1)` or `@cmd` would execute as a formula when the operator opens
     * the export in Excel/LibreOffice. A leading `-` is common in harmless
     * labels, so the guard is a quote-free `'` that consumers never see as
     * data.
     */
    fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val guarded = if (field.isNotEmpty() && field[0] in FORMULA_PREFIXES) "'$field" else field
        return when {
            needsQuote -> "\"${guarded.replace("\"", "\"\"")}\""
            else -> guarded
        }
    }
}
