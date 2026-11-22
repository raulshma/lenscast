package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.core.Secret
import androidx.datastore.preferences.core.Preferences
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.OutputRotationPolicy
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.apiTokenHashPref
import com.raulshma.lenscast.data.cameraSettingsPref
import com.raulshma.lenscast.streaming.model.CameraSettingsDto
import com.raulshma.lenscast.streaming.model.SettingsExportDto
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import com.raulshma.lenscast.streaming.model.SettingsUpdateRequestDto
import com.raulshma.lenscast.streaming.model.StreamingSettingsDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types

/**
 * /api/settings — JSON-in/JSON-out mapping between the Web API DTOs and the
 * Settings Store. This handler only *writes* settings; the Settings Applier
 * applies them to the runtime.
 *
 * The RTSP video codec rides the same store round-trip as every other
 * setting: GET reads the persisted flow, PUT saves through its descriptor
 * (an absent or unknown wire name is skipped), and the Settings Applier
 * applies it to the manager through the restart ladder.
 */
class SettingsWebHandler(
    private val settingsDataStore: SettingsDataStore,
    /** The on-demand model store; its state rides the settings response, its download the POST route. */
    private val detectionModelStore: DetectionModelStore,
    /** The on-demand YAMNet model store; its download rides its own POST route (audio-model). */
    private val audioModelStore: AudioModelStore,
    /** The export envelope's timestamp source. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(SettingsResponseDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java) }
    private val exportAdapter by lazy { AppJson.moshi.adapter(SettingsExportDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }
    private val cameraDtoAdapter by lazy { AppJson.moshi.adapter(CameraSettingsDto::class.java) }
    private val streamingDtoAdapter by lazy { AppJson.moshi.adapter(StreamingSettingsDto::class.java) }

    /**
     * The raw body's key tree — the only way to know which section keys the
     * client actually sent, which is exactly what the section merge below
     * preserves vs. overlays.
     */
    private val rawBodyAdapter by lazy {
        AppJson.moshi.adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
    }

    /**
     * GET /api/settings — the settings document with `webhookHeaders`
     * redacted. The header map is a user-authored credential carrier
     * (frequently an Authorization header), and the role gate cannot split
     * this route by caller (the handler never sees the session's role), so
     * the read answer is viewer-safe by construction: every session reads
     * the document blank-headers, the same write-only shape the [Secret]
     * fields serialize — the field catalog's keep-on-blank encode keeps the
     * stored headers alive under the blank echo a whole-section save sends
     * back. The admin-only export twin ([export]) carries the stored value
     * verbatim and stays the lossless path.
     */
    suspend fun get(): String {
        val doc = buildResponse()
        return responseAdapter.toJson(
            doc.copy(streaming = doc.streaming.copy(webhookHeaders = "")),
        )
    }

    /**
     * GET /api/settings/export — the current settings document inside the
     * versioned [SettingsExportDto] envelope, for downloading as a file and
     * restoring via POST /api/settings/import on this or another device.
     *
     * What the export carries: exactly what GET /api/settings returns — the
     * write-only credentials (WebDAV password, Telegram bot token, MQTT
     * password, WHIP bearer token, API token, and the RTMP push URL, whose
     * stream key is embedded in it) serialize blank through the [Secret]
     * wire type, but `webhookHeaders` is a user-authored header map that
     * round-trips in full and frequently contains an Authorization header.
     * The file is configuration, not a sanitized share artifact — treat it
     * as such.
     */
    suspend fun export(): String =
        exportAdapter.toJson(
            SettingsExportDto(
                exportedAtMs = nowMs(),
                settings = buildResponse(),
            ),
        )

    /**
     * POST /api/settings/import — accepts a [SettingsExportDto] envelope (the
     * export route's own shape, schema-version gated) or a bare settings
     * update document, and applies it through the exact [put] path a
     * dashboard save uses, so every clamp and write-only-secret rule holds
     * identically. A bare document carries [SettingsUpdateRequestDto] PUT
     * semantics: fields present in a section apply, fields it omits keep
     * their stored values (section-level merge — see [put]) — the envelope
     * is the lossless path and the one the dashboard's export button
     * produces.
     * Fails with an [IllegalArgumentException] (the router's error-payload
     * shape) for unparseable bodies, unsupported schema versions, and
     * documents with no settings at all.
     */
    suspend fun import(body: String): String {
        val parsed = SettingsImportParser.parse(body)
        val update = when (parsed) {
            is SettingsImportParser.Parsed.Envelope -> {
                val settings = requireNotNull(parsed.export.settings) { "Export envelope carries no settings" }
                SettingsUpdateRequestDto(camera = settings.camera, streaming = settings.streaming)
            }
            is SettingsImportParser.Parsed.RawUpdate -> parsed.update
        }
        put(updateAdapter.toJson(update))
        return successAdapter.toJson(SuccessResponse())
    }

    /**
     * The response decodes one fresh file pass ([SettingsDataStore.readFresh])
     * through the pref descriptors: a GET landing right after a save answers
     * from the disk, never from the shared flows' lagging collector beat —
     * the stale `iso:null` / `compat:null` echo a back-to-back curl used to
     * observe. Only the model lifecycle stays live: runtime state, not a
     * persisted setting.
     */
    private suspend fun buildResponse(): SettingsResponseDto =
        settingsDataStore.readFresh { prefs ->
            SettingsResponseDto(
                camera = toCameraDto(cameraSettingsPref.decode(prefs)),
                streaming = streamingDtoFrom(prefs),
            )
        }

    /**
     * The streaming section of the settings document, decoded from the
     * [SettingsDataStore.readFresh] file pass through [StreamingFieldCatalog];
     * the response-only facts (model lifecycle, `apiTokenConfigured`) are
     * appended here. The same decode feeds the GET response and the PUT's
     * stored-side merge base, so both answer and overlay come from one
     * committed file state.
     */
    private fun streamingDtoFrom(prefs: Preferences): StreamingSettingsDto {
        // Response-only model facts: the store's published lifecycle.
        val modelWire = DetectionModelStore.wireFields(detectionModelStore.state.value)
        return StreamingFieldCatalog.decode(prefs).copy(
            apiTokenConfigured = apiTokenHashPref.decode(prefs).isNotBlank(),
            mlModelState = modelWire.state,
            mlModelProgress = modelWire.progress,
            mlModelError = modelWire.error,
        )
    }

    /**
     * PUT /api/settings — section-level merge: the raw JSON keys the client
     * sent overlay the stored document ([mergeSection]), so a section that
     * omits a field keeps the stored value instead of silently resetting it
     * to the DTO default. Full-section writers (the import envelope) carry
     * every key and behave exactly as before, and the dashboard PUTs its
     * partial sections straight through for the server to merge (no
     * client-side GET-merge-PUT chain); an explicit JSON null still overlays
     * as null and resets an optional field.
     * Each section then persists in ONE atomic DataStore transaction
     * ([SettingsDataStore.saveEntries], the entries built by
     * [StreamingFieldCatalog.encode]) — a crash mid-PUT cannot land a half-applied
     * section, and a concurrent disk-truth reader sees the old or the new
     * document, never a torn one. The camera rebuild keeps the torch
     * contract: no torch fields ride the DTO, so the stored torch pair
     * carries over verbatim — convergence to the live LED fact is
     * CameraService.applySettings' job (the single owner; the writer-side
     * live-fact snapshot this rebuild once took was the protocol behind
     * fixes 22fe040/e84b36d).
     */
    suspend fun put(body: String): String {
        val request = updateAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid settings JSON")
        val rawSections: Map<String, Any> = rawBodyAdapter.fromJson(body) ?: emptyMap()

        request.camera?.let {
            val current = settingsDataStore.settingsFromDisk()
            val cam = mergeSection(cameraDtoAdapter, toCameraDto(current), rawSections["camera"])
            val newSettings = CameraSettings(
                exposureCompensation = cam.exposureCompensation.coerceIn(
                    CameraSettings.EXPOSURE_COMPENSATION_MIN,
                    CameraSettings.EXPOSURE_COMPENSATION_MAX,
                ),
                iso = cam.iso?.let { if (it > 0) it else null },
                exposureTime = cam.exposureTime?.let { if (it > 0) it else null },
                focusMode = parseEnum(cam.focusMode, current.focusMode),
                focusDistance = cam.focusDistance?.coerceIn(0f, CameraSettings.FOCUS_DISTANCE_MAX),
                whiteBalance = parseEnum(cam.whiteBalance, current.whiteBalance),
                colorTemperature = cam.colorTemperature?.coerceIn(
                    CameraSettings.COLOR_TEMPERATURE_MIN,
                    CameraSettings.COLOR_TEMPERATURE_MAX,
                ),
                zoomRatio = cam.zoomRatio.toFloat().coerceIn(CameraSettings.ZOOM_RATIO_MIN, CameraSettings.ZOOM_RATIO_MAX),
                frameRate = cam.frameRate.coerceIn(CameraSettings.FRAME_RATE_MIN, CameraSettings.FRAME_RATE_MAX),
                resolution = parseEnum(cam.resolution, current.resolution),
                stabilization = cam.stabilization,
                hdrMode = parseEnum(cam.hdrMode, current.hdrMode),
                sceneMode = cam.sceneMode,
                nightVisionMode = parseEnum(cam.nightVisionMode, current.nightVisionMode),
                // Torch is NOT a web-camera-section setting: the camera DTO
                // carries no torch fields (GET can't present them, PUT can't
                // change them), so the rebuild carries the STORED pair
                // verbatim. Converging it to the live LED fact is
                // CameraService.applySettings' job — this save lands, the
                // applier applies it, and the untouched torch pair converges
                // there (carrying the stale stored value used to kill a lit
                // LED — the scheduled-automation torch, too — on every
                // dashboard camera-section save, issue #16, fixed writer-side
                // by 22fe040 before the verdict moved to the applier).
                // torchStrength stays the stored intent: a live flip drives
                // the device-default brightness and carries no strength
                // intent of its own.
                torchEnabled = current.torchEnabled,
                outputRotation = OutputRotationPolicy.coerce(cam.outputRotation),
                orientationLocked = cam.orientationLocked,
                torchStrength = current.torchStrength,
            )
            settingsDataStore.saveSettings(newSettings)
        }

        request.streaming?.let {
            val rawStreaming = rawSections["streaming"] as? Map<*, *>
            // One untorn disk snapshot feeds every base this PUT reads — the
            // merge base and the overlay/hash special cases ([fresh] is an
            // immutable Preferences, safe to hold across the merge).
            val fresh = settingsDataStore.readFresh { it }
            val stream = mergeSection(
                streamingDtoAdapter,
                streamingDtoFrom(fresh),
                rawStreaming,
            )
            // The raw section's key set is the touched set: the fields the
            // client actually sent. Only they become entries — untouched
            // siblings keep their stored values bit-for-bit instead of being
            // re-encoded back onto the file.
            val touched = rawStreaming?.keys?.filterIsInstance<String>()?.toSet().orEmpty()
            // ONE atomic section save for the whole merged document (this
            // used to be ~103 independent saveX transactions, each its own
            // read-modify-write pass of the preferences file). Out-of-range
            // numeric values are still the Settings Store's clamp policy, not
            // this handler's: the entries ride the same descriptors, which
            // coerce on encode.
            settingsDataStore.saveEntries(StreamingFieldCatalog.encode(stream, touched, fresh))
        }

        return successAdapter.toJson(SuccessResponse())
    }

    /**
     * Section-level merge for the settings PUT: the incoming section's raw
     * JSON keys overlay the stored section's value tree, so omitted keys
     * keep their stored values while sent keys (including explicit nulls)
     * replace them. Both sides run through the section's own Moshi adapter,
     * so the key set can never drift from the DTO shape — no hand-written
     * presence list to maintain.
     */
    private fun <T : Any> mergeSection(adapter: JsonAdapter<T>, stored: T, incoming: Any?): T {
        if (incoming !is Map<*, *>) return stored
        val merged = adapter.toJsonValue(stored) as MutableMap<Any?, Any?>
        for ((key, value) in incoming) merged[key] = value
        return adapter.fromJsonValue(merged) ?: stored
    }

    /**
     * POST /api/settings/ml-model/download — asks the model store to fetch
     * the detection model. Idempotent in the store (Ready and in-flight
     * downloads are no-ops); the outcome surfaces on the next settings GET
     * through `mlModelState`, never as this route's error.
     */
    suspend fun downloadModel(): String {
        detectionModelStore.requestDownload()
        return successAdapter.toJson(SuccessResponse())
    }

    /**
     * POST /api/settings/audio-model/download — the audio twin of
     * [downloadModel]: asks the YAMNet store to fetch the sound-classification
     * model, idempotent there, outcome never this route's error (the in-app
     * detection section's model row is the state surface).
     */
    suspend fun downloadAudioModel(): String {
        audioModelStore.requestDownload()
        return successAdapter.toJson(SuccessResponse())
    }

    private fun toCameraDto(settings: CameraSettings) = CameraSettingsDto(
        exposureCompensation = settings.exposureCompensation,
        iso = settings.iso,
        exposureTime = settings.exposureTime,
        focusMode = settings.focusMode.name,
        focusDistance = settings.focusDistance,
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
}

/**
 * The pure import-body classifier behind POST /api/settings/import: an export
 * envelope (validated against its schema version) or a bare settings update
 * document — anything else is an [IllegalArgumentException] the router turns
 * into the standard error payload. JVM-tested; no Android types.
 */
object SettingsImportParser {

    sealed interface Parsed {
        data class Envelope(val export: SettingsExportDto) : Parsed
        data class RawUpdate(val update: SettingsUpdateRequestDto) : Parsed
    }

    private val envelopeAdapter by lazy { AppJson.moshi.adapter(SettingsExportDto::class.java) }
    private val updateAdapter by lazy { AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java) }

    fun parse(body: String): Parsed {
        // The envelope path first: an envelope always carries the version and
        // app fields, so its presence (or a present-but-null settings doc,
        // which the caller rejects) decides here.
        val envelope = runCatching { envelopeAdapter.fromJson(body) }.getOrNull()
        if (envelope != null && (envelope.settings != null || envelope.exportedAtMs != 0L || envelope.app != SettingsExportDto.APP_IDENTITY)) {
            if (envelope.schemaVersion != SettingsExportDto.SETTINGS_SCHEMA_VERSION) {
                throw IllegalArgumentException(
                    "Unsupported settings schema version: ${envelope.schemaVersion} " +
                        "(supported: ${SettingsExportDto.SETTINGS_SCHEMA_VERSION})",
                )
            }
            return Parsed.Envelope(envelope)
        }
        // A bare settings document, or an envelope so bare it is
        // indistinguishable from one (both fall through to the update parse).
        // An update carrying no section at all is a no-op import, rejected —
        // a "success" that changed nothing would be a lie.
        val update = runCatching { updateAdapter.fromJson(body) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid settings JSON")
        if (update.camera == null && update.streaming == null) {
            throw IllegalArgumentException("No settings to import")
        }
        return Parsed.RawUpdate(update)
    }
}
