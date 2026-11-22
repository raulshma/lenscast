package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.camera.LensInventory
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.LensLabelKind
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.camera.model.StreamStatusSnapshot
import com.raulshma.lenscast.companion.client.dto.ControlRange
import com.raulshma.lenscast.companion.client.dto.LensInfo
import com.raulshma.lenscast.companion.client.dto.StreamingStatus
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.core.ThermalState
import kotlin.math.roundToInt

/**
 * The remote console backend's pure mapping half: wire DTOs (GET /api/status,
 * GET /api/camera/lenses) in, the camera dashboard's app models out. The
 * dashboard binds to [com.raulshma.lenscast.camera.CameraConsole] and renders
 * [CameraState]/[StreamStatus]/lens lists identically over both backends —
 * that only holds if the remote side derives those models through the SAME
 * derivations the local side uses (the shared [StreamStatusSnapshot] builder,
 * the [CameraSettings] companion's range constants), not through a forked
 * interpretation that drifts. This object is that single derivation home for
 * the remote backend; the later console ViewModel composes these calls into
 * its flow publications and owns none of the mapping decisions itself.
 *
 * JVM-pure by design — every decision here is unit-testable without a device
 * or Robolectric. The one Android-typed corner is the lens model's embedded
 * camera selector: `camera.model.CameraLensInfo` carries an
 * `androidx.camera.core.CameraSelector` the wire has no counterpart for, so
 * [lens] returns the [MappedLens] projection and the ViewModel package
 * assembles the final model (supplying a placeholder selector — a remote
 * backend never binds a local camera, so the field is dead weight it must
 * carry only to satisfy the dashboard's type).
 */
object DeviceStatusMappers {

    /**
     * The session lifecycle → the dashboard's camera state. The remote
     * backend publishes this on every [ConnectionState] transition so the
     * dashboard's init/error surfaces mean the same thing over both backends:
     * [CameraState.Initializing] is "the link is being established",
     * [CameraState.Ready] is "render the live viewfinder dashboard",
     * [CameraState.Error] is "the dashboard cannot render truth right now".
     *
     * The deliberate choice is RECONNECTING → **Ready**: the session keeps
     * the UI warm through the reconnect ladder's backoff — its warm poll
     * refreshes the status snapshot on every rung (1 s → 30 s), so the
     * dashboard is rendering a seconds-old but alive picture, not a dead
     * one. Dropping to an error state on every transient backoff window
     * would thrash the whole screen for a link that usually heals within a
     * rung or two; [CameraState.Error] is reserved for the ladder's terminal
     * verdicts ([ConnectionState.OFFLINE] after the give-up tiers,
     * [ConnectionState.AUTH_FAILED] on the revoked credential). The console
     * package can still surface the softer RECONNECTING nuance through its
     * own badge; the dashboard's camera state stays warm.
     */
    fun cameraState(connection: ConnectionState): CameraState = when (connection) {
        ConnectionState.IDLE -> CameraState.Idle
        ConnectionState.CONNECTING -> CameraState.Initializing
        ConnectionState.CONNECTED -> CameraState.Ready
        ConnectionState.RECONNECTING -> CameraState.Ready
        ConnectionState.OFFLINE -> CameraState.Error(DEVICE_UNREACHABLE_MESSAGE)
        ConnectionState.AUTH_FAILED -> CameraState.Error(AUTH_FAILED_MESSAGE)
    }

    /**
     * The status poll's streaming block → the dashboard's stream status,
     * through the SAME [StreamStatusSnapshot.build] derivation the local
     * [com.raulshma.lenscast.camera.CameraViewModel] feeds — one field
     * mapping serves both backends, so the stream panel renders identically
     * and neither side forks the semantics.
     *
     * Documented divergences (the wire shape differs from the local manager
     * flows; the app model is unchanged, exactly like the local side):
     *
     * - [StreamStatus.isServerRunning] is a constant `true`: the device just
     *   answered the status request, so its HTTP server is definitionally
     *   up. The dashboard's server panel never renders remotely anyway (the
     *   console's capabilities hold `serverPanel` off), so the flag's only
     *   render effect is moot; a constant keeps this derivation total.
     * - [StreamingStatus.audioEnabled] stands in for the local "audio is
     *   actively streaming" flag — the wire carries enablement, not
     *   momentary activity.
     * - The nullable additive push-output fields (rtmp/whip/srt, their
     *   statuses/errors, `srtRttMs`) are DROPPED, exactly as the local side
     *   drops them: [StreamStatus] predates those outputs and carries no
     *   fields for them. The companion's own stream surfaces read them
     *   straight off the DTO; the dashboard model stays untouched.
     * - `whepClients` is not folded into [StreamStatus.clientCount]: the
     *   local model counts only the M-JPEG web viewers, and summing a second
     *   viewer lane would silently change the field's meaning for UI that
     *   both backends share.
     */
    fun streamStatus(status: StreamingStatus): StreamStatus =
        StreamStatusSnapshot.build(
            video = StreamStatusSnapshot.VideoInputs(
                isStreaming = status.isActive,
                isWebActive = status.webStreamingActive,
                isServerRunning = true,
                url = status.url,
                clientCount = status.clientCount,
            ),
            audio = StreamStatusSnapshot.AudioInputs(
                isAudioActive = status.audioEnabled,
                audioUrl = status.audioUrl,
                isRtspActive = status.rtspStreamingActive,
                rtspUrl = status.rtspUrl,
            ),
            isWebEnabled = status.webStreamingEnabled,
            isRtspEnabled = status.rtspEnabled,
        )

