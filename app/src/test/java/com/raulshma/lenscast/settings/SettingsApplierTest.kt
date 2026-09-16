package com.raulshma.lenscast.settings

import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.MotionZone
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.camera.model.PhotoAspectRatio
import com.raulshma.lenscast.camera.model.PhotoCapturePlan
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.core.mqtt.MqttTopics
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.onvif.OnvifServer
import com.raulshma.lenscast.streaming.rtmp.RtmpStatus
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.raulshma.lenscast.streaming.srt.SrtStatus
import com.raulshma.lenscast.streaming.whip.WhipStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Event-sequence pass over the [SettingsApplier] — the single owner of
 * "persisted settings → runtime" application. The store is mocked as one
 * [MutableStateFlow] per setting, every runtime collaborator is a relaxed
 * mockk, and each test settles the initial fan-out before mutating a flow,
 * then pins the exact runtime calls the change must (and must not) produce —
 * including the two documented asymmetries: the camera-settings flow's
 * `distinctUntilChanged` frame-rate fan-out and the MQTT combine's collect-time
 * enabled read. Group collectors (audio, RTSP, watchdog, motion, sound,
 * discovery) re-apply their whole group per emission, so a single field's
 * change is also a whole-group re-apply — the totals below pin that.
 */
class SettingsApplierTest {

    // ── store flows (one MutableStateFlow per setting the applier reads) ──

    private val cameraSettings = MutableStateFlow(CameraSettings())
    private val streamingPort = MutableStateFlow(8080)
    private val httpsEnabled = MutableStateFlow(false)
    private val streamAudioEnabled = MutableStateFlow(true)
    private val streamAudioBitrateKbps = MutableStateFlow(128)
    private val streamAudioChannels = MutableStateFlow(1)
    private val streamAudioEchoCancellation = MutableStateFlow(true)
    private val jpegQuality = MutableStateFlow(70)
    private val overlaySettings = MutableStateFlow(OverlaySettings())
    private val rtspEnabled = MutableStateFlow(false)
    private val rtspPort = MutableStateFlow(8554)
    private val rtspInputFormat = MutableStateFlow(RtspInputFormat.AUTO)
    private val rtspResolution = MutableStateFlow(RtspResolution.P720)
    private val rtspVideoCodec = MutableStateFlow(RtspVideoCodec.H264)
    private val rtspSubStreamEnabled = MutableStateFlow(false)
    private val rtmpEnabled = MutableStateFlow(false)
    private val rtmpUrl = MutableStateFlow("")
    private val whipEnabled = MutableStateFlow(false)
    private val whipUrl = MutableStateFlow("")
    private val whipToken = MutableStateFlow("")
    private val whipStunServer = MutableStateFlow("")
    private val srtEnabled = MutableStateFlow(false)
    private val srtUrl = MutableStateFlow("")
    private val photoJpegQuality = MutableStateFlow(70)
    private val photoMaximizeQuality = MutableStateFlow(false)
    private val rawCaptureEnabled = MutableStateFlow(false)
    private val photoAspectRatio = MutableStateFlow(PhotoAspectRatio.R16_9)
    private val webStreamingEnabled = MutableStateFlow(true)
    private val mdnsEnabled = MutableStateFlow(true)
    private val adaptiveBitrateEnabled = MutableStateFlow(false)
    private val encodedAdaptiveBitrateEnabled = MutableStateFlow(false)
    private val hlsDvrSegments = MutableStateFlow(12)
    private val authSettings = MutableStateFlow(StreamAuthSettings())
    private val motionDetectionEnabled = MutableStateFlow(false)
    private val motionSensitivity = MutableStateFlow(50)
    private val motionZones = MutableStateFlow(emptyList<MotionZone>())
    private val motionCooldownSeconds = MutableStateFlow(10)
    private val audioDeviceId = MutableStateFlow("")
    private val soundDetectionEnabled = MutableStateFlow(false)
    private val soundThresholdPercent = MutableStateFlow(20)
    private val soundCooldownSeconds = MutableStateFlow(60)
    private val soundAdaptiveNoiseFloor = MutableStateFlow(false)
    private val watchdogEnabled = MutableStateFlow(false)
    private val watchdogMaxRetries = MutableStateFlow(3)
    private val watchdogCheckIntervalSeconds = MutableStateFlow(5)
    private val mqttEnabled = MutableStateFlow(false)
    private val mqttBrokerHost = MutableStateFlow("")
    private val mqttBrokerPort = MutableStateFlow(1883)
    private val mqttUsername = MutableStateFlow("")
    private val mqttPassword = MutableStateFlow("")
    private val mqttTls = MutableStateFlow(false)
    private val mqttDiscoveryPrefix = MutableStateFlow("lenscast")
    private val mqttTelemetryEnabled = MutableStateFlow(false)
    private val onvifEnabled = MutableStateFlow(false)
    private val ecoIdleFpsEnabled = MutableStateFlow(false)

