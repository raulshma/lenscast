package com.raulshma.lenscast.settings

import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.PhotoCapturePlan
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.core.mqtt.MqttTopics
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.onvif.OnvifServer
import com.raulshma.lenscast.streaming.rtmp.RtmpStatus
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import com.raulshma.lenscast.streaming.srt.SrtStatus
import com.raulshma.lenscast.streaming.whip.WhipStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single owner of "persisted settings -> runtime" application.
 *
 * Every settings change goes through SettingsDataStore; this module watches the
 * store and applies new values to the StreamingManager, CameraService, and
 * StreamWatchdog. ViewModels and Web API handlers write settings — nobody else
 * applies them, so each persisted change is applied exactly once regardless of
 * how many screens or clients are alive.
 */
class SettingsApplier(
    private val settingsDataStore: SettingsDataStore,
    private val cameraService: CameraService,
    private val streamingManager: StreamingManager,
    private val streamWatchdog: StreamWatchdog,
    private val mqttAlertPublisher: MqttAlertPublisher,
    private val onvifServer: OnvifServer,
) {

    fun start(scope: CoroutineScope) {
        // Camera settings
        scope.launch {
            settingsDataStore.settings.collectLatest { saved ->
                cameraService.applySettings(saved)
            }
        }

        // Port + server startup
        scope.launch {
            settingsDataStore.streamingPort.collectLatest { port ->
                streamingManager.setPort(port)
                streamingManager.ensureServerRunning()
            }
        }

        // TLS mode: a stop → recreate → start cycle behind the manager's seam.
        scope.launch {
            settingsDataStore.httpsEnabled.collectLatest { enabled ->
                streamingManager.setTlsEnabled(enabled)
            }
        }

        // All audio-related settings in one coroutine
        scope.launch {
            combine(
                settingsDataStore.streamAudioEnabled,
                settingsDataStore.streamAudioBitrateKbps,
                settingsDataStore.streamAudioChannels,
                settingsDataStore.streamAudioEchoCancellation,
            ) { enabled, bitrate, channels, echoCancellation ->
                AudioSettings(enabled, bitrate, channels, echoCancellation)
            }.collectLatest { audio ->
                // One coalesced write: a single web-capture refresh and a
                // single RTSP restart decision per emission, and a no-op when
                // nothing moved — the old four-setter sequence restarted a
                // live RTSP output up to four times per emission.
                streamingManager.setAudioConfig(audio.enabled, audio.bitrateKbps, audio.channels, audio.echoCancellation)
            }
        }

        // JPEG quality + overlay settings
        scope.launch {
            combine(
                settingsDataStore.jpegQuality,
                settingsDataStore.overlaySettings,
            ) { quality, overlay ->
                QualityOverlaySettings(quality, overlay)
            }.collectLatest { config ->
                streamingManager.setJpegQuality(config.quality)
                streamingManager.setOverlaySettings(config.overlay)
            }
        }

        // Frame rate (M-JPEG streaming + RTSP + adaptive bitrate fan out
        // inside the Streaming Manager). Derived from the camera-settings
        // flow — the frame rate persists only through that descriptor. The
        // distinctUntilChanged keeps unrelated camera-settings changes from
        // re-firing the runtime apply, matching the old dedicated flow's
        // conflation; the initial emission is identical too (the flow's
        // default and CameraSettings' default are both StreamDefaults.STREAM_FPS).
        scope.launch {
            settingsDataStore.settings.map { it.frameRate }.distinctUntilChanged()
                .collectLatest { fps ->
                    streamingManager.setFrameRate(fps)
                }
        }

        // RTSP settings
        scope.launch {
            combine(
                settingsDataStore.rtspEnabled,
                settingsDataStore.rtspPort,
                settingsDataStore.rtspInputFormat,
                settingsDataStore.rtspResolution,
                settingsDataStore.rtspVideoCodec,
            ) { enabled, port, format, resolution, codec ->
                RtspSettings(enabled, port, format, resolution, codec)
            }.collectLatest { rtsp ->
                streamingManager.setRtspEnabled(rtsp.enabled)
                streamingManager.setRtspPort(rtsp.port)
                streamingManager.setRtspInputFormat(rtsp.format)
                streamingManager.setRtspResolution(rtsp.resolution)
                streamingManager.setRtspVideoCodec(rtsp.codec)
            }
        }

        // The RTSP low-res sub-stream: a single-flow rule like ONVIF's — the
        // toggle lands on the hub (a refresh starts/stops its second encoder)
        // and /sub serves or 404s on the next request.
        scope.launch {
            settingsDataStore.rtspSubStreamEnabled.collectLatest { enabled ->
                streamingManager.setRtspSubStreamEnabled(enabled)
            }
        }

        // RTMP push: the enable gate and the target URL run the output's own
        // lifecycle rule — off stops a live push, a URL change restarts it
        // through the output's URL-change path, and on simply arms it (the
        // actual start is a user/API action, exactly like the RTSP gate).
        scope.launch {
            combine(
                settingsDataStore.rtmpEnabled,
                settingsDataStore.rtmpUrl,
            ) { enabled, url ->
                RtmpSettings(enabled, url)
            }.collectLatest { rtmp ->
                streamingManager.setRtmpUrl(rtmp.url)
                streamingManager.setRtmpEnabled(rtmp.enabled)
            }
        }

        // WHIP push: the same lifecycle rule as the RTMP push — the enable
        // gate arms it, and any endpoint/token/STUN change restarts a live
        // output through its own config-change paths (the publisher's
        // connect parameters are per-attempt, so nothing can hot-swap).
        scope.launch {
            combine(
                settingsDataStore.whipEnabled,
                settingsDataStore.whipUrl,
                settingsDataStore.whipToken,
                settingsDataStore.whipStunServer,
            ) { enabled, url, token, stunServer ->
                WhipSettings(enabled, url, token, stunServer)
            }.collectLatest { whip ->
                streamingManager.setWhipUrl(whip.url)
                streamingManager.setWhipToken(whip.token)
                streamingManager.setWhipStunServer(whip.stunServer)
                streamingManager.setWhipEnabled(whip.enabled)
            }
        }

        // SRT push: the same lifecycle rule as the RTMP/WHIP pushes — the
        // enable gate arms it, and a URL change restarts a live output
        // through the output's own URL-change path (the handshake parameters
        // are per-attempt, so nothing can hot-swap).
        scope.launch {
            combine(
                settingsDataStore.srtEnabled,
                settingsDataStore.srtUrl,
            ) { enabled, url ->
                SrtSettings(enabled, url)
            }.collectLatest { srt ->
                streamingManager.setSrtUrl(srt.url)
                streamingManager.setSrtEnabled(srt.enabled)
            }
        }

        // Photo capture quality/aspect/RAW: the four persisted knobs fold into
        // the one immutable PhotoCaptureConfig the CameraService's
        // ImageCapture builder consumes — the plan (not this applier) owns the
        // quality clamp, the capture-mode choice, the aspect→bound-size
        // mapping, and the device RAW capability fold; a change that alters
        // the bound builder triggers the service's own RebindIfFree rebind.
        scope.launch {
            combine(
                settingsDataStore.photoJpegQuality,
                settingsDataStore.photoMaximizeQuality,
                settingsDataStore.rawCaptureEnabled,
                settingsDataStore.photoAspectRatio,
            ) { jpegQuality, maximizeQuality, rawRequested, aspect ->
                PhotoCapturePlan.PhotoCaptureConfig(jpegQuality, maximizeQuality, rawRequested, aspect)
            }.collectLatest { config ->
                cameraService.applyPhotoCaptureConfig(config)
            }
        }

        // Discovery + web streaming + adaptive bitrate
        scope.launch {
            combine(
                settingsDataStore.webStreamingEnabled,
                settingsDataStore.mdnsEnabled,
                settingsDataStore.adaptiveBitrateEnabled,
                settingsDataStore.encodedAdaptiveBitrateEnabled,
                settingsDataStore.hlsDvrSegments,
            ) { webEnabled, mdns, adaptive, encodedAdaptive, hlsDvr ->
                DiscoverySettings(webEnabled, mdns, adaptive, encodedAdaptive, hlsDvr)
            }.collectLatest { discovery ->
                streamingManager.setWebStreamingEnabled(discovery.webEnabled)
                streamingManager.setMdnsEnabled(discovery.mdns)
                streamingManager.setAdaptiveBitrateEnabled(discovery.adaptive)
                streamingManager.setEncodedAdaptiveBitrateEnabled(discovery.encodedAdaptive)
                streamingManager.setHlsDvrSegments(discovery.hlsDvr)
            }
        }

        // Auth settings
        scope.launch {
            settingsDataStore.authSettings.collectLatest { auth ->
                streamingManager.updateAuthSettings(auth)
            }
        }

        // Motion detection: persisted toggle → runtime detector. The settings
        // screen writes the store; the Applier applies exactly once. Sensitivity
        // arrives as a percent and scales to the detector's 0..1 ladder; zones
        // narrow detection to their enabled rectangles; the cooldown seconds
        // ride the detector's own minimum-between-events ladder.
        scope.launch {
            combine(
                settingsDataStore.motionDetectionEnabled,
                settingsDataStore.motionSensitivity,
                settingsDataStore.motionZones,
                settingsDataStore.motionCooldownSeconds,
            ) { enabled, sensitivityPercent, zones, cooldownSeconds ->
                MotionSettings(enabled, sensitivityPercent, zones, cooldownSeconds)
            }.collectLatest { motion ->
                streamingManager.setMotionDetectionEnabled(motion.enabled)
                streamingManager.setMotionSensitivity(motion.sensitivityPercent / 100f)
                streamingManager.setMotionZones(motion.zones)
                streamingManager.setMotionCooldownSeconds(motion.cooldownSeconds)
            }
        }

        // Preferred microphone: applied live to the capture record.
        scope.launch {
            settingsDataStore.audioDeviceId.collectLatest { id ->
                streamingManager.setAudioDeviceId(id)
            }
        }

        // Sound detection: threshold percent and toggle onto the audio-path
        // detector; the events route back through the manager's listener seam.
        scope.launch {
            combine(
                settingsDataStore.soundDetectionEnabled,
                settingsDataStore.soundThresholdPercent,
                settingsDataStore.soundCooldownSeconds,
                settingsDataStore.soundAdaptiveNoiseFloor,
            ) { enabled, threshold, cooldownSeconds, adaptiveFloor ->
                SoundSettings(enabled, threshold, cooldownSeconds, adaptiveFloor)
            }.collectLatest { sound ->
                streamingManager.setSoundDetection(sound.enabled, sound.thresholdPercent, sound.adaptiveFloor)
                streamingManager.setSoundCooldownSeconds(sound.cooldownSeconds)
            }
        }

        // Watchdog settings
        scope.launch {
            combine(
                settingsDataStore.watchdogEnabled,
                settingsDataStore.watchdogMaxRetries,
                settingsDataStore.watchdogCheckIntervalSeconds,
            ) { enabled, maxRetries, checkInterval ->
                WatchdogSettings(enabled, maxRetries, checkInterval)
            }.collectLatest { watchdog ->
                streamWatchdog.setEnabled(watchdog.enabled)
                streamWatchdog.setMaxRetries(watchdog.maxRetries)
                streamWatchdog.setCheckIntervalSeconds(watchdog.checkInterval)
            }
        }

        // MQTT alerting: any MQTT setting change runs the connection
        // lifecycle rule — enabled and hosted connects and announces
        // (idempotent under an unchanged config; a changed one reconnects
        // under it), disabled closes (a retained `offline` plus discovery
        // clears, so the HA entities never outlive the setting). The
        // per-dispatch config read stays the publisher's own live-read.
        // The eight flows fold through combines (not a merge) so their
        // StateFlow subscription replay is ONE lifecycle run: a merge
        // would replay each flow's current value as its own emission and
        // re-run the rule once per flow when the collector attaches late.
        scope.launch {
            combine(
                combine(
                    settingsDataStore.mqttEnabled,
                    settingsDataStore.mqttBrokerHost,
                    settingsDataStore.mqttBrokerPort,
                    settingsDataStore.mqttUsername,
                ) { enabled, brokerHost, brokerPort, username ->
                    MqttConnection(enabled, brokerHost, brokerPort, username)
                },
                combine(
                    settingsDataStore.mqttPassword,
                    settingsDataStore.mqttTls,
                    settingsDataStore.mqttDiscoveryPrefix,
                    settingsDataStore.mqttTelemetryEnabled,
                ) { password, tls, discoveryPrefix, telemetryEnabled ->
                    MqttDelivery(password, tls, discoveryPrefix, telemetryEnabled)
                },
            ) { connection, delivery ->
                connection to delivery
            }.collect {
                if (settingsDataStore.mqttEnabled.value) {
                    mqttAlertPublisher.start()
                } else {
                    mqttAlertPublisher.close()
                }
            }
        }

        // MQTT stream states: every push output's live flag lands as a
        // retained ON/OFF on the broker. The first emission replays the
        // current truth (so a just-connected broker learns the state at
        // once), later emissions publish only the outputs that moved.
        scope.launch {
            var lastStates: Map<MqttTopics.StreamOutput, Boolean> = emptyMap()
            combine(
                streamingManager.isWebStreamingActive,
                streamingManager.isRtspRunning,
                streamingManager.rtmpStatus,
                streamingManager.whipStatus,
                streamingManager.srtStatus,
            ) { web, rtsp, rtmp, whip, srt ->
                mapOf(
                    MqttTopics.StreamOutput.WEB to web,
                    MqttTopics.StreamOutput.RTSP to rtsp,
                    // A push output is "live" from a passing start until its
                    // stop — the connecting/reconnecting/error statuses
                    // included, exactly like the output's own isActive.
                    MqttTopics.StreamOutput.RTMP to (rtmp != RtmpStatus.Idle),
                    MqttTopics.StreamOutput.WHIP to (whip != WhipStatus.Idle),
                    MqttTopics.StreamOutput.SRT to (srt != SrtStatus.Idle),
                )
            }.collect { states ->
                for ((output, on) in states) {
                    if (lastStates[output] != on) {
                        mqttAlertPublisher.notifyStreamState(output, on)
                    }
                }
                lastStates = states
            }
        }

        // MQTT client events: MJPEG viewers ride the manager's client-count
        // flow (change-driven), RTSP sessions are sampled on a short poll
        // (the RTSP server exposes no change flow) and diffed by session id.
        // Both throttle and gate inside the publisher.
        scope.launch {
            var previous = -1
            streamingManager.clientCount.collect { count ->
                if (previous >= 0 && count != previous) {
                    mqttAlertPublisher.notifyClientEvent(
                        MqttTopics.ClientKind.MJPEG,
                        connected = count > previous,
                        activeCount = count,
                    )
                }
                previous = count
            }
        }
        // The RTSP poll runs only while MQTT alerts are enabled — with the
        // toggle off the publisher would no-op every sample anyway. A toggle
        // change cancels the previous poll (collectLatest) or starts a fresh
        // one, so no disabled loop keeps waking every 2 s.
        scope.launch {
            settingsDataStore.mqttEnabled.collectLatest { enabled ->
                if (!enabled) return@collectLatest
                var previousIds = emptySet<String>()
                while (true) {
                    delay(RTSP_CLIENT_POLL_MS)
                    val clients = runCatching { streamingManager.getRtspClients() }.getOrDefault(emptyList())
                    val ids = clients.map { it.id }.toSet()
                    for (id in ids - previousIds) {
                        mqttAlertPublisher.notifyClientEvent(MqttTopics.ClientKind.RTSP, connected = true, activeCount = ids.size)
                    }
                    for (id in previousIds - ids) {
                        mqttAlertPublisher.notifyClientEvent(MqttTopics.ClientKind.RTSP, connected = false, activeCount = ids.size)
                    }
                    previousIds = ids
                }
            }
        }

        // ONVIF: the enable flag runs the endpoint lifecycle rule — enabled
        // starts the WS-Discovery responder (idempotent), disabled stops it.
        // Everything else the endpoint advertises (URIs, ports, resolution,
        // audio) is read live per request through the server's providers, so
        // unlike MQTT no config change needs to re-run this rule.
        scope.launch {
            settingsDataStore.onvifEnabled.collectLatest { enabled ->
                if (enabled) {
                    onvifServer.start()
                } else {
                    onvifServer.stop()
                }
            }
        }

        // Eco idle-fps mode: the persisted toggle lands on the manager, whose
        // evaluation loop owns the client/charging/thermal verdicts (the pure
        // EcoIdlePolicy) and the drop/restore application.
        scope.launch {
            settingsDataStore.ecoIdleFpsEnabled.collectLatest { enabled ->
                streamingManager.setEcoIdleFpsEnabled(enabled)
            }
        }

        Log.d(TAG, "Settings applier started")
    }

    private data class AudioSettings(
        val enabled: Boolean,
        val bitrateKbps: Int,
        val channels: Int,
        val echoCancellation: Boolean,
    )

    private data class QualityOverlaySettings(
        val quality: Int,
        val overlay: com.raulshma.lenscast.camera.model.OverlaySettings,
    )

    private data class RtspSettings(
        val enabled: Boolean,
        val port: Int,
        val format: RtspInputFormat,
        val resolution: RtspResolution,
        val codec: RtspVideoCodec,
    )

    private data class RtmpSettings(
        val enabled: Boolean,
        val url: String,
    )

    private data class WhipSettings(
        val enabled: Boolean,
        val url: String,
        val token: String,
        val stunServer: String,
    )

    private data class SrtSettings(
        val enabled: Boolean,
        val url: String,
    )

    private data class DiscoverySettings(
        val webEnabled: Boolean,
        val mdns: Boolean,
        val adaptive: Boolean,
        val encodedAdaptive: Boolean,
        val hlsDvr: Int,
    )

    private data class WatchdogSettings(
        val enabled: Boolean,
        val maxRetries: Int,
        val checkInterval: Int,
    )

    private data class MqttConnection(
        val enabled: Boolean,
        val brokerHost: String,
        val brokerPort: Int,
        val username: String,
    )

    private data class MqttDelivery(
        val password: String,
        val tls: Boolean,
        val discoveryPrefix: String,
        val telemetryEnabled: Boolean,
    )

    private data class MotionSettings(
        val enabled: Boolean,
        val sensitivityPercent: Int,
        val zones: List<com.raulshma.lenscast.camera.model.MotionZone>,
        val cooldownSeconds: Int,
    )

    private data class SoundSettings(
        val enabled: Boolean,
        val thresholdPercent: Int,
        val cooldownSeconds: Int,
        val adaptiveFloor: Boolean,
    )

    companion object {
        private const val TAG = "SettingsApplier"

        /** The RTSP session sampler's cadence: prompt enough for a client event, cheap enough to idle on. */
        private const val RTSP_CLIENT_POLL_MS = 2_000L
    }
}
