package com.raulshma.lenscast.companion.console

import android.app.Activity
import com.raulshma.lenscast.camera.CameraSettingsEditor
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.PhotoCapturePlan
import com.raulshma.lenscast.camera.model.PhotoAspectRatio
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.AuthConfigInfo
import com.raulshma.lenscast.companion.client.dto.RemoteCameraSettings
import com.raulshma.lenscast.companion.client.dto.SettingsUpdate
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.core.SecretUpdate
import com.raulshma.lenscast.core.parseEnum
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.settings.SettingsCapabilities
import com.raulshma.lenscast.settings.SettingsSource
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The settings screens' REMOTE backend: [SettingsSource] over the paired
 * device's settings document — `GET /api/settings` on a beat (the whole
 * camera + streaming doc), `PUT /api/settings` for every device-side write,
 * and `GET/PUT /api/auth/config` for the credential section. Built by
 * [com.raulshma.lenscast.companion.di.CompanionGraph.settingsSource], cached
 * per device beside the other sources.
 *
 * **The lane partition** (the [SettingsSource] KDoc's table, as bound HERE):
 *
 * - **Device-side** members ride the document: the camera section maps onto
 *   [CameraSettings] through [RemoteSettingsMapper] (the dashboard's existing
 *   pure mapper — same wire, same round-trip discipline), the streaming
 *   section maps field-for-field onto the screen's typed members, and
 *   `authSettings` maps the redacted `/api/auth/config` read. Writes are the
 *   established whole-section GET-merge-PUT (never a hand-built partial — a
 *   present section persists wholesale server-side), serialized through one
 *   mutex and launched in submission order ([launchWrite]'s chain), so
 *   overlapping writes cannot interleave their GET-merge-PUT chains and the
 *   last submitted edit is the saved one.
 * - **Viewer-local** lanes — showPreview, dynamic theming, and the viewfinder
 *   aids (grid/self-timer/spirit level/histogram/zebras/peaking) — bind the
 *   LOCAL [SettingsDataStore] injected at construction: they are THIS phone's
 *   viewing/theming preferences (the
 *   [DeviceConsoleViewModel] wave-3 precedent), the device's document never
 *   carries them, and their writes ignore [canEdit] (a viewer still themes
 *   their own app). The network surface therefore exposes only device-side
 *   members; the screens read the viewer-local lanes through this source and
 *   stay branch-free.
 * - **Phone-only** facts degrade honestly: the camera control ranges ride a
 *   `GET /api/status` fetch on the same beat (mapped through
 *   [DeviceStatusMappers], the shared fallback spans before the first
 *   answer), the battery-optimization state pins false with a no-op request
 *   (the row is capability-hidden), and the photo-capture knob writes
 *   (quality/aspect/RAW/geotag — no wire carrier) are gated no-ops.
 *
 * Write shape: an edit re-fetches the document, applies its transform onto
 * the FRESH section, PUTs the whole section, and adopts the merged answer as
 * confirmed state — the companion settings save discipline (a fresh GET
 * merged before each PUT). A streaming write additionally renders through an
 * optimistic pending-edit overlay so a switch shows without waiting the
 * round trip — the overlay is kept SEPARATE from the confirmed document, and
 * a failed write drops only its own edit (later pending edits stay) until a
 * successful read re-publishes the device's truth. Camera writes go through
 * [RemoteSettingsMapper.toPatch] with the fresh wire section as the base, and
 * never PUT a zoom-only change: zoom rides `POST /api/camera/zoom` (which
 * persists its accepted value server-side) — the mapper's own contract,
 * honored here. The poll reads serialize with the writes through the same
 * mutex, so a poll that started before a completed PUT cannot replace the
 * write's newer document.
 *
 * The document poll runs only while a screen is subscribed (the
 * WhileSubscribed beat the other sources use), so a deep-linked settings
 * screen works without any live link and costs nothing when closed.
 *
 * Role gate: [canEdit] is the roster role's verdict; a gated device-side
 * write is a no-op at the source (the screens' read-only banner is
 * [SettingsCapabilities.canEdit], published here).
 */
class RemoteSettingsSource(
    /**
     * The device's JSON client; null when the roster lost the device — a
     * settled no-dial state (the wire defaults, viewer-local lanes live).
     */
    private val api: LenscastApiClient?,
    /** The roster role's config-write verdict (admin pairings only). */
    override val canEdit: Boolean,
    /**
     * THIS phone's settings store — the viewer-local lanes' backend in
     * remote mode (and nothing else: no device-side member reads it).
     */
    private val localSettings: SettingsDataStore,
    /** The poll beat (the injectable record; tests shrink it). */
    private val timings: ConsoleTimings = ConsoleTimings(),
    /** The scope the beat and the write round trips run on (the graph's app scope). */
    private val scope: CoroutineScope,
) : SettingsSource {

    override val capabilities =
        MutableStateFlow(SettingsCapabilities.remote(canEdit = canEdit))

    // The device's last CONFIRMED document — a successful read or write
    // answer; never an optimistic guess. The beat refreshes it while a screen
    // is subscribed (serialized against the writes so a poll that started
    // before a completed PUT cannot replace the write's newer document).
    private val _doc = MutableStateFlow<AllSettings?>(null)

    /**
     * The streaming edits whose writes are still in flight — the optimistic
     * overlay, kept SEPARATE from [_doc]'s confirmed state (a failed write
     * drops only its own edit; later pending edits stay rendered). Applied
     * over the confirmed document at publication time, never written into it.
     */
    private val pendingStreamingEdits =
        CopyOnWriteArrayList<(StreamingSettings) -> StreamingSettings>()

    /** Bumped whenever [pendingStreamingEdits] moves, so the view re-emits. */
    private val pendingEditsRevision = MutableStateFlow(0L)

    /** The redacted auth-config read; lands on the beat (admin-only route). */
    private val _auth = MutableStateFlow(toAuthSettings(AuthConfigInfo()))

    /** The status ranges — mapped once the beat's status fetch lands. */
    private val _zoomRange = MutableStateFlow(DeviceStatusMappers.zoomRange(null))
    private val _isoRange = MutableStateFlow(DeviceStatusMappers.isoRange(null))
    private val _exposureRange =
        MutableStateFlow(DeviceStatusMappers.exposureCompensationRange(null))

    /** Monotonic beat tick: each fetch bumps it, so the StateFlow never conflates two beats. */
    private val beat: StateFlow<Long> = flow {
        var tick = 0L
        while (true) {
            fetchDoc()
            fetchAuthConfig()
            fetchRanges()
            emit(++tick)
            delay(timings.settingsPollMs)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MS), 0L)

    /**
     * One merged view: re-emitted by every beat tick, every write answer,
     * and every pending-edit change, as the confirmed document overlaid with
     * the optimistic pending edits. SHARED (`stateIn`, the beat's own
     * WhileSubscribed grace — no second stop grace on top of it): the many
     * per-field projections below consume one computed snapshot instead of
     * each subscription independently re-running [publishedDoc] on every
     * upstream emission.
     */
    private val doc: StateFlow<AllSettings?> =
        merge(beat, _doc, pendingEditsRevision)
            .map { publishedDoc() }
            .stateIn(scope, WhileSubscribed, null)

    /**
     * The confirmed document with the in-flight optimistic edits folded over
     * its streaming section. The write-only secrets never render from a
     * pending edit: whatever the user submitted, the published view carries
     * the device's read-side redacted values (the GET answer's blanks), so
     * the embedded stream keys and the submitted WHIP token / WebDAV
     * password stay out of every published state.
     */
    private fun publishedDoc(): AllSettings? {
        val confirmed = _doc.value ?: return null
        if (pendingStreamingEdits.isEmpty()) return confirmed
        var streaming = confirmed.streaming
        for (edit in pendingStreamingEdits) streaming = edit(streaming)
        return confirmed.copy(
            streaming = streaming.copy(
                rtmpUrl = confirmed.streaming.rtmpUrl,
                srtUrl = confirmed.streaming.srtUrl,
                whipToken = confirmed.streaming.whipToken,
                backupWebdavPassword = confirmed.streaming.backupWebdavPassword,
            ),
        )
    }

    /** The doc's streaming section, or the wire defaults before the first answer. */
    private val streaming: Flow<StreamingSettings> =
        doc.map { it?.streaming ?: StreamingSettings() }

    /**
     * The beat's document read, serialized with the writes: a poll that
     * started before a completed PUT acquires [writeMutex] only after the
     * write released it, so its answer cannot replace the write's newer
     * document with the pre-write state.
     */
    private suspend fun fetchDoc() {
        val client = api ?: return
        writeMutex.withLock { readDoc(client) }
    }

    /**
     * The raw document read — callers either hold [writeMutex] (a write's own
     * failure recovery and re-reads: the lock is held, never reacquired) or
     * accept racing one.
     */
    private suspend fun readDoc(client: LenscastApiClient) {
        val fetched = try {
            client.getSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // a read: transient failure keeps the last document
        }
        _doc.value = fetched
    }

    /**
     * The redacted credential read, serialized with the auth writes (the same
     * [writeMutex] discipline as [fetchDoc]): a poll that started before a
     * completed `/api/auth/config` PUT cannot publish the pre-write config
     * after the write's own publication.
     */
    private suspend fun fetchAuthConfig() {
        val client = api ?: return
        writeMutex.withLock {
            val config = try {
                client.authConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            _auth.value = toAuthSettings(config)
        }
    }

    /** The camera's live control ranges (the settings sliders' spans). */
    private suspend fun fetchRanges() {
        val client = api ?: return
        val status = try {
            client.getStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        _zoomRange.value = DeviceStatusMappers.zoomRange(status.zoomRange)
        _isoRange.value = DeviceStatusMappers.isoRange(status.isoRange)
        _exposureRange.value =
            DeviceStatusMappers.exposureCompensationRange(status.exposureCompensationRange)
    }

    // ── Device-side reads (the document, mapped) ──

    override val settings: StateFlow<CameraSettings> = doc
        .map { RemoteSettingsMapper.toCameraSettings(it?.camera ?: RemoteCameraSettings()) }
        .stateIn(scope, WhileSubscribed, CameraSettings())

    override val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _zoomRange
    override val availableIsoRange: StateFlow<ClosedRange<Int>> = _isoRange
    override val availableExposureRange: StateFlow<ClosedRange<Int>> = _exposureRange

    // No RAW verdict carrier on the wire; the photo section is hidden remotely.
    override val isRawCaptureSupported: StateFlow<Boolean> = MutableStateFlow(true)

    // Photo capture knobs: no settings-wire carrier — pinned at the app
    // defaults (the section is capability-hidden remotely; the writes no-op).
    override val photoJpegQuality: StateFlow<Int> =
        MutableStateFlow(PhotoCapturePlan.PHOTO_JPEG_QUALITY_DEFAULT)
    override val photoMaximizeQuality: StateFlow<Boolean> = MutableStateFlow(false)
    override val rawCaptureEnabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val photoAspectRatio: StateFlow<PhotoAspectRatio> =
        MutableStateFlow(PhotoAspectRatio.R16_9)
    override val geotagEnabled: StateFlow<Boolean> = MutableStateFlow(false)

    override val streamingPort: StateFlow<Int> =
        streaming.map { it.port }.stateIn(scope, WhileSubscribed, StreamingSettings().port)
    override val webStreamingEnabled: StateFlow<Boolean> =
        streaming.map { it.webStreamingEnabled }.stateIn(scope, WhileSubscribed, true)
    override val jpegQuality: StateFlow<Int> =
        streaming.map { it.jpegQuality }.stateIn(scope, WhileSubscribed, StreamingSettings().jpegQuality)
    override val adaptiveBitrateEnabled: StateFlow<Boolean> =
        streaming.map { it.adaptiveBitrateEnabled }.stateIn(scope, WhileSubscribed, false)
    override val adaptiveEncodedBitrateEnabled: StateFlow<Boolean> =
        streaming.map { it.adaptiveEncodedBitrateEnabled }.stateIn(scope, WhileSubscribed, false)
    override val hlsDvrSegments: StateFlow<Int> =
        streaming.map { it.hlsDvrSegments }.stateIn(scope, WhileSubscribed, 0)
    override val ecoIdleFpsEnabled: StateFlow<Boolean> =
        streaming.map { it.ecoIdleFpsEnabled }.stateIn(scope, WhileSubscribed, false)
    override val mdnsEnabled: StateFlow<Boolean> =
        streaming.map { it.mdnsEnabled }.stateIn(scope, WhileSubscribed, true)
    override val rtspEnabled: StateFlow<Boolean> =
        streaming.map { it.rtspEnabled }.stateIn(scope, WhileSubscribed, false)
    override val rtspPort: StateFlow<Int> =
        streaming.map { it.rtspPort }.stateIn(scope, WhileSubscribed, StreamingSettings().rtspPort)
    override val rtspBasicAuthCompat: StateFlow<Boolean> =
        streaming.map { it.rtspBasicAuthCompat }.stateIn(scope, WhileSubscribed, false)
    override val rtspInputFormat: StateFlow<RtspInputFormat> =
        streaming.map { parseEnum(it.rtspInputFormat, RtspInputFormat.AUTO) }
            .stateIn(scope, WhileSubscribed, RtspInputFormat.AUTO)
    override val rtspSubStreamEnabled: StateFlow<Boolean> =
        streaming.map { it.rtspSubStreamEnabled }.stateIn(scope, WhileSubscribed, false)
    override val rtmpEnabled: StateFlow<Boolean> =
        streaming.map { it.rtmpEnabled }.stateIn(scope, WhileSubscribed, false)
    override val rtmpUrl: StateFlow<String> =
        // Write-only on the wire: the GET answer is blank (the embedded
        // stream key never echoes), and the publication layer keeps a
        // submitted URL just as invisible — the field renders empty.
        streaming.map { it.rtmpUrl }.stateIn(scope, WhileSubscribed, "")
    override val whipEnabled: StateFlow<Boolean> =
        streaming.map { it.whipEnabled }.stateIn(scope, WhileSubscribed, false)
    override val whipUrl: StateFlow<String> =
        streaming.map { it.whipUrl }.stateIn(scope, WhileSubscribed, "")
    override val whipStunServer: StateFlow<String> =
        streaming.map { it.whipStunServer }.stateIn(scope, WhileSubscribed, "")
    override val srtEnabled: StateFlow<Boolean> =
        streaming.map { it.srtEnabled }.stateIn(scope, WhileSubscribed, false)
    override val srtUrl: StateFlow<String> =
        // Write-only on the wire (the RTMP URL's secret semantics — the GET
        // answer is blank): the publication layer keeps a submitted URL just
        // as invisible — the field renders empty.
        streaming.map { it.srtUrl }.stateIn(scope, WhileSubscribed, "")

    override val streamAudioEnabled: StateFlow<Boolean> =
        streaming.map { it.streamAudioEnabled }.stateIn(scope, WhileSubscribed, true)
    override val streamAudioBitrateKbps: StateFlow<Int> =
        streaming.map { it.streamAudioBitrateKbps }
            .stateIn(scope, WhileSubscribed, StreamingSettings().streamAudioBitrateKbps)
    override val streamAudioChannels: StateFlow<Int> =
        streaming.map { it.streamAudioChannels }
            .stateIn(scope, WhileSubscribed, StreamingSettings().streamAudioChannels)
    override val streamAudioEchoCancellation: StateFlow<Boolean> =
        streaming.map { it.streamAudioEchoCancellation }.stateIn(scope, WhileSubscribed, true)
    override val recordingAudioEnabled: StateFlow<Boolean> =
        streaming.map { it.recordingAudioEnabled }.stateIn(scope, WhileSubscribed, true)
    override val audioDeviceId: StateFlow<String> =
        streaming.map { it.audioDeviceId }.stateIn(scope, WhileSubscribed, "")

    override val motionDetectionEnabled: StateFlow<Boolean> =
        streaming.map { it.motionDetectionEnabled }.stateIn(scope, WhileSubscribed, false)
    override val motionSensitivityPercent: StateFlow<Int> =
        streaming.map { it.motionSensitivityPercent }
            .stateIn(scope, WhileSubscribed, StreamingSettings().motionSensitivityPercent)
    override val motionRecordingEnabled: StateFlow<Boolean> =
        streaming.map { it.motionRecordingEnabled }.stateIn(scope, WhileSubscribed, false)
    override val motionPostRollSeconds: StateFlow<Int> =
        streaming.map { it.motionPostRollSeconds }
            .stateIn(scope, WhileSubscribed, StreamingSettings().motionPostRollSeconds)
    override val motionArmScheduleEnabled: StateFlow<Boolean> =
        streaming.map { it.motionArmScheduleEnabled }.stateIn(scope, WhileSubscribed, false)
    override val motionArmStartMinute: StateFlow<Int> =
        streaming.map { it.motionArmStartMinute }
            .stateIn(scope, WhileSubscribed, StreamingSettings().motionArmStartMinute)
    override val motionArmEndMinute: StateFlow<Int> =
        streaming.map { it.motionArmEndMinute }
            .stateIn(scope, WhileSubscribed, StreamingSettings().motionArmEndMinute)
    override val motionArmDaysMask: StateFlow<Int> =
        streaming.map { it.motionArmDaysMask }
            .stateIn(scope, WhileSubscribed, StreamingSettings().motionArmDaysMask)
    override val motionCooldownSeconds: StateFlow<Int> =
        streaming.map { it.motionCooldownSeconds }
            .stateIn(scope, WhileSubscribed, StreamingSettings().motionCooldownSeconds)
    override val soundDetectionEnabled: StateFlow<Boolean> =
        streaming.map { it.soundDetectionEnabled }.stateIn(scope, WhileSubscribed, false)
    override val soundThresholdPercent: StateFlow<Int> =
        streaming.map { it.soundThresholdPercent }
            .stateIn(scope, WhileSubscribed, StreamingSettings().soundThresholdPercent)
    override val soundAdaptiveNoiseFloor: StateFlow<Boolean> =
        streaming.map { it.soundAdaptiveNoiseFloor }.stateIn(scope, WhileSubscribed, false)
    override val soundRecordingEnabled: StateFlow<Boolean> =
        streaming.map { it.soundRecordingEnabled }.stateIn(scope, WhileSubscribed, false)
    override val soundCooldownSeconds: StateFlow<Int> =
        streaming.map { it.soundCooldownSeconds }
            .stateIn(scope, WhileSubscribed, StreamingSettings().soundCooldownSeconds)
    override val soundClassificationEnabled: StateFlow<Boolean> =
        streaming.map { it.soundClassificationEnabled }.stateIn(scope, WhileSubscribed, false)
    override val soundClassificationConfidencePercent: StateFlow<Int> =
        streaming.map { it.soundClassificationConfidencePercent }
            .stateIn(scope, WhileSubscribed, StreamingSettings().soundClassificationConfidencePercent)
    override val soundClassificationAllowedClasses: StateFlow<Set<String>> =
        streaming.map { it.soundClassificationAllowedClasses.toSet() }
            .stateIn(scope, WhileSubscribed, emptySet())
    override val soundTriggerEnabled: StateFlow<Boolean> =
        streaming.map { it.soundTriggerEnabled }.stateIn(scope, WhileSubscribed, false)
    override val soundTriggerClasses: StateFlow<Set<String>> =
        streaming.map { it.soundTriggerClasses.toSet() }.stateIn(scope, WhileSubscribed, emptySet())
    override val mlDetectionEnabled: StateFlow<Boolean> =
        streaming.map { it.mlDetectionEnabled }.stateIn(scope, WhileSubscribed, false)
    override val mlMinScorePercent: StateFlow<Int> =
        streaming.map { it.mlMinScorePercent }
            .stateIn(scope, WhileSubscribed, StreamingSettings().mlMinScorePercent)
    override val mlIncludePerson: StateFlow<Boolean> =
        streaming.map { it.mlIncludePerson }.stateIn(scope, WhileSubscribed, true)
    override val mlIncludePets: StateFlow<Boolean> =
        streaming.map { it.mlIncludePets }.stateIn(scope, WhileSubscribed, true)
    override val mlIncludeVehicles: StateFlow<Boolean> =
        streaming.map { it.mlIncludeVehicles }.stateIn(scope, WhileSubscribed, true)
    override val tamperDetectionEnabled: StateFlow<Boolean> =
        streaming.map { it.tamperDetectionEnabled }.stateIn(scope, WhileSubscribed, false)
    override val detectionNotificationsEnabled: StateFlow<Boolean> =
        streaming.map { it.detectionNotificationsEnabled }.stateIn(scope, WhileSubscribed, true)
    override val alertQuietHoursEnabled: StateFlow<Boolean> =
        streaming.map { it.alertQuietHoursEnabled }.stateIn(scope, WhileSubscribed, false)
    override val alertQuietHoursStartMinute: StateFlow<Int> =
        streaming.map { it.alertQuietHoursStartMinute }
            .stateIn(scope, WhileSubscribed, StreamingSettings().alertQuietHoursStartMinute)
    override val alertQuietHoursEndMinute: StateFlow<Int> =
        streaming.map { it.alertQuietHoursEndMinute }
            .stateIn(scope, WhileSubscribed, StreamingSettings().alertQuietHoursEndMinute)
    override val pushEnabled: StateFlow<Boolean> =
        streaming.map { it.pushEnabled }.stateIn(scope, WhileSubscribed, false)
    override val unifiedPushEnabled: StateFlow<Boolean> =
        streaming.map { it.unifiedPushEnabled }.stateIn(scope, WhileSubscribed, false)

    // No VAPID-subject carrier on the settings wire: pinned empty (the field
    // is capability-hidden remotely), the write a no-op.
    private val _pushVapidSubject = MutableStateFlow("")
    override val pushVapidSubject: StateFlow<String> = _pushVapidSubject

    override val detectionModelState: StateFlow<DetectionModelStore.State> =
        streaming.map {
            RemoteSettingsMap.toDetectionModelState(
                state = it.mlModelState,
                progress = it.mlModelProgress,
                error = it.mlModelError,
            )
        }.stateIn(scope, WhileSubscribed, DetectionModelStore.State.NotDownloaded)

    // No audio-model carrier on the wire: the null-means-no-carrier degrade.
    override val audioModelState: StateFlow<AudioModelStore.State?> =
        MutableStateFlow(null)

    override val storageQuotaMb: StateFlow<Int> =
        streaming.map { it.storageQuotaMb }
            .stateIn(scope, WhileSubscribed, StreamingSettings().storageQuotaMb)
    override val mediaEncryptionEnabled: StateFlow<Boolean> =
        streaming.map { it.mediaEncryptionEnabled }.stateIn(scope, WhileSubscribed, false)
    override val continuousRecording: StateFlow<Boolean> =
        streaming.map { it.continuousRecording }.stateIn(scope, WhileSubscribed, false)
    override val continuousSegmentMinutes: StateFlow<Int> =
        streaming.map { it.continuousSegmentMinutes }
            .stateIn(scope, WhileSubscribed, StreamingSettings().continuousSegmentMinutes)
    override val watchdogEnabled: StateFlow<Boolean> =
        streaming.map { it.watchdogEnabled }.stateIn(scope, WhileSubscribed, false)
    override val watchdogMaxRetries: StateFlow<Int> =
        streaming.map { it.watchdogMaxRetries }
            .stateIn(scope, WhileSubscribed, StreamingSettings().watchdogMaxRetries)
    override val watchdogCheckIntervalSeconds: StateFlow<Int> =
        streaming.map { it.watchdogCheckIntervalSeconds }
            .stateIn(scope, WhileSubscribed, StreamingSettings().watchdogCheckIntervalSeconds)
    override val backupEnabled: StateFlow<Boolean> =
        streaming.map { it.backupEnabled }.stateIn(scope, WhileSubscribed, false)
    override val backupWifiOnly: StateFlow<Boolean> =
        streaming.map { it.backupWifiOnly }.stateIn(scope, WhileSubscribed, true)
    override val backupWebdavUrl: StateFlow<String> =
        streaming.map { it.backupWebdavUrl }.stateIn(scope, WhileSubscribed, "")
    override val backupWebdavUsername: StateFlow<String> =
        streaming.map { it.backupWebdavUsername }.stateIn(scope, WhileSubscribed, "")
    override val httpsEnabled: StateFlow<Boolean> =
        streaming.map { it.httpsEnabled }.stateIn(scope, WhileSubscribed, false)
    override val keepAwakeEnabled: StateFlow<Boolean> =
        streaming.map { it.keepAwakeEnabled }.stateIn(scope, WhileSubscribed, true)

    // No carrier on the settings wire (a phone-local boot intent the device's
    // own store owns): pinned false, the write a no-op (row capability-hidden).
    private val _resumeStreamsOnBoot = MutableStateFlow(false)
    override val resumeStreamsOnBoot: StateFlow<Boolean> = _resumeStreamsOnBoot

    // Same no-carrier family: the phone-local FSI resume lever.
    override val fsiCameraResume: StateFlow<Boolean> = MutableStateFlow(false)

    /**
     * The redacted credential read, derived from the document flow so
     * collecting it drives the beat (the auth fetch rides the same tick) —
     * and so a fresh config read re-emits without an extra channel.
     */
    override val authSettings: StateFlow<StreamAuthSettings> =
        merge(doc, _auth)
            .onStart { emit(_auth.value) }
            .map { _auth.value }
            .stateIn(scope, WhileSubscribed, _auth.value)

    // ── Viewer-local lanes (the injected local store — never the document) ──

    override val showPreview: StateFlow<Boolean> = localSettings.showPreview
    override val dynamicThemingEnabled: StateFlow<Boolean> = localSettings.dynamicThemingEnabled
    override val gridStyle: StateFlow<GridStyle> = localSettings.gridStyle
    override val selfTimer: StateFlow<SelfTimerMode> = localSettings.selfTimer
    override val spiritLevelEnabled: StateFlow<Boolean> = localSettings.spiritLevelEnabled
    override val histogramEnabled: StateFlow<Boolean> = localSettings.histogramEnabled
    override val zebrasEnabled: StateFlow<Boolean> = localSettings.zebrasEnabled
    override val peakingEnabled: StateFlow<Boolean> = localSettings.peakingEnabled

    // ── Phone-only facts (honest absence; rows are capability-hidden) ──

    override val isIgnoringBatteryOptimizations: androidx.compose.runtime.State<Boolean> =
        androidx.compose.runtime.mutableStateOf(false)

    override fun refreshBatteryOptimizationStatus() = Unit

    override fun requestIgnoreBatteryOptimization(activity: Activity) = Unit

    // ── The write funnels ──

    /** Serializes the GET-merge-PUT chains (one write round trip at a time). */
    private val writeMutex = Mutex()

    /**
     * The write lane, opened to the console: the dashboard's quick-edit
     * ([DeviceConsoleViewModel.putSettingsSection]) runs its own
     * GET-merge-PUT against the same document, and both sides PUT whole
     * sections — the server's key-level merge cannot rescue two interleaved
     * chains from a lost update, so the console's edit must serialize with
     * [putStreaming]/[putCamera]/[resetToDefaults]/[putAuthConfig]/
     * [putAuthPassword] (and the poll) through THIS mutex, not a copy of it.
     * Not reentrant (kotlinx Mutex): a caller already inside a write path
     * must never re-enter — the gated block dials the client directly.
     */
    suspend fun <T> withSettingsWriteLock(block: suspend () -> T): T =
        writeMutex.withLock { block() }

    /**
     * The writes' tail — the last submitted edit's [Job]. kotlinx Mutex
     * serializes the round trips but does not ORDER them: two edits launched
     * in quick succession on the multi-threaded app scope can acquire
     * [writeMutex] in either order and save the FIRST submission. Every
     * write funnel launches through [launchWrite], whose chain pins
     * execution to submission order — the last submitted edit is the last
     * GET-merge-PUT, so the saved setting is its — while [writeMutex] keeps
     * serializing against the poll and the console's own
     * [withSettingsWriteLock] callers.
     */
    @Volatile
    private var writeTail: Job = Job().apply { complete() }

    /**
     * One ordered write: joins [writeTail] before taking [writeMutex] (a
     * failed predecessor releases its successor — join does not propagate
     * failure), so funnel submissions run in submission order.
     */
    private fun launchWrite(block: suspend () -> Unit) {
        val previous = writeTail
        val next = scope.launch {
            previous.join()
            writeMutex.withLock { block() }
        }
        writeTail = next
    }

    /**
     * One streaming-section write: the edit rides the optimistic overlay
     * ([pendingStreamingEdits] — the confirmed document stays clean), then
     * the serialized, submission-ordered GET-merge-PUT lands its merged
     * answer as the confirmed document. On failure the write's outcome on
     * the device is UNKNOWN — the edit leaves the overlay (later pending
     * edits stay rendered) and is never written into the confirmed
     * document; only a successful read — this path's immediate recovery
     * read or the beat's next one — re-publishes what the device actually
     * kept.
     */
    private fun putStreaming(transform: (StreamingSettings) -> StreamingSettings) {
        val client = api ?: return
        if (!canEdit) return
        pendingStreamingEdits.add(transform)
        pendingEditsRevision.value += 1
        launchWrite {
            try {
                val fresh = client.getSettings()
                val merged = transform(fresh.streaming)
                client.putSettings(SettingsUpdate(streaming = merged))
                // Confirmed: the write's merged answer, with the submitted
                // write-only secrets redacted back to the read-side values
                // before they are published (the GETs' blanks — the embedded
                // stream keys and the WHIP token / WebDAV password never echo
                // into the confirmed state either; the submitted values rode
                // the request above).
                _doc.value = fresh.copy(
                    streaming = merged.copy(
                        rtmpUrl = fresh.streaming.rtmpUrl,
                        srtUrl = fresh.streaming.srtUrl,
                        whipToken = fresh.streaming.whipToken,
                        backupWebdavPassword = fresh.streaming.backupWebdavPassword,
                    ),
                )
                pendingStreamingEdits.remove(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The write did not confirm — it may or may not have been
                // accepted. Drop ONLY this edit from the optimistic
                // overlay (later pending edits stay), then re-read so the
                // device's truth re-lands; if that read fails too, the
                // beat keeps polling until a successful one clears the
                // uncertainty.
                pendingStreamingEdits.remove(transform)
                pendingEditsRevision.value += 1
                readDoc(client)
            }
        }
    }

    /**
     * One camera-section write through the mapper's patch discipline: a
     * fresh GET, the transform onto the fresh snapshot, the wholesale
     * camera-section PUT whose section is the fresh wire document with only
     * the edit applied ([RemoteSettingsMapper.toPatch]'s fresh base —
     * unedited wire values and the device's zoom ride the fresh read). A
     * zoom-only change rides `POST /api/camera/zoom` instead — the endpoint
     * that owns live zoom and persists its accepted value.
     */
    private fun putCamera(transform: (CameraSettings) -> CameraSettings) {
        val client = api ?: return
        if (!canEdit) return
        launchWrite {
            try {
                val fresh = client.getSettings()
                val current = RemoteSettingsMapper.toCameraSettings(fresh.camera)
                val next = transform(current)
                val patch = RemoteSettingsMapper.toPatch(current, next, fresh.camera)
                if (patch != null) {
                    client.putSettings(patch)
                    _doc.value = fresh.copy(camera = requireNotNull(patch.camera))
                } else if (current.zoomRatio != next.zoomRatio) {
                    client.setZoom(next.zoomRatio.toDouble())
                    readDoc(client)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The beat reconciles with the device's truth.
            }
        }
    }

    // ── Device-side writes ──

    override fun updateQuickSetting(type: QuickSettingType, value: Any) {
        val editorValue = QuickSettingCatalog.editorValueFor(type, value) ?: return
        putCamera { current -> QuickSettingCatalog.descriptorFor(type).write(current, editorValue) }
    }

    override fun updateFocusDistance(distance: Float?) {
        putCamera { it.copy(focusDistance = distance) }
    }

    override fun updateColorTemperature(temp: Int?) {
        putCamera { it.copy(colorTemperature = temp) }
    }

    override fun updateSceneMode(mode: String) {
        putCamera { it.copy(sceneMode = CameraSettingsEditor.parseSceneMode(mode)) }
    }

    override fun resetToDefaults() {
        // The camera section resets to the app defaults wholesale (the same
        // scope the local Reset has — camera settings, not the streaming
        // section); streaming stays untouched (a null section on the wire).
        val client = api ?: return
        if (!canEdit) return
        launchWrite {
            try {
                val defaults = RemoteSettingsMapper.toRemoteSection(CameraSettings())
                client.putSettings(SettingsUpdate(camera = defaults))
                readDoc(client)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    override fun updateStreamingPort(port: Int) = putStreaming { it.copy(port = port) }
    override fun updateWebStreamingEnabled(enabled: Boolean) =
        putStreaming { it.copy(webStreamingEnabled = enabled) }

    override fun updateJpegQuality(quality: Int) = putStreaming { it.copy(jpegQuality = quality) }
    override fun updateAdaptiveBitrateEnabled(enabled: Boolean) =
        putStreaming { it.copy(adaptiveBitrateEnabled = enabled) }

    override fun updateAdaptiveEncodedBitrateEnabled(enabled: Boolean) =
        putStreaming { it.copy(adaptiveEncodedBitrateEnabled = enabled) }

    override fun updateRtspSubStreamEnabled(enabled: Boolean) =
        putStreaming { it.copy(rtspSubStreamEnabled = enabled) }

    override fun updateHlsDvrSegments(segments: Int) =
        putStreaming { it.copy(hlsDvrSegments = segments) }

    override fun updateEcoIdleFpsEnabled(enabled: Boolean) =
        putStreaming { it.copy(ecoIdleFpsEnabled = enabled) }

    override fun updateMdnsEnabled(enabled: Boolean) = putStreaming { it.copy(mdnsEnabled = enabled) }
    override fun updateRtspEnabled(enabled: Boolean) = putStreaming { it.copy(rtspEnabled = enabled) }
    override fun updateRtspPort(port: Int) = putStreaming { it.copy(rtspPort = port) }

    override fun updateRtspBasicAuthCompat(enabled: Boolean) =
        putStreaming { it.copy(rtspBasicAuthCompat = enabled) }

    override fun updateRtspInputFormat(name: String) = putStreaming {
        it.copy(rtspInputFormat = parseEnum(name, RtspInputFormat.AUTO).name)
    }

    override fun updateRtmpEnabled(enabled: Boolean) = putStreaming { it.copy(rtmpEnabled = enabled) }
    override fun updateRtmpUrl(url: String) = putStreaming { it.copy(rtmpUrl = url) }
    override fun updateWhipEnabled(enabled: Boolean) = putStreaming { it.copy(whipEnabled = enabled) }
    override fun updateWhipUrl(url: String) = putStreaming { it.copy(whipUrl = url) }

    /** The empty-keeps-stored contract: an empty token simply never PUTs. */
    override fun updateWhipToken(token: String) {
        if (SecretUpdate.overwrites(token)) putStreaming { it.copy(whipToken = token) }
    }

    override fun updateWhipStunServer(server: String) =
        putStreaming { it.copy(whipStunServer = server) }

    override fun updateSrtEnabled(enabled: Boolean) = putStreaming { it.copy(srtEnabled = enabled) }
    override fun updateSrtUrl(url: String) = putStreaming { it.copy(srtUrl = url) }

    override fun updateStreamAudioEnabled(enabled: Boolean) =
        putStreaming { it.copy(streamAudioEnabled = enabled) }

    override fun updateStreamAudioBitrateKbps(bitrateKbps: Int) =
        putStreaming { it.copy(streamAudioBitrateKbps = bitrateKbps) }

    override fun updateStreamAudioChannels(channels: Int) =
        putStreaming { it.copy(streamAudioChannels = channels) }

    override fun updateStreamAudioEchoCancellation(enabled: Boolean) =
        putStreaming { it.copy(streamAudioEchoCancellation = enabled) }

    override fun updateRecordingAudioEnabled(enabled: Boolean) =
        putStreaming { it.copy(recordingAudioEnabled = enabled) }

    override fun updateAudioDeviceId(id: String) = putStreaming { it.copy(audioDeviceId = id) }

    override fun updateMotionDetectionEnabled(enabled: Boolean) =
        putStreaming { it.copy(motionDetectionEnabled = enabled) }

    override fun updateMotionSensitivity(percent: Int) =
        putStreaming { it.copy(motionSensitivityPercent = percent) }

    override fun updateMotionRecordingEnabled(enabled: Boolean) =
        putStreaming { it.copy(motionRecordingEnabled = enabled) }

    override fun updateMotionPostRollSeconds(seconds: Int) =
        putStreaming { it.copy(motionPostRollSeconds = seconds) }

    override fun updateMotionArmScheduleEnabled(enabled: Boolean) =
        putStreaming { it.copy(motionArmScheduleEnabled = enabled) }

    override fun updateMotionArmStartMinute(minute: Int) =
        putStreaming { it.copy(motionArmStartMinute = minute) }

    override fun updateMotionArmEndMinute(minute: Int) =
        putStreaming { it.copy(motionArmEndMinute = minute) }

    override fun updateMotionArmDaysMask(mask: Int) =
        putStreaming { it.copy(motionArmDaysMask = mask) }

    override fun updateMotionCooldownSeconds(seconds: Int) =
        putStreaming { it.copy(motionCooldownSeconds = seconds) }

    override fun updateSoundDetectionEnabled(enabled: Boolean) =
        putStreaming { it.copy(soundDetectionEnabled = enabled) }

    override fun updateSoundThresholdPercent(percent: Int) =
        putStreaming { it.copy(soundThresholdPercent = percent) }

    override fun updateSoundAdaptiveNoiseFloor(enabled: Boolean) =
        putStreaming { it.copy(soundAdaptiveNoiseFloor = enabled) }

    override fun updateSoundRecordingEnabled(enabled: Boolean) =
        putStreaming { it.copy(soundRecordingEnabled = enabled) }

    override fun updateSoundCooldownSeconds(seconds: Int) =
        putStreaming { it.copy(soundCooldownSeconds = seconds) }

    override fun updateSoundClassificationEnabled(enabled: Boolean) =
        putStreaming { it.copy(soundClassificationEnabled = enabled) }

    override fun updateSoundClassificationConfidencePercent(percent: Int) =
        putStreaming { it.copy(soundClassificationConfidencePercent = percent) }

    override fun updateSoundClassificationAllowedClasses(classes: Set<String>) =
        putStreaming { it.copy(soundClassificationAllowedClasses = classes.toList()) }

    override fun updateSoundTriggerEnabled(enabled: Boolean) =
        putStreaming { it.copy(soundTriggerEnabled = enabled) }

    override fun updateSoundTriggerClasses(classes: Set<String>) =
        putStreaming { it.copy(soundTriggerClasses = classes.toList()) }

    override fun updateMlDetectionEnabled(enabled: Boolean) =
        putStreaming { it.copy(mlDetectionEnabled = enabled) }

    override fun updateMlMinScorePercent(percent: Int) =
        putStreaming { it.copy(mlMinScorePercent = percent) }

    override fun updateMlIncludePerson(enabled: Boolean) =
        putStreaming { it.copy(mlIncludePerson = enabled) }

    override fun updateMlIncludePets(enabled: Boolean) =
        putStreaming { it.copy(mlIncludePets = enabled) }

    override fun updateMlIncludeVehicles(enabled: Boolean) =
        putStreaming { it.copy(mlIncludeVehicles = enabled) }

    override fun updateTamperDetectionEnabled(enabled: Boolean) =
        putStreaming { it.copy(tamperDetectionEnabled = enabled) }

    override fun updateDetectionNotificationsEnabled(enabled: Boolean) =
        putStreaming { it.copy(detectionNotificationsEnabled = enabled) }

    override fun updateAlertQuietHoursEnabled(enabled: Boolean) =
        putStreaming { it.copy(alertQuietHoursEnabled = enabled) }

    override fun updateAlertQuietHoursStartMinute(minute: Int) =
        putStreaming { it.copy(alertQuietHoursStartMinute = minute) }

    override fun updateAlertQuietHoursEndMinute(minute: Int) =
        putStreaming { it.copy(alertQuietHoursEndMinute = minute) }

    override fun updatePushEnabled(enabled: Boolean) = putStreaming { it.copy(pushEnabled = enabled) }
    override fun updateUnifiedPushEnabled(enabled: Boolean) =
        putStreaming { it.copy(unifiedPushEnabled = enabled) }

    // No wire carrier: a no-op (the field is capability-hidden remotely).
    override fun updatePushVapidSubject(subject: String) = Unit

    override fun downloadDetectionModel() {
        val client = api ?: return
        if (!canEdit) return
        scope.launch {
            try {
                client.downloadDetectionModel()
                fetchDoc() // the model lifecycle is response-only doc state
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    override fun downloadAudioModel() {
        val client = api ?: return
        if (!canEdit) return
        scope.launch {
            try {
                client.downloadAudioModel()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    override fun updateStorageQuotaMb(quotaMb: Int) =
        putStreaming { it.copy(storageQuotaMb = quotaMb) }

    override fun updateMediaEncryptionEnabled(enabled: Boolean) =
        putStreaming { it.copy(mediaEncryptionEnabled = enabled) }

    override fun updateContinuousRecording(enabled: Boolean) =
        putStreaming { it.copy(continuousRecording = enabled) }

    override fun updateContinuousSegmentMinutes(minutes: Int) =
        putStreaming { it.copy(continuousSegmentMinutes = minutes) }

    override fun updateWatchdogEnabled(enabled: Boolean) =
        putStreaming { it.copy(watchdogEnabled = enabled) }

    override fun updateWatchdogMaxRetries(retries: Int) =
        putStreaming { it.copy(watchdogMaxRetries = retries) }

    override fun updateWatchdogCheckIntervalSeconds(seconds: Int) =
        putStreaming { it.copy(watchdogCheckIntervalSeconds = seconds) }

    override fun updateBackupEnabled(enabled: Boolean) =
        putStreaming { it.copy(backupEnabled = enabled) }

    override fun updateBackupWifiOnly(wifiOnly: Boolean) =
        putStreaming { it.copy(backupWifiOnly = wifiOnly) }

    override fun updateBackupWebdavUrl(url: String) =
        putStreaming { it.copy(backupWebdavUrl = url) }

    override fun updateBackupWebdavUsername(username: String) =
        putStreaming { it.copy(backupWebdavUsername = username) }

    override fun updateBackupWebdavPassword(password: String) {
        if (SecretUpdate.overwrites(password)) putStreaming { it.copy(backupWebdavPassword = password) }
    }

    override fun updateHttpsEnabled(enabled: Boolean) = putStreaming { it.copy(httpsEnabled = enabled) }
    override fun updateKeepAwakeEnabled(enabled: Boolean) =
        putStreaming { it.copy(keepAwakeEnabled = enabled) }

    // No wire carrier: no-ops (the rows are capability-hidden remotely).
    override fun updateResumeStreamsOnBoot(enabled: Boolean) = Unit
    override fun updateFsiCameraResume(enabled: Boolean) = Unit

    // ── The device's web-auth credentials (the /api/auth/config merge) ──

    override fun updateAuthEnabled(enabled: Boolean) {
        putAuthConfig { it.copy(enabled = enabled) }
    }

    override fun updateAuthUsername(username: String) {
        putAuthConfig { it.copy(username = username) }
    }

    override fun updateAuthPassword(password: String) {
        // A rotation only: the plaintext rides once and is never echoed back.
        putAuthPassword(password)
    }

    /**
     * The `/api/auth/config` GET-merge-PUT: fetch the redacted config, apply
     * the transform, PUT with the viewer pair PRESERVED (the typed client's
     * contract — an omitted viewer section DISABLES it server-side, so a
     * change touching only the admin half must echo the viewer fields).
     */
    private fun putAuthConfig(transform: (AuthConfigInfo) -> AuthConfigInfo) {
        val client = api ?: return
        if (!canEdit) return
        launchWrite {
            try {
                val current = client.authConfig()
                val next = transform(current)
                client.putAuthConfig(
                    enabled = next.enabled,
                    username = next.username.orEmpty(),
                    password = null, // redacted read: keep the stored hash
                    viewerEnabled = next.viewerEnabled ?: false,
                    viewerUsername = next.viewerUsername,
                    viewerPassword = null, // keep
                )
                _auth.value = toAuthSettings(next)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The beat reconciles with the device's redacted truth.
            }
        }
    }

    private fun putAuthPassword(password: String) {
        val client = api ?: return
        if (!canEdit) return
        launchWrite {
            try {
                val current = client.authConfig()
                client.putAuthConfig(
                    enabled = current.enabled,
                    username = current.username.orEmpty(),
                    password = password, // the one rotation carrier
                    viewerEnabled = current.viewerEnabled ?: false,
                    viewerUsername = current.viewerUsername,
                    viewerPassword = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // ── Viewer-local writes (the injected store — never the wire, never gated) ──

    override fun updateShowPreview(show: Boolean) {
        scope.launch { localSettings.saveShowPreview(show) }
    }

    override fun updateDynamicThemingEnabled(enabled: Boolean) {
        scope.launch { localSettings.saveDynamicThemingEnabled(enabled) }
    }

    override fun updateGridStyle(name: String) {
        scope.launch {
            localSettings.saveGridStyle(parseEnum(name, GridStyle.OFF))
        }
    }

    override fun updateSelfTimer(name: String) {
        scope.launch {
            localSettings.saveSelfTimer(parseEnum(name, SelfTimerMode.OFF))
        }
    }

    override fun updateSpiritLevelEnabled(enabled: Boolean) {
        scope.launch { localSettings.saveSpiritLevelEnabled(enabled) }
    }

    override fun updateHistogramEnabled(enabled: Boolean) {
        scope.launch { localSettings.saveHistogramEnabled(enabled) }
    }

    override fun updateZebrasEnabled(enabled: Boolean) {
        scope.launch { localSettings.saveZebrasEnabled(enabled) }
    }

    override fun updatePeakingEnabled(enabled: Boolean) {
        scope.launch { localSettings.savePeakingEnabled(enabled) }
    }

    // ── Wire-lacks-a-carrier camera writes (the photo-capture knobs) ──

    override fun updatePhotoJpegQuality(quality: Int) = Unit
    override fun updatePhotoMaximizeQuality(enabled: Boolean) = Unit
    override fun updateRawCaptureEnabled(enabled: Boolean) = Unit
    override fun updatePhotoAspectRatio(aspect: PhotoAspectRatio) = Unit
    override fun updateGeotagEnabled(enabled: Boolean) = Unit

    companion object {

        /** The subscribe grace that parks the beat when no screen shows. */
        private const val SUBSCRIBE_GRACE_MS = 5_000L

        /**
         * The sharing start for every document-derived flow. NO stop grace
         * of its own: the beat's own WhileSubscribed is the poll's ONLY stop
         * grace, so these projections unsubscribe from the beat the moment
         * their last collector leaves — the poll parks on that single grace
         * (a chain of per-flow graces would re-arm it flow by flow and keep
         * the poll running long after the screen closed), while a quick
         * reopen finds the beat still alive and reuses it without a restart
         * (the cached document/auth StateFlows republish instantly).
         */
        private val WhileSubscribed = SharingStarted.WhileSubscribed()

        /**
         * The redacted config read → the screen's [StreamAuthSettings]. The
         * password hash NEVER crosses back (server-redacted), so the mapped
         * settings carry an empty hash and a blank RTSP HA1 — the honest
         * "unknown" that still renders the enabled state and the username.
         */
        fun toAuthSettings(config: AuthConfigInfo): StreamAuthSettings = StreamAuthSettings(
            enabled = config.enabled,
            username = config.username.orEmpty(),
            passwordHash = "",
            rtspDigestHa1 = "",
            viewerUsername = config.viewerUsername,
            viewerPasswordHash = null,
        )
    }
}

/**
 * The settings document's pure wire→model pieces that are not already
 * [RemoteSettingsMapper]'s (which owns the camera section) — JVM-pure so the
 * mapping tests can pin them without a client or a scope.
 */
object RemoteSettingsMap {

    /**
     * The document's response-only model-lifecycle fields → the screen's
     * [DetectionModelStore.State] echo. `ready` maps to a marker file whose
     * path is never meaningful on this side — the wire carries no path, and
     * the settings screen renders the Ready verdict, not the file.
     */
    fun toDetectionModelState(state: String, progress: Double, error: String): DetectionModelStore.State =
        when (state) {
            "downloading" -> DetectionModelStore.State.Downloading(progress.toFloat())
            "ready" -> DetectionModelStore.State.Ready(File(""))
            "failed" -> DetectionModelStore.State.Failed(error)
            else -> DetectionModelStore.State.NotDownloaded
        }
}
