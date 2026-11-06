package com.raulshma.lenscast.capture

import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.core.push.WebPushSender
import com.raulshma.lenscast.capture.model.DetectionEventDeepLink
import com.raulshma.lenscast.data.SettingsDataStore

/**
 * One dispatch destination for a detection event's alert fan-out — the seam
 * that turned the coordinator's inline four-sink sequence (webhook, MQTT,
 * Web Push, local notification) into a list. Every sink receives the same
 * one alert shape and answers its own go/no-go: `true` means the sink
 * claimed its dispatch (the coordinator records [action] on the event log),
 * `false` means it no-opped (disabled, unsubscribed, quiet-held, platform
 * refused) and nothing is claimed. Sinks are dispatched in list order and a
 * sink never sees an event another sink's verdict gates — the only failure
 * semantics are the sinks' own contracts (each is fail-open by design and
 * never throws).
 *
 * [eventId] is the event-moment id every sink reports (the Web Push payload
 * carries it; the other sinks ignore it) and [clipAvailable] says whether
 * the dispatch already claimed the bounded recording (the push payload's
 * clip hint; the other sinks ignore it) — the two arguments the push call
 * site needs and the rest don't. The trigger-time snapshot rides
 * [DetectionAlert.snapshotJpegBase64]: every sink that wants it reads it
 * there, so one encode feeds the whole fan-out.
 *
 * The adapters below wrap exactly the coordinator's existing dependencies;
 * the coordinator builds them as its constructor default, so the
 * composition root's wiring is untouched.
 */
interface DetectionSink {

    /** The event-log action this sink claims when [dispatch] returns true. */
    val action: DetectionEventAction

    /**
     * Dispatch one alert. Suspending for future sinks that wait on IO; the
     * current adapters queue onto their transport's own worker and return
     * the would-dispatch verdict immediately. Implementations must not
     * throw — a throw here aborts the coordinator's remaining fan-out.
     */
    suspend fun dispatch(alert: DetectionAlert, eventId: String, clipAvailable: Boolean): Boolean
}

/**
 * The webhook sink: [WebhookNotifier]'s JSON POST with the settings' custom
 * headers and the events-feed deep link. Claims [DetectionEventAction.WEBHOOK]
 * exactly when the notifier would really dispatch (enabled + an HTTP URL) —
 * the notifier's own verdict, read at dispatch time, not a pre-encode one.
 */
class WebhookDetectionSink(
    private val notifier: WebhookNotifier,
    /** The persisted headers setting (a JSON `{"Name": "value"}` map), read live per dispatch. */
    private val headersJson: () -> String?,
) : DetectionSink {

    override val action: DetectionEventAction = DetectionEventAction.WEBHOOK

    override suspend fun dispatch(
        alert: DetectionAlert,
        eventId: String,
        clipAvailable: Boolean,
    ): Boolean = notifier.notifyEvent(
        alert,
        headers = WebhookNotifier.parseHeaders(headersJson()),
        // The deep link is the events feed: at dispatch moment the bounded
        // recording's clip is not linked yet (it links when the recording
        // finalizes) — the persisted event's feed url upgrades to the
        // gallery link, while the dispatch-moment sinks carry "#/events".
        deepLink = DetectionEventDeepLink.EVENTS,
    )
}

/**
 * The MQTT sink: [MqttAlertPublisher]'s event publish with the same deep
 * link the webhook body carries, so an automation consuming either channel
 * sees the identical payload. Claims [DetectionEventAction.MQTT] on the
 * publisher's own would-publish verdict (enabled + a non-blank host); a
 * missing publisher is a quiet no-op, like the coordinator's old
 * `mqttPublisher()?.notifyEvent(...) == true` read.
 */
class MqttDetectionSink(
    private val publisher: () -> MqttAlertPublisher?,
) : DetectionSink {

    override val action: DetectionEventAction = DetectionEventAction.MQTT

    override suspend fun dispatch(
        alert: DetectionAlert,
        eventId: String,
        clipAvailable: Boolean,
    ): Boolean = publisher()?.notifyEvent(alert, deepLink = DetectionEventDeepLink.EVENTS) == true
}

/**
 * The Web Push sink: one RFC 8291-encrypted JSON per subscribed browser and
 * UnifiedPush receiver. Claims [DetectionEventAction.PUSH] when a transport
 * gate is on and at least one target on that transport received a dispatch —
 * the sender's own verdict. The only sink that consumes [eventId] (it rides
 * the payload) and [clipAvailable] (the payload's clip hint, true when the
 * event already claimed its bounded recording).
 */
class WebPushDetectionSink(
    private val sender: () -> WebPushSender?,
) : DetectionSink {

    override val action: DetectionEventAction = DetectionEventAction.PUSH

    override suspend fun dispatch(
        alert: DetectionAlert,
        eventId: String,
        clipAvailable: Boolean,
    ): Boolean = sender()?.notifyEvent(
        alert,
        eventId = eventId,
        clipAvailable = clipAvailable,
        deepLink = DetectionEventDeepLink.EVENTS,
    ) == true
}

/**
 * The local-notification sink: the heads-up [DetectionNotifier] post, behind
 * its two sink-private gates — the notifications toggle and the quiet-hours
 * window (quiet hours silence the phone, not the integrations: webhook, MQTT,
 * and push above already dispatched, and the event still logs). Claims
 * [DetectionEventAction.NOTIFY] only when the platform accepted the post
 * (the runtime permission gates it on API 33+); a missing notifier is a
 * quiet no-op.
 */
class NotificationDetectionSink(
    private val notifier: () -> DetectionNotifier?,
    private val store: SettingsDataStore,
    /** The minute-of-day clock the quiet-hours window is judged against. */
    private val minuteOfDay: () -> Int,
) : DetectionSink {

    override val action: DetectionEventAction = DetectionEventAction.NOTIFY

    override suspend fun dispatch(
        alert: DetectionAlert,
        eventId: String,
        clipAvailable: Boolean,
    ): Boolean =
        store.detectionNotificationsEnabled.value &&
            !QuietHoursPolicy.isQuiet(
                enabled = store.alertQuietHoursEnabled.value,
                startMinute = store.alertQuietHoursStartMinute.value,
                endMinute = store.alertQuietHoursEndMinute.value,
                minuteOfDay = minuteOfDay(),
            ) &&
            notifier()?.notify(alert.kind, alert.zones, alert.snapshotJpegBase64) == true
}
