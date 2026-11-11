package com.raulshma.lenscast.camera.model

import androidx.camera.view.PreviewView

/**
 * The one session-demand record: WHAT the camera session is being asked to do,
 * as a single immutable value. Before this type the five demand facts lived in
 * five loose [com.raulshma.lenscast.camera.CameraService] fields written one
 * statement at a time — a multi-fact writer (startPreview, stopPreview,
 * release) could be observed half-applied by the auto-recovery thread's
 * cross-thread arbiter consults, and every reader re-assembled the heap by
 * hand. One copy-on-write record makes each writer atomic and every read a
 * coherent snapshot.
 *
 * **The five demand states — overlaps of the facts below, not an enum.** A
 * session sits in several at once (a recording is Exclusive-held AND Headless
 * keep-alive AND backgrounded); [com.raulshma.lenscast.camera.model.CameraSessionArbiter]
 * reads the raw facts, and these are just the named combinations its verdicts
 * cover:
 *
 * - **Unbound** — no demand: `previewRequested=false`, `keepAliveRefCount=0`,
 *   `exclusiveSessionRefCount=0`. The shared binding ladder
 *   ([CameraSessionArbiter.Trigger.RebindCheck]) unbinds.
 * - **Preview-bound foreground** — `previewRequested=true`,
 *   `currentPreviewView != null`, `isActivityForeground=true`, nothing
 *   exclusive. The normal camera screen; the bind-time attach gate
 *   (`shouldAttachPreview`) is open.
 * - **Preview-bound surface-detached** — the activity went background
 *   (`isActivityForeground=false` from `onActivityStop`) while preview demand
 *   and the view reference survive: `onActivityStop` detaches the surface on
 *   the BINDING side (`preview.surfaceProvider = null`) and keeps the view
 *   record intact, so `previewSurfaceAttached` stays true — matching the
 *   arbiter's contract that the detach never clears the view reference (only
 *   `stopPreview` does). The record's contribution to this state is the
 *   foreground flip plus the retained view; the physical detach is not
 *   representable here and lives on the bound use case.
 * - **Headless keep-alive** — `previewRequested=false`,
 *   `keepAliveRefCount>0`: the camera stays bound through the keep-alive
 *   lifecycle with no preview surface (background streaming, an in-flight
 *   photo capture).
 * - **Exclusive-held** — `exclusiveSessionRefCount>0`: a recording owns the
 *   camera outright; every rebind/stopPreview ladder becomes the arbiter's
 *   Noop until it releases.
 *
 * **Who writes each fact** (all through the service's single
 * `updateDemand` seam, all on the Main thread — RecordingService's service
 * callbacks, StreamingSession's and PhotoCaptureManager's explicit
 * `Dispatchers.Main` hops, MainActivity's lifecycle observer, and the
 * Compose-driven preview start/stop):
 *
 * - `exclusiveSessionRefCount` — [com.raulshma.lenscast.capture.RecordingService]
 *   alone (`beginExclusiveSession`/`endExclusiveSession` around a recording).
 *   PhotoCaptureManager does NOT hold the exclusive ref — it expresses its
 *   demand through keep-alive refs (`acquirePhotoCapture`/`releasePhotoCapture`).
 * - `keepAliveRefCount` — [com.raulshma.lenscast.streaming.StreamingSession]
 *   (stream start/stop and its unwind paths),
 *   [com.raulshma.lenscast.capture.PhotoCaptureManager] (via the service's
 *   `acquirePhotoCapture`/`releasePhotoCapture`), and
 *   [com.raulshma.lenscast.capture.RecordingService] (around a recording).
 * - `isActivityForeground` — [com.raulshma.lenscast.MainActivity]'s lifecycle
 *   observer (`onActivityResume`/`onActivityStop`). Default `true`: before the
 *   first lifecycle event the service assumes foreground, so a preview start
 *   racing the first callback still binds with the attach gate open.
 * - `previewRequested` — the UI ([com.raulshma.lenscast.camera.CameraViewModel]
 *   on the camera screen's behalf), via the service's `startPreview`/
 *   `stopPreview`.
 * - `currentPreviewView` — the same `startPreview`/`stopPreview`/`release`
 *   writers, always together with `previewRequested`. Grouped here on
 *   purpose: its presence IS a demand fact (the arbiter's
 *   `previewSurfaceAttached` input is derived from `!= null`), every writer
 *   writes it alongside `previewRequested`, and keeping it out would force
 *   either a mirrored boolean or a stray field read inside the service's
 *   `demandState()` mapping. The reference itself is demand-adjacent — it is
 *   handed to the bind path (`surfaceProvider` wiring) at execution, exactly
 *   as the old field was; the bound use cases remain plain service fields.
 *
 * **Threading**: written only on Main via the service's `updateDemand`
 * (copy-on-write, one record per write); read without synchronization by the
 * frame-analysis thread's auto-recovery consults — the same benign-staleness
 * guarantees the loose fields had, with the window narrowed to whole-record
 * swaps instead of statement-at-a-time field writes.
 */
data class SessionDemand(
    /** The UI's ask for a visible preview. Owner: the camera screen's start/stop. */
    val previewRequested: Boolean = false,
    /** Recording's exclusive hold on the camera. Owner: RecordingService only. */
    val exclusiveSessionRefCount: Int = 0,
    /**
     * Headless demand (streaming, photo capture, recording). Owner:
     * StreamingSession, PhotoCaptureManager (via acquirePhotoCapture), and
     * RecordingService. NOTE: `release()` does not reset this — a preserved,
     * documented gap (see CameraService.release).
     */
    val keepAliveRefCount: Int = 0,
    /** Whether MainActivity is foreground. Default `true` before the first lifecycle event. */
    val isActivityForeground: Boolean = true,
    /**
     * The view surface the preview binds to, null while none exists. Presence
     * is the demand fact; the reference rides along for the bind path.
     */
    val currentPreviewView: PreviewView? = null,
)
