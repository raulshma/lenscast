package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.companion.client.dto.RemoteCameraSettings
import com.raulshma.lenscast.companion.client.dto.SettingsUpdate

/**
 * The settings seam between the camera dashboard's [CameraSettings] and the
 * device's settings document (`GET`/`PUT /api/settings`, the camera section —
 * TS `CameraSettings` / companion `RemoteCameraSettings`). Two pure
 * directions, both owned HERE so the later console ViewModel only composes:
 *
 * - **wire → settings** ([toCameraSettings]): the device-side fields of the
 *   dashboard's settings snapshot, decoded from the fetched document.
 * - **settings → wire patch** ([toPatch]): the `PUT /api/settings` body for a
 *   dashboard-side edit, built to the payload shape
 *   the settings source's save sends (a fresh GET merged with the
 *   edited values, PUT as one whole section — see the wholesale rule below).
 *
 * The device-side vs viewer-side split, for the record:
 *
 * - **Device-side (mapped here)**: every [CameraSettings] field — exposure,
 *   ISO/manual time, focus, WB/kelvin, zoom, frame rate, resolution,
 *   stabilization, HDR, night vision, scene mode, output rotation, orientation
 *   lock — because each one configures the device's camera pipeline and the
 *   wire's camera section carries each one's persisted value.
 * - **Viewer-local lanes (stay OUT)**: grid style, self-timer, the
 *   picture-in-picture preview toggle, and the pro-tools toggles
 *   (histogram/zebras/peaking). None of them HAS a [CameraSettings] field —
 *   the dashboard carries them as separate console flows — and none is
 *   persisted in the wire's camera section: they are this phone's viewing
 *   preferences, not the device's capture configuration, so the console
 *   ViewModel merges its own viewer prefs around the snapshot this mapper
 *   builds. EXIF/geotag would be device-side if the wire carried it, but no
 *   settings field does today — nothing to map.
 * - The wire's `streaming` section is out entirely: those lanes (outputs,
 *   overlays, detection, integrations, storage) belong to the companion's
 *   system tab, which has its own GET-merge-PUT editor; the camera dashboard
 *   never renders them.
 *
 * The wire contract this patcher honors: the server merges a PUT section at
 * the JSON-key level onto its stored document, and the companion's own DTOs
 * pin the stricter GET-merge-PUT convention (never a hand-built partial — a
 * present section must carry every field so an older device that persists
 * wholesale cannot reset omitted siblings to defaults). [toPatch] therefore
 * emits the section WHOLE even when one field changed; the "diff" decides
 * WHETHER a PUT is worth sending, never a smaller payload.
 *
 * Torch and zoom are excluded — loudly:
 *
 * - [CameraSettings.torchEnabled] has NO settings-wire counterpart at all
 *   (the server's camera DTO deliberately carries no torch fields, and its
 *   rebuild preserves the stored torch pair on every camera-section save —
 *   the torch contract). Remotely a torch flip rides `POST /api/camera/torch`.
 * - [CameraSettings.zoomRatio] DOES ride the settings wire, but a live zoom
 *   change rides `POST /api/camera/zoom` — which also persists the accepted
 *   ratio server-side. Pushing the console's zoom through a settings PUT
 *   would double-write the same fact through two owners, so [toPatch] pins
 *   the section's `zoomRatio` to the device-reported value ([previous]) and
 *   never lets a zoom-only edit emit a PUT.
 *
 * Both exclusions are enumerated in [PATCH_EXCLUDED_FIELDS]; fields with no
 * wire counterpart at all in [UNMAPPED_FIELDS]. The console package decides
 * the UI treatment of both sets; this mapper only keeps the sets honest.
 */
object RemoteSettingsMapper {

    /**
     * [CameraSettings] fields with NO camera-section wire counterpart —
     * neither the GET document carries them nor any settings PUT can change
     * them. `torchEnabled` and `torchStrength` are the torch pair the server
     * keeps out of the wire by contract (stored pair preserved verbatim on
     * every camera-section save; live flips ride `/api/camera/torch`, and a
     * flip deliberately carries no brightness intent). Enumerated, not
     * implied: the console package's UI needs the exact list to know which
     * dashboard controls cannot be persisted through settings.
     */
    val UNMAPPED_FIELDS: Set<String> = setOf("torchEnabled", "torchStrength")

    /**
     * [CameraSettings] fields whose CHANGES must never emit or shape a
     * settings PUT — remotely they ride the dedicated control endpoints
     * instead: `torchEnabled` → `POST /api/camera/torch`, `zoomRatio` →
     * `POST /api/camera/zoom` (which persists its accepted value server-side).
     * `torchEnabled` appears here AND in [UNMAPPED_FIELDS] (no wire
     * counterpart AND an owning endpoint); `zoomRatio` has a wire counterpart
     * that only the device's own writers may move. [toPatch] enforces this.
     */
    val PATCH_EXCLUDED_FIELDS: Set<String> = setOf("torchEnabled", "zoomRatio")