    /**
     * The device's live ISO range → the quick-settings sheet's span. A null
     * range (pre-lens-ranges firmware) or an inverted one (a device bug —
     * publishing it would break every slider) falls back to the
     * [CameraSettings] companion's assumed sensor span
     * (`ISO_RANGE_MIN`..`ISO_RANGE_MAX`): the same "published before the
     * device's live ranges arrive" bounds the local side documents, so both
     * backends degrade identically and the device's live truth wins the
     * moment it lands.
     */
    fun isoRange(range: ControlRange?): ClosedRange<Int> =
        saneIntRange(range) ?: (CameraSettings.ISO_RANGE_MIN..CameraSettings.ISO_RANGE_MAX)

    /**
     * The device's live exposure-compensation range → the quick-settings
     * span, with the same null/inverted fallback policy as [isoRange] but
     * onto the exposure persistence bounds
     * (`EXPOSURE_COMPENSATION_MIN`..`EXPOSURE_COMPENSATION_MAX`) — the
     * constants the store, the web API DTO mapping, and the UI already share
     * for "what exposure span do we believe in without device truth".
     */
    fun exposureCompensationRange(range: ControlRange?): ClosedRange<Int> =
        saneIntRange(range)
            ?: (CameraSettings.EXPOSURE_COMPENSATION_MIN..CameraSettings.EXPOSURE_COMPENSATION_MAX)

    /**
     * The device's live zoom range → the pinch/chip span, with the same
     * null/inverted fallback policy as [isoRange] onto
     * [CameraSettings.effectiveZoomRange] at the persistence ceiling
     * [CameraSettings.ZOOM_RATIO_MAX] (`1f`..`10f`): that builder is the one
     * home of the "zoom span the UI may drive" derivation (floored at 1x,
     * capped at the lower of device and persistence ceilings), so the
     * fallback reuses it instead of restating the floor/cap rules here.
     * A present, sane range maps through raw (min..max) — the local backend
     * likewise publishes the service's raw device range, and clamping it
     * here would second-guess the device's own truth.
     */
    fun zoomRange(range: ControlRange?): ClosedFloatingPointRange<Float> =
        if (range != null && range.min <= range.max) {
            range.min.toFloat()..range.max.toFloat()
        } else {
            CameraSettings.effectiveZoomRange(CameraSettings.ZOOM_RATIO_MAX)
        }

    /**
     * The device's platform thermal constant name → the app's thermal state,
     * with a safe [ThermalState.NORMAL] fallback: the wire is a free-form
     * platform string (an unknown future constant, a lower-cased platform
     * name, or a rebranded value must never crash the status decode path),
     * and NORMAL is both the enum's baseline and the value the dashboard's
     * throttling chips treat as "no thermal story to tell".
     */
    fun thermalState(platformName: String?): ThermalState =
        platformName?.let { name -> runCatching { ThermalState.valueOf(name) }.getOrNull() }
            ?: ThermalState.NORMAL

