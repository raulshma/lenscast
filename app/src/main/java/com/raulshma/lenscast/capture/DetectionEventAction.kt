package com.raulshma.lenscast.capture

/**
 * The dispatched-action vocabulary the detection event log persists and the
 * web feed renders: one entry per sink or concern a detection event can
 * claim while the coordinator dispatches it. Replaces the coordinator's old
 * `ACTION_*` string constants — the enum is the same vocabulary with the
 * string spelling carried on [wireName], because the persisted event-log
 * JSON (`DetectionEvent.dispatchedActions`) and the SSE/web feed consumers
 * match on these exact strings. **The wire names must never change
 * spelling**: they are the on-disk format and the web contract
 * (`web/src/types.ts`, `EventFeed.tsx`'s action labels) at once.
 *
 * The coordinator appends an entry only when the concern actually ran — the
 * sink's own go/no-go verdict, the recording controller's start, the
 * siren/torch flip — so the feed lists what really happened, in dispatch
 * order: `recording`/`photo` first (the synchronous half), then the four
 * alert sinks (`webhook`, `mqtt`, `push`, `notify`), then the deterrence
 * pair (`siren`, `torch`) before the log write.
 */
enum class DetectionEventAction(val wireName: String) {
    /** The event-triggered bounded recording was started. */
    RECORDING("recording"),

    /** The legacy auto-photo fired into the gallery (recording-trigger mode off). */
    PHOTO("photo"),

    /** The webhook notifier queued a dispatch (its own go/no-go said go). */
    WEBHOOK("webhook"),

    /** The MQTT publisher queued a publish (its own go/no-go said go). */
    MQTT("mqtt"),

    /** Web Push/UnifiedPush dispatch went out to at least one subscribed target. */
    PUSH("push"),

    /** The local heads-up notification was accepted by the platform. */
    NOTIFY("notify"),

    /** The auto-siren deterrence started. */
    SIREN("siren"),

    /** The auto-torch deterrence flip landed. */
    TORCH("torch"),
}