    /**
     * The device-side settings snapshot from the fetched camera section.
     * Faithful decode — the device is the truth source, so no clamping here
     * (the device's own store/applier owns coercion; the console renders what
     * the device reports and writes back through [toPatch]). Enum fields
     * decode by name with the [CameraSettings] defaults as fallback: the
     * console has no stored "current" to keep the way the server's own
     * `parseEnum` does, and a device that adds an enum value before this app
     * learns it must still render — the next GET re-reports whatever the
     * device actually kept.
     */
    fun toCameraSettings(camera: RemoteCameraSettings): CameraSettings = CameraSettings(
        exposureCompensation = camera.exposureCompensation,
        iso = camera.iso,
        exposureTime = camera.exposureTime,
        focusMode = parseEnum(camera.focusMode, FocusMode.AUTO),
        focusDistance = camera.focusDistance?.toFloat(),
        whiteBalance = parseEnum(camera.whiteBalance, WhiteBalance.AUTO),
        colorTemperature = camera.colorTemperature,
        zoomRatio = camera.zoomRatio.toFloat(),
        frameRate = camera.frameRate,
        resolution = parseEnum(camera.resolution, Resolution.FHD_1080P),
        stabilization = camera.stabilization,
        hdrMode = parseEnum(camera.hdrMode, HdrMode.OFF),
        sceneMode = camera.sceneMode,
        nightVisionMode = parseEnum(camera.nightVisionMode, NightVisionMode.OFF),
        outputRotation = camera.outputRotation,
        orientationLocked = camera.orientationLocked,
    )

    /**
     * The camera section rebuilt from a settings snapshot — the exact
     * field-for-field inverse of [toCameraSettings] and the server's own
     * `toCameraDto` transcription (same wire names, same enum `.name`
     * encodings), so a round trip is value-stable. This is the section
     * [toPatch] PUTs; it is exposed because the ViewModel's manual save path
     * (fresh GET → apply edit → PUT the whole section) wants the same
     * builder the diff uses.
     */
    fun toRemoteSection(settings: CameraSettings): RemoteCameraSettings = RemoteCameraSettings(
        exposureCompensation = settings.exposureCompensation,
        iso = settings.iso,
        exposureTime = settings.exposureTime,
        focusMode = settings.focusMode.name,
        focusDistance = settings.focusDistance?.toDouble(),
        whiteBalance = settings.whiteBalance.name,
        colorTemperature = settings.colorTemperature,
        zoomRatio = settings.zoomRatio.toDouble(),
        frameRate = settings.frameRate,
        resolution = settings.resolution.name,
        stabilization = settings.stabilization,
        hdrMode = settings.hdrMode.name,
        sceneMode = settings.sceneMode,
        nightVisionMode = settings.nightVisionMode.name,
        outputRotation = settings.outputRotation,
        orientationLocked = settings.orientationLocked,
    )

    /**
     * The `PUT /api/settings` body for a dashboard edit, or null when there
     * is nothing to PUT: no field that the wire carries changed, or every
     * change is in [PATCH_EXCLUDED_FIELDS] (torch/zoom ride their dedicated
     * endpoints — the caller, the console ViewModel, is responsible for
     * calling those; this mapper will not smuggle them through settings).
     *
     * When something DID change, the update carries the camera section WHOLE
     * (see the class KDoc's wire-contract note). With a [fresh] section — the
     * wire document fetched at save time — the section is [fresh] with ONLY
     * the previous→next edits applied ([withEditsApplied]): every unedited
     * field rides the device's current wire value verbatim (an unrecognized
     * focus mode such as `EDOF`, which [toCameraSettings] renders through the
     * AUTO fallback, must not be re-PUT as "AUTO"), and `zoomRatio` rides
     * fresh too — the excluded ratio only ever moves through
     * `/api/camera/zoom`, so a frame-rate-only save cannot clobber the
     * device's latest live zoom with the visible snapshot's stale value.
     * Without [fresh] the section is rebuilt from [next] (decoded values
     * only — the decode fallbacks are then re-encoded), with the excluded
     * `zoomRatio` pinned to [previous]'s device-reported value so the
     * wholesale write cannot move what `/api/camera/zoom` owns. Null
     * `streaming` — the server leaves that section untouched, exactly the
     * shape the settings save PUTs on its own lane.
     *
     * Contract on the caller: [previous]/[next] are the decoded snapshot the
     * user edited, and [fresh] — when provided — must come from a FRESH
     * `GET` at save time (the GET-merge-PUT discipline); a stale base would
     * re-write stale device state wholesale.
     */
    fun toPatch(
        previous: CameraSettings,
        next: CameraSettings,
        fresh: RemoteCameraSettings? = null,
    ): SettingsUpdate? {
        val changed = CARRIED_FIELDS.filter { it.get(previous) != it.get(next) }
        if (changed.isEmpty()) return null
        val section = if (fresh != null) {
            fresh.withEditsApplied(changed.map { it.wireName }.toSet(), next)
        } else {
            val pinned = if (previous.zoomRatio != next.zoomRatio) {
                next.copy(zoomRatio = previous.zoomRatio)
            } else {
                next
            }
            toRemoteSection(pinned)
        }
        return SettingsUpdate(camera = section)
    }