    /**
     * One wire lens → the dashboard's lens model fields, field by field.
     * Returns [MappedLens] rather than building the app model directly
     * because `camera.model.CameraLensInfo` embeds an
     * `androidx.camera.core.CameraSelector`
     * — an Android type with NO wire source (the remote backend never binds
     * a local camera); the ViewModel package assembles the final model from
     * this projection and supplies a placeholder selector.
     *
     * Wire fields with no [MappedLens] target (each deliberate):
     * - `index`: the model has no index field — the server enumerates lenses
     *   in index order, so the LIST POSITION carries it and `selectLens(i)`
     *   round-trips as long as the published list keeps the wire order.
     * - `label`: no target — the app model derives display labels from
     *   [LensLabelKind] via [LensInventory.baseLabel], and the wire label is
     *   the device-side English output of that same derivation.
     * - `selected`: no target — the dashboard carries the selection as the
     *   console's separate `selectedLensIndex` flow (from
     *   `LensesResponse.selectedIndex`), not on the lens entry.
     */
    fun lens(source: LensInfo): MappedLens {
        val facing = if (source.isFront) MappedLens.LENS_FACING_FRONT else MappedLens.LENS_FACING_BACK
        val focal = source.focalLength.toFloat()
        // `kind` is the device-side LensLabelKind.name — but the DTO marks it
        // optional (a stale dashboard against an older device), and an unknown
        // name must never crash the decode path. The fallback RE-DERIVES the
        // band locally through LensInventory's own banding (front-folding
        // included) from the wire's isFront + focalLength — the same algorithm
        // that produced the device-side kind, so the chip lands in the same
        // band the device would have sent.
        val kind = source.kind?.let { runCatching { LensLabelKind.valueOf(it) }.getOrNull() }
            ?: LensInventory.labelKind(facing, focal)
        return MappedLens(
            id = source.id,
            labelKind = kind,
            lensFacing = facing,
            focalLength = focal,
            // No wire source: physical-camera partitioning is a local Camera2
            // concept; a remote lens is already the device's logical unit.
            physicalCameraId = null,
            zoomRatio = source.zoomRatio?.toFloat(),
        )
    }

    /**
     * Overlays the two live facts the settings document cannot carry onto the
     * GET-derived settings snapshot, in the shape the console ViewModel
     * publishes: `torchEnabled` (torch has no settings-wire field at all) and
     * `zoomRatio` (the stored setting lags the live zoom — the status payload
     * is the device's live truth, and remotely both facts are LEARNED here
     * while their WRITES ride the dedicated `/api/camera/torch` and
     * `/api/camera/zoom` endpoints). Minimal by design: every other field of
     * the snapshot comes from [RemoteSettingsMapper.toCameraSettings]; the
     * ViewModel composes the two calls.
     */
    fun withLiveFacts(document: CameraSettings, torchOn: Boolean, zoomRatio: Double): CameraSettings =
        document.copy(torchEnabled = torchOn, zoomRatio = zoomRatio.toFloat())

    /**
     * The [CameraState.Error] message for the ladder's terminal give-up:
     * plain, actionable, and pointed at the two fixes a remote console user
     * actually has (device power/network, then retry).
     */
    private const val DEVICE_UNREACHABLE_MESSAGE =
        "Device unreachable. Check that it is powered on and connected to the network, then retry."

    /**
     * The [CameraState.Error] message for the revoked-credential verdict —
     * deliberately different wording from [DEVICE_UNREACHABLE_MESSAGE] (a
     * retry cannot fix a rejected session; re-pairing can), so the two
     * failure modes never read as the same problem.
     */
    private const val AUTH_FAILED_MESSAGE =
        "Access was rejected. Re-pair this device to sign in again."

    /**
     * A wire range is publishable only when present and non-inverted;
     * fractional bounds round to nearest (the wire carries integer-valued
     * doubles, and rounding — unlike truncation — never silently shrinks the
     * device's span at one end). Null return = use the caller's fallback.
     */
    private fun saneIntRange(range: ControlRange?): ClosedRange<Int>? =
        range?.takeIf { it.min <= it.max }?.let { it.min.roundToInt()..it.max.roundToInt() }
}

/**
 * The JVM-safe projection of one wire lens into the app lens model's fields —
 * everything [com.raulshma.lenscast.camera.model.CameraLensInfo] carries
 * EXCEPT its Android-typed camera selector (see [DeviceStatusMappers.lens]).
 */
data class MappedLens(
    val id: String,
    val labelKind: LensLabelKind,
    val lensFacing: Int,
    val focalLength: Float,
    val physicalCameraId: String?,
    val zoomRatio: Float?,
) {
    companion object {
        /**
         * `androidx.camera.core.CameraSelector.LENS_FACING_FRONT` (0) /
         * `LENS_FACING_BACK` (1), mirrored as literals so this pure layer
         * never names the Android class. The values are frozen Camera2
         * metadata contract, and the unit tests pin the mirror against the
         * real constants so a drift fails loudly.
         */
        const val LENS_FACING_FRONT = 0
        const val LENS_FACING_BACK = 1
    }
}
