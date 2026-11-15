package com.raulshma.lenscast.companion.ui.tabs.events

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.raulshma.lenscast.R

/**
 * A detection event's age, bucketed for the feed's relative timestamp — the
 * pure half of the rendering: [eventAge] computes the bucket from two epoch
 * millisecond values (no clock, no locale, no Android), so it unit-tests
 * trivially, and the [label] composable maps the bucket to a string resource
 * (stringResource everywhere; the raw "3 min" strings never appear in code).
 *
 * Buckets follow the web console's relative-time shape: under a minute reads
 * "just now", then whole minutes / hours / days — the coarser the bucket,
 * the less the device-vs-phone clock skew matters (a feed card never shows a
 * suspicious future time).
 */
sealed interface EventAge {

    /** Under a minute old (or the device clock runs ahead — clamped here). */
    data object JustNow : EventAge

    /** 1..59 whole minutes old. */
    data class Minutes(val count: Long) : EventAge

    /** 1..23 whole hours old. */
    data class Hours(val count: Long) : EventAge

    /** A day or more old. */
    data class Days(val count: Long) : EventAge
}

/** Milliseconds in one minute. */
private const val MINUTE_MS = 60_000L

/** Milliseconds in one hour. */
private const val HOUR_MS = 3_600_000L

/** Milliseconds in one day. */
private const val DAY_MS = 86_400_000L

/**
 * The bucket for an event stamped [eventMs], as of [nowMs]. A negative delta
 * (the event's timestamp sits in this phone's future — a device clock that
 * runs ahead) clamps to [EventAge.JustNow] rather than a negative count.
 */
fun eventAge(eventMs: Long, nowMs: Long): EventAge {
    val deltaMs = (nowMs - eventMs).coerceAtLeast(0L)
    return when {
        deltaMs < MINUTE_MS -> EventAge.JustNow
        deltaMs < HOUR_MS -> EventAge.Minutes(deltaMs / MINUTE_MS)
        deltaMs < DAY_MS -> EventAge.Hours(deltaMs / HOUR_MS)
        else -> EventAge.Days(deltaMs / DAY_MS)
    }
}

/** The bucket's user-facing copy, resolved through string resources. */
@Composable
fun EventAge.label(): String = when (this) {
    EventAge.JustNow -> stringResource(R.string.companion_events_time_now)
    is EventAge.Minutes -> stringResource(R.string.companion_events_time_minutes, count)
    is EventAge.Hours -> stringResource(R.string.companion_events_time_hours, count)
    is EventAge.Days -> stringResource(R.string.companion_events_time_days, count)
}