    /**
     * The fresh wire section with ONLY the user's edits ([changed] — the
     * wire names whose previous→next extracts differ) applied onto it: an
     * edited field takes [next]'s value, every other field keeps the wire's
     * verbatim value (unrecognized enum names included — the decode fallback
     * is a DISPLAY concern, never a write-back), and the excluded
     * `zoomRatio` keeps the device's value unconditionally. One row per
     * carried field, mirroring [CARRIED_FIELDS].
     */
    private fun RemoteCameraSettings.withEditsApplied(
        changed: Set<String>,
        next: CameraSettings,
    ): RemoteCameraSettings = copy(
        exposureCompensation =
            if ("exposureCompensation" in changed) next.exposureCompensation else exposureCompensation,
        iso = if ("iso" in changed) next.iso else iso,
        exposureTime = if ("exposureTime" in changed) next.exposureTime else exposureTime,
        focusMode = if ("focusMode" in changed) next.focusMode.name else focusMode,
        focusDistance =
            if ("focusDistance" in changed) next.focusDistance?.toDouble() else focusDistance,
        whiteBalance = if ("whiteBalance" in changed) next.whiteBalance.name else whiteBalance,
        colorTemperature =
            if ("colorTemperature" in changed) next.colorTemperature else colorTemperature,
        // zoomRatio: PATCH_EXCLUDED_FIELDS — never moved by a settings PUT.
        frameRate = if ("frameRate" in changed) next.frameRate else frameRate,
        resolution = if ("resolution" in changed) next.resolution.name else resolution,
        stabilization = if ("stabilization" in changed) next.stabilization else stabilization,
        hdrMode = if ("hdrMode" in changed) next.hdrMode.name else hdrMode,
        sceneMode = if ("sceneMode" in changed) next.sceneMode else sceneMode,
        nightVisionMode =
            if ("nightVisionMode" in changed) next.nightVisionMode.name else nightVisionMode,
        outputRotation = if ("outputRotation" in changed) next.outputRotation else outputRotation,
        orientationLocked =
            if ("orientationLocked" in changed) next.orientationLocked else orientationLocked,
    )

    /**
     * The diff table: every [CameraSettings] field the settings wire carries,
     * as an extractor pair — a field is "changed" when its extracts differ.
     * 16 wire fields minus [PATCH_EXCLUDED_FIELDS]' zoomRatio = these 15;
     * the torch pair never appears (no wire counterpart). Table-driven so a
     * new wire field is one row here and one mapping line in each direction —
     * a missing row fails the derived partition test, and a row extracting
     * the wrong field fails the per-field patch check (one edit per row must
     * surface exactly that row and ride the PUT; the round-trip no-op cannot
     * see either).
     */
    internal val CARRIED_FIELDS: List<FieldDiff> = listOf(
        FieldDiff("exposureCompensation") { it.exposureCompensation },
        FieldDiff("iso") { it.iso },
        FieldDiff("exposureTime") { it.exposureTime },
        FieldDiff("focusMode") { it.focusMode },
        FieldDiff("focusDistance") { it.focusDistance },
        FieldDiff("whiteBalance") { it.whiteBalance },
        FieldDiff("colorTemperature") { it.colorTemperature },
        FieldDiff("frameRate") { it.frameRate },
        FieldDiff("resolution") { it.resolution },
        FieldDiff("stabilization") { it.stabilization },
        FieldDiff("hdrMode") { it.hdrMode },
        FieldDiff("sceneMode") { it.sceneMode },
        FieldDiff("nightVisionMode") { it.nightVisionMode },
        FieldDiff("outputRotation") { it.outputRotation },
        FieldDiff("orientationLocked") { it.orientationLocked },
    )

    /**
     * One diff-table row: the wire field name (read by the tests to pin the
     * table's contents) and its settings-side view.
     */
    internal class FieldDiff(
        val wireName: String,
        val get: (CameraSettings) -> Any?,
    )

    /** Enum-by-name decode with the app default as the fallback (see [toCameraSettings]). */
    private inline fun <reified E : Enum<E>> parseEnum(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