    // Manager-side flows the MQTT stream-state publisher reads.
    private val isWebStreamingActive = MutableStateFlow(false)
    private val isRtspRunning = MutableStateFlow(false)
    private val rtmpStatus = MutableStateFlow<RtmpStatus>(RtmpStatus.Idle)
    private val whipStatus = MutableStateFlow<WhipStatus>(WhipStatus.Idle)
    private val srtStatus = MutableStateFlow<SrtStatus>(SrtStatus.Idle)
    private val clientCount = MutableStateFlow(0)

    private val settingsDataStore: SettingsDataStore = mockk()
    private val cameraService: CameraService = mockk(relaxed = true)
    private val streamingManager: StreamingManager = mockk(relaxed = true)
    private val streamWatchdog: StreamWatchdog = mockk(relaxed = true)
    private val mqttAlertPublisher: MqttAlertPublisher = mockk(relaxed = true)
    private val onvifServer: OnvifServer = mockk(relaxed = true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        coEvery { cameraService.applySettings(any()) } returns Unit

        every { streamingManager.isWebStreamingActive } returns isWebStreamingActive
        every { streamingManager.isRtspRunning } returns isRtspRunning
        every { streamingManager.rtmpStatus } returns rtmpStatus
        every { streamingManager.whipStatus } returns whipStatus
        every { streamingManager.srtStatus } returns srtStatus
        every { streamingManager.clientCount } returns clientCount
        every { streamingManager.getRtspClients() } returns emptyList()

        every { settingsDataStore.settings } returns cameraSettings
        every { settingsDataStore.streamingPort } returns streamingPort
        every { settingsDataStore.httpsEnabled } returns httpsEnabled
        every { settingsDataStore.streamAudioEnabled } returns streamAudioEnabled
        every { settingsDataStore.streamAudioBitrateKbps } returns streamAudioBitrateKbps
        every { settingsDataStore.streamAudioChannels } returns streamAudioChannels
        every { settingsDataStore.streamAudioEchoCancellation } returns streamAudioEchoCancellation
        every { settingsDataStore.jpegQuality } returns jpegQuality
        every { settingsDataStore.overlaySettings } returns overlaySettings
        every { settingsDataStore.rtspEnabled } returns rtspEnabled
        every { settingsDataStore.rtspPort } returns rtspPort
        every { settingsDataStore.rtspInputFormat } returns rtspInputFormat
        every { settingsDataStore.rtspResolution } returns rtspResolution
        every { settingsDataStore.rtspVideoCodec } returns rtspVideoCodec
        every { settingsDataStore.rtspSubStreamEnabled } returns rtspSubStreamEnabled
        every { settingsDataStore.rtmpEnabled } returns rtmpEnabled
        every { settingsDataStore.rtmpUrl } returns rtmpUrl
        every { settingsDataStore.whipEnabled } returns whipEnabled
        every { settingsDataStore.whipUrl } returns whipUrl
        every { settingsDataStore.whipToken } returns whipToken
        every { settingsDataStore.whipStunServer } returns whipStunServer
        every { settingsDataStore.srtEnabled } returns srtEnabled
        every { settingsDataStore.srtUrl } returns srtUrl
        every { settingsDataStore.photoJpegQuality } returns photoJpegQuality
        every { settingsDataStore.photoMaximizeQuality } returns photoMaximizeQuality
        every { settingsDataStore.rawCaptureEnabled } returns rawCaptureEnabled
        every { settingsDataStore.photoAspectRatio } returns photoAspectRatio
        every { settingsDataStore.webStreamingEnabled } returns webStreamingEnabled
        every { settingsDataStore.mdnsEnabled } returns mdnsEnabled
        every { settingsDataStore.adaptiveBitrateEnabled } returns adaptiveBitrateEnabled
        every { settingsDataStore.encodedAdaptiveBitrateEnabled } returns encodedAdaptiveBitrateEnabled
        every { settingsDataStore.hlsDvrSegments } returns hlsDvrSegments
        every { settingsDataStore.authSettings } returns authSettings
        every { settingsDataStore.motionDetectionEnabled } returns motionDetectionEnabled
        every { settingsDataStore.motionSensitivity } returns motionSensitivity
        every { settingsDataStore.motionZones } returns motionZones
        every { settingsDataStore.motionCooldownSeconds } returns motionCooldownSeconds
        every { settingsDataStore.audioDeviceId } returns audioDeviceId
        every { settingsDataStore.soundDetectionEnabled } returns soundDetectionEnabled
        every { settingsDataStore.soundThresholdPercent } returns soundThresholdPercent
        every { settingsDataStore.soundCooldownSeconds } returns soundCooldownSeconds
        every { settingsDataStore.soundAdaptiveNoiseFloor } returns soundAdaptiveNoiseFloor
        every { settingsDataStore.watchdogEnabled } returns watchdogEnabled
        every { settingsDataStore.watchdogMaxRetries } returns watchdogMaxRetries
        every { settingsDataStore.watchdogCheckIntervalSeconds } returns watchdogCheckIntervalSeconds
        every { settingsDataStore.mqttEnabled } returns mqttEnabled
        every { settingsDataStore.mqttBrokerHost } returns mqttBrokerHost
        every { settingsDataStore.mqttBrokerPort } returns mqttBrokerPort
        every { settingsDataStore.mqttUsername } returns mqttUsername
        every { settingsDataStore.mqttPassword } returns mqttPassword
        every { settingsDataStore.mqttTls } returns mqttTls
        every { settingsDataStore.mqttDiscoveryPrefix } returns mqttDiscoveryPrefix
        every { settingsDataStore.mqttTelemetryEnabled } returns mqttTelemetryEnabled
        every { settingsDataStore.onvifEnabled } returns onvifEnabled
        every { settingsDataStore.ecoIdleFpsEnabled } returns ecoIdleFpsEnabled

        SettingsApplier(
            settingsDataStore = settingsDataStore,
            cameraService = cameraService,
            streamingManager = streamingManager,
            streamWatchdog = streamWatchdog,
            mqttAlertPublisher = mqttAlertPublisher,
            onvifServer = onvifServer,
        ).start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ── helpers ──

    /** Polls [block] until it passes (mockk verifies included) or times out. */
    private fun eventually(timeoutMs: Long = 5_000, block: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                block()
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(20)
            }
        }
        throw AssertionError("Condition not reached within ${timeoutMs}ms", last)
    }

    /** Lets every collector drain the current emissions before a negative assertion. */
    private fun settle() {
        Thread.sleep(200)
    }

    // ── camera settings: whole-snapshot re-apply + frame-rate asymmetry ──

    @Test
    fun `camera settings change re-applies the whole snapshot to the camera service`() {
        eventually {
            coVerify(exactly = 1) { cameraService.applySettings(CameraSettings()) }
        }
        cameraSettings.value = CameraSettings(zoomRatio = 2.5f)
        eventually {
            coVerify(exactly = 1) { cameraService.applySettings(CameraSettings(zoomRatio = 2.5f)) }
            coVerify(exactly = 2) { cameraService.applySettings(any()) }
        }
    }

    @Test
    fun `frame rate change fans out once and an unrelated camera change does not re-fire it`() {
        eventually {
            verify(exactly = 1) { streamingManager.setFrameRate(24) }
            coVerify(exactly = 1) { cameraService.applySettings(any()) }
        }
        cameraSettings.value = CameraSettings(frameRate = 30)
        eventually {
            verify(exactly = 1) { streamingManager.setFrameRate(30) }
            coVerify(exactly = 2) { cameraService.applySettings(any()) }
        }
        // Same frame rate, different camera field: the composite descriptor
        // re-emits and applySettings re-applies (idempotent by contract), but
        // the distinctUntilChanged keeps the runtime frame-rate apply
        // single-shot per rate.
        cameraSettings.value = CameraSettings(frameRate = 30, torchEnabled = true)
        eventually {
            coVerify(exactly = 3) { cameraService.applySettings(any()) }
            coVerify(exactly = 1) { cameraService.applySettings(CameraSettings(frameRate = 30, torchEnabled = true)) }
        }
        settle()
        verify(exactly = 1) { streamingManager.setFrameRate(30) }
    }

    // ── web server port + TLS ──

    @Test
    fun `port change applies once and re-runs ensureServerRunning and an identical value does not re-apply`() {
        eventually {
            verify(exactly = 1) { streamingManager.setPort(8080) }
            verify(exactly = 1) { streamingManager.ensureServerRunning() }
        }
        streamingPort.value = 9123
        eventually {
            verify(exactly = 1) { streamingManager.setPort(9123) }
            verify(exactly = 2) { streamingManager.ensureServerRunning() }
        }
        // StateFlow conflation: re-writing the same persisted value emits
        // nothing, so the runtime sees no second apply.
        streamingPort.value = 9123
        settle()
        verify(exactly = 1) { streamingManager.setPort(9123) }
        verify(exactly = 2) { streamingManager.setPort(any()) }
    }

    @Test
    fun `tls toggle rides the manager's stop-recreate-start seam`() {
        eventually {
            verify(exactly = 1) { streamingManager.setTlsEnabled(false) }
        }
        httpsEnabled.value = true
        eventually {
            verify(exactly = 1) { streamingManager.setTlsEnabled(true) }
            verify(exactly = 2) { streamingManager.setTlsEnabled(any()) }
        }
    }

    // ── audio coalescing ──

    @Test
    fun `one audio field change lands as a single coalesced setAudioConfig`() {
        eventually {
            verify(exactly = 1) { streamingManager.setAudioConfig(true, 128, 1, true) }
        }
        streamAudioBitrateKbps.value = 96
        eventually {
            verify(exactly = 1) { streamingManager.setAudioConfig(enabled = true, bitrateKbps = 96, channels = 1, echoCancellation = true) }
            verify(exactly = 2) { streamingManager.setAudioConfig(any(), any(), any(), any()) }
        }
    }

    // ── RTSP settings group ──

    @Test
    fun `one rtsp field change re-applies the whole rtsp group in one pass`() {
        eventually {
            verify(exactly = 1) { streamingManager.setRtspEnabled(false) }
            verify(exactly = 1) { streamingManager.setRtspPort(8554) }
            verify(exactly = 1) { streamingManager.setRtspInputFormat(RtspInputFormat.AUTO) }
            verify(exactly = 1) { streamingManager.setRtspResolution(RtspResolution.P720) }
            verify(exactly = 1) { streamingManager.setRtspVideoCodec(RtspVideoCodec.H264) }
        }
        rtspPort.value = 9555
        eventually {
            verify(exactly = 1) { streamingManager.setRtspPort(9555) }
            verify(exactly = 2) { streamingManager.setRtspEnabled(false) }
            verify(exactly = 2) { streamingManager.setRtspInputFormat(RtspInputFormat.AUTO) }
            verify(exactly = 2) { streamingManager.setRtspResolution(RtspResolution.P720) }
            verify(exactly = 2) { streamingManager.setRtspVideoCodec(RtspVideoCodec.H264) }
            verify(exactly = 2) { streamingManager.setRtspPort(any()) }
        }
    }

    @Test
    fun `rtsp sub-stream toggle lands on the hub`() {
        eventually {
            verify(exactly = 1) { streamingManager.setRtspSubStreamEnabled(false) }
        }
        rtspSubStreamEnabled.value = true
        eventually {
            verify(exactly = 1) { streamingManager.setRtspSubStreamEnabled(true) }
            verify(exactly = 2) { streamingManager.setRtspSubStreamEnabled(any()) }
        }
    }

    // ── push outputs: RTMP / WHIP / SRT lifecycle rule ──

    @Test
    fun `rtmp url change routes url first and re-runs the enable gate`() {
        eventually {
            verify(exactly = 1) { streamingManager.setRtmpUrl("") }
            verify(exactly = 1) { streamingManager.setRtmpEnabled(false) }
        }
        rtmpUrl.value = "rtmp://broker/live/key"
        eventually {
            verify(exactly = 1) { streamingManager.setRtmpUrl("rtmp://broker/live/key") }
            verify(exactly = 2) { streamingManager.setRtmpEnabled(false) }
        }
    }

    @Test
    fun `whip group applies endpoint token stun then the enable gate`() {
        eventually {
            verify(exactly = 1) { streamingManager.setWhipUrl("") }
            verify(exactly = 1) { streamingManager.setWhipToken("") }
            verify(exactly = 1) { streamingManager.setWhipStunServer("") }
            verify(exactly = 1) { streamingManager.setWhipEnabled(false) }
        }
        whipEnabled.value = true
        eventually {
            verify(exactly = 2) { streamingManager.setWhipUrl("") }
            verify(exactly = 2) { streamingManager.setWhipToken("") }
            verify(exactly = 2) { streamingManager.setWhipStunServer("") }
            verify(exactly = 1) { streamingManager.setWhipEnabled(true) }
            verify(exactly = 2) { streamingManager.setWhipEnabled(any()) }
        }
    }

    @Test
    fun `srt url change routes url first and re-runs the enable gate`() {
        eventually {
            verify(exactly = 1) { streamingManager.setSrtUrl("") }
            verify(exactly = 1) { streamingManager.setSrtEnabled(false) }
        }
        srtUrl.value = "srt://host:9000"
        eventually {
            verify(exactly = 1) { streamingManager.setSrtUrl("srt://host:9000") }
            verify(exactly = 2) { streamingManager.setSrtEnabled(false) }
        }
    }

    // ── photo capture config ──

    @Test
    fun `photo knobs fold into one immutable PhotoCaptureConfig per emission`() {
        eventually {
            verify(exactly = 1) {
                cameraService.applyPhotoCaptureConfig(PhotoCapturePlan.PhotoCaptureConfig(70, false, false, PhotoAspectRatio.R16_9))
            }
        }
        photoJpegQuality.value = 85
        eventually {
            verify(exactly = 1) {
                cameraService.applyPhotoCaptureConfig(PhotoCapturePlan.PhotoCaptureConfig(85, false, false, PhotoAspectRatio.R16_9))
            }
            verify(exactly = 2) { cameraService.applyPhotoCaptureConfig(any()) }
        }
    }

    // ── discovery + adaptive + HLS DVR ──

    @Test
    fun `discovery group applies web mdns adaptive encoded-adaptive and dvr together`() {
        eventually {
            verify(exactly = 1) { streamingManager.setWebStreamingEnabled(true) }
            verify(exactly = 1) { streamingManager.setMdnsEnabled(true) }
            verify(exactly = 1) { streamingManager.setAdaptiveBitrateEnabled(false) }
            verify(exactly = 1) { streamingManager.setEncodedAdaptiveBitrateEnabled(false) }
            verify(exactly = 1) { streamingManager.setHlsDvrSegments(12) }
        }
        adaptiveBitrateEnabled.value = true
        eventually {
            verify(exactly = 1) { streamingManager.setAdaptiveBitrateEnabled(true) }
            verify(exactly = 2) { streamingManager.setWebStreamingEnabled(true) }
            verify(exactly = 2) { streamingManager.setMdnsEnabled(true) }
            verify(exactly = 2) { streamingManager.setEncodedAdaptiveBitrateEnabled(false) }
            verify(exactly = 2) { streamingManager.setHlsDvrSegments(12) }
        }
    }

    // ── auth settings ──

    @Test
    fun `auth settings hand the whole value type to the manager`() {
        eventually {
            verify(exactly = 1) { streamingManager.updateAuthSettings(StreamAuthSettings()) }
        }
        val auth = StreamAuthSettings(enabled = true, username = "admin", passwordHash = "hash")
        authSettings.value = auth
        eventually {
            verify(exactly = 1) { streamingManager.updateAuthSettings(auth) }
            verify(exactly = 2) { streamingManager.updateAuthSettings(any()) }
        }
    }

    // ── motion detection ──

    @Test
    fun `motion sensitivity scales percent to the zero-to-one ladder and zones pass through`() {
        eventually {
            verify(exactly = 1) { streamingManager.setMotionSensitivity(0.5f) }
            verify(exactly = 1) { streamingManager.setMotionZones(emptyList()) }
            verify(exactly = 1) { streamingManager.setMotionDetectionEnabled(false) }
            verify(exactly = 1) { streamingManager.setMotionCooldownSeconds(10) }
        }
        motionSensitivity.value = 60
        eventually {
            verify(exactly = 1) { streamingManager.setMotionSensitivity(0.6f) }
            verify(exactly = 2) { streamingManager.setMotionDetectionEnabled(false) }
        }
        val zone = MotionZone(id = "z1")
        motionZones.value = listOf(zone)
        eventually {
            verify(exactly = 1) { streamingManager.setMotionZones(listOf(zone)) }
            verify(exactly = 2) { streamingManager.setMotionSensitivity(0.6f) }
            verify(exactly = 3) { streamingManager.setMotionDetectionEnabled(false) }
        }
    }

    // ── sound detection ──

    @Test
    fun `sound threshold rides setSoundDetection and the cooldown is its own call`() {
        eventually {
            verify(exactly = 1) { streamingManager.setSoundDetection(false, 20, false) }
            verify(exactly = 1) { streamingManager.setSoundCooldownSeconds(60) }
        }
        soundThresholdPercent.value = 30
        eventually {
            verify(exactly = 1) { streamingManager.setSoundDetection(false, 30, false) }
            verify(exactly = 2) { streamingManager.setSoundCooldownSeconds(60) }
        }
        soundAdaptiveNoiseFloor.value = true
        eventually {
            verify(exactly = 1) { streamingManager.setSoundDetection(false, 30, true) }
            verify(exactly = 3) { streamingManager.setSoundCooldownSeconds(60) }
        }
    }

    // ── preferred microphone ──

    @Test
    fun `preferred microphone applies live to the capture record`() {
        eventually {
            verify(exactly = 1) { streamingManager.setAudioDeviceId("") }
        }
        audioDeviceId.value = "mic-2"
        eventually {
            verify(exactly = 1) { streamingManager.setAudioDeviceId("mic-2") }
            verify(exactly = 2) { streamingManager.setAudioDeviceId(any()) }
        }
    }

    // ── watchdog ──

    @Test
    fun `one watchdog field change re-applies the whole watchdog group`() {
        eventually {
            verify(exactly = 1) { streamWatchdog.setEnabled(false) }
            verify(exactly = 1) { streamWatchdog.setMaxRetries(3) }
            verify(exactly = 1) { streamWatchdog.setCheckIntervalSeconds(5) }
        }
        watchdogMaxRetries.value = 7
        eventually {
            verify(exactly = 1) { streamWatchdog.setMaxRetries(7) }
            verify(exactly = 2) { streamWatchdog.setEnabled(false) }
            verify(exactly = 2) { streamWatchdog.setCheckIntervalSeconds(5) }
            verify(exactly = 2) { streamWatchdog.setMaxRetries(any()) }
        }
    }

    // ── MQTT connection lifecycle ──

    @Test
    fun `mqtt lifecycle rule closes while disabled and reconnects on any setting emission while enabled`() {
        // The eight combined flows initialize as ONE emission, answered with
        // the disabled branch: close, never start.
        eventually {
            verify(exactly = 1) { mqttAlertPublisher.close() }
            verify(exactly = 0) { mqttAlertPublisher.start() }
        }
        mqttEnabled.value = true
        eventually {
            verify(exactly = 1) { mqttAlertPublisher.start() }
        }
        // A non-gate MQTT setting emission re-runs the rule; the live gate read
        // says enabled → connect/announce, never close.
        mqttBrokerHost.value = "broker.local"
        eventually {
            verify(exactly = 2) { mqttAlertPublisher.start() }
            verify(exactly = 1) { mqttAlertPublisher.close() }
        }
    }

    // ── MQTT stream states ──

    @Test
    fun `mqtt stream states replay current truth once then publish only outputs that moved`() {
        eventually {
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.WEB, false) }
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.RTSP, false) }
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.RTMP, false) }
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.WHIP, false) }
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.SRT, false) }
        }
        isWebStreamingActive.value = true
        eventually {
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.WEB, true) }
        }
        verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.RTSP, false) }
        // A push output is live from a passing start until its stop — a
        // non-Idle (connecting) status already publishes ON.
        rtmpStatus.value = RtmpStatus.Connecting
        eventually {
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.RTMP, true) }
        }
        srtStatus.value = SrtStatus.Error("boom")
        eventually {
            verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.SRT, true) }
        }
        verify(exactly = 1) { mqttAlertPublisher.notifyStreamState(MqttTopics.StreamOutput.WHIP, false) }
    }

    // ── MQTT client events ──

    @Test
    fun `mjpeg client events publish on change only, never on the initial count`() {
        settle()
        verify(exactly = 0) {
            mqttAlertPublisher.notifyClientEvent(MqttTopics.ClientKind.MJPEG, any(), any())
        }
        clientCount.value = 1
        eventually {
            verify(exactly = 1) {
                mqttAlertPublisher.notifyClientEvent(MqttTopics.ClientKind.MJPEG, connected = true, activeCount = 1)
            }
        }
        clientCount.value = 1
        settle()
        verify(exactly = 1) {
            mqttAlertPublisher.notifyClientEvent(MqttTopics.ClientKind.MJPEG, any(), any())
        }
        clientCount.value = 0
        eventually {
            verify(exactly = 1) {
                mqttAlertPublisher.notifyClientEvent(MqttTopics.ClientKind.MJPEG, connected = false, activeCount = 0)
            }
        }
    }

    // ── ONVIF ──

    @Test
    fun `onvif toggle runs the endpoint lifecycle rule`() {
        eventually {
            verify(exactly = 1) { onvifServer.stop() }
            verify(exactly = 0) { onvifServer.start() }
        }
        onvifEnabled.value = true
        eventually {
            verify(exactly = 1) { onvifServer.start() }
            verify(exactly = 1) { onvifServer.stop() }
        }
    }

    // ── eco idle ──

    @Test
    fun `eco idle toggle lands on the manager`() {
        eventually {
            verify(exactly = 1) { streamingManager.setEcoIdleFpsEnabled(false) }
        }
        ecoIdleFpsEnabled.value = true
        eventually {
            verify(exactly = 1) { streamingManager.setEcoIdleFpsEnabled(true) }
            verify(exactly = 2) { streamingManager.setEcoIdleFpsEnabled(any()) }
        }
    }

    // ── no cross-application ──

    @Test
    fun `watchdog mqtt and onvif changes never cross-apply onto the streaming or camera runtime`() {
        eventually {
            verify(exactly = 1) { streamingManager.setPort(8080) }
            coVerify(exactly = 1) { cameraService.applySettings(any()) }
        }
        watchdogMaxRetries.value = 9
        mqttEnabled.value = true
        onvifEnabled.value = true
        eventually {
            verify(exactly = 1) { streamWatchdog.setMaxRetries(9) }
            verify(exactly = 1) { mqttAlertPublisher.start() }
            verify(exactly = 1) { onvifServer.start() }
        }
        // A non-gate MQTT setting emission with the gate live re-runs start.
        mqttBrokerHost.value = "broker2.local"
        eventually {
            verify(exactly = 2) { mqttAlertPublisher.start() }
        }
        settle()
        // The web port and the camera snapshot each applied exactly once —
        // their own flows' initial emission — untouched by the three changes.
        verify(exactly = 1) { streamingManager.setPort(8080) }
        verify(exactly = 1) { streamingManager.setTlsEnabled(false) }
        verify(exactly = 1) { streamingManager.setFrameRate(any()) }
        coVerify(exactly = 1) { cameraService.applySettings(any()) }
    }
}
