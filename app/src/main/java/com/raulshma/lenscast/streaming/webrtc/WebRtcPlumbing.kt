package com.raulshma.lenscast.streaming.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The libwebrtc plumbing both WebRTC ends share — the WHIP push publisher
 * ([com.raulshma.lenscast.streaming.whip.WhipPublisher]) and the WHEP viewer
 * endpoint ([com.raulshma.lenscast.streaming.whep.WhepServer]) — extracted
 * once so the two cannot drift:
 *
 *  - the process-wide [PeerConnectionFactory.initialize] one-shot,
 *  - the STUN setting → iceServers mapping (bare `host[:port]` accepted, the
 *    `stun:`/`stuns:` URI assembled otherwise; blank = host candidates only),
 *  - the bounded createOffer / setDescription latches,
 *  - the candidate-collecting peer-connection observer with the one-shot
 *    gather-complete latch.
 *
 * Device-only code (the native library loads here), so — like the WHIP
 * publisher's own path — it is compile- and device-verified, never
 * JVM-tested; the JVM-tested decisions around it (SDP parsing, session
 * registries, signaling math) live in pure modules next to their consumers.
 */
internal object WebRtcPlumbing {

    /** One-shot ICE still gets a cap: host candidates alone are usable on a LAN. */
    const val GATHER_TIMEOUT_MS = 3_000L

    /** Bounded wait for every SDP step, so a wedged native call cannot hang a worker thread. */
    const val SDP_TIMEOUT_MS = 5_000L

    /** Process-wide guard: [PeerConnectionFactory.initialize] must run once. */
    private val factoryInitialized = AtomicBoolean(false)

    /** [PeerConnectionFactory.initialize] is a static one-shot for the process. */
    fun ensureFactoryInitialized(context: Context) {
        if (!factoryInitialized.compareAndSet(false, true)) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
    }

    /** The STUN setting → iceServers: bare `host[:port]` or a full `stun:`/`stuns:` URI; blank means none. */
    fun stunIceServers(server: String?): List<PeerConnection.IceServer> {
        val trimmed = server?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
        val uri = if (trimmed.startsWith("stun:", true) || trimmed.startsWith("stuns:", true)) {
            trimmed
        } else {
            "stun:$trimmed"
        }
        return listOf(PeerConnection.IceServer.builder(uri).createIceServer())
    }

    /**
     * The mutable outcome of one async SDP step, shared between libwebrtc's
     * callback thread and the waiting caller. The fields must be read AFTER
     * the latch opens — passing snapshots of them as call arguments reads them
     * before the callback ran, which turned every create-offer/answer into
     * "failed without a reason".
     */
    private class SdpStep {
        @Volatile var description: SessionDescription? = null
        @Volatile var failure: String? = null
    }

    /** Runs [PeerConnection.createOffer], returning the description or failing with its error text. */
    fun awaitCreateOffer(pc: PeerConnection, label: String): SessionDescription {
        val latch = CountDownLatch(1)
        val step = SdpStep()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                step.description = sdp
                latch.countDown()
            }

            override fun onSetSuccess() = Unit // unreachable from createOffer
            override fun onCreateFailure(error: String?) {
                step.failure = error ?: "unknown error"
                latch.countDown()
            }

            override fun onSetFailure(error: String?) = Unit // unreachable from createOffer
        }, MediaConstraints())
        return awaitSdpLatch(latch, step, label)
    }

    /** Runs [PeerConnection.createAnswer], returning the description or failing with its error text. */
    fun awaitCreateAnswer(pc: PeerConnection, label: String): SessionDescription {
        val latch = CountDownLatch(1)
        val step = SdpStep()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                step.description = sdp
                latch.countDown()
            }

            override fun onSetSuccess() = Unit // unreachable from createAnswer
            override fun onCreateFailure(error: String?) {
                step.failure = error ?: "unknown error"
                latch.countDown()
            }

            override fun onSetFailure(error: String?) = Unit // unreachable from createAnswer
        }, MediaConstraints())
        return awaitSdpLatch(latch, step, label)
    }

    /** Runs a set(Local|Remote)Description, failing with its error text. */
    fun awaitSetDescription(pc: PeerConnection, local: Boolean, description: SessionDescription, label: String) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit // unreachable from set
            override fun onSetSuccess() = latch.countDown()
            override fun onCreateFailure(error: String?) = Unit // unreachable from set
            override fun onSetFailure(error: String?) {
                failure = error
                latch.countDown()
            }
        }
        if (local) {
            pc.setLocalDescription(observer, description)
        } else {
            pc.setRemoteDescription(observer, description)
        }
        try {
            if (!latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw WebrtcSdpException("$label SDP set did not complete in time")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WebrtcSdpException("$label SDP set interrupted")
        }
        failure?.let { throw WebrtcSdpException("$label SDP set failed: $it") }
    }

    private fun awaitSdpLatch(
        latch: CountDownLatch,
        step: SdpStep,
        label: String,
    ): SessionDescription {
        try {
            if (!latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw WebrtcSdpException("$label SDP step did not complete in time")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WebrtcSdpException("$label SDP step interrupted")
        }
        step.failure?.let { throw WebrtcSdpException("$label SDP step failed: $it") }
        return step.description ?: throw WebrtcSdpException("$label SDP step failed without a reason")
    }
}

/** A bounded SDP step failed — the caller maps it to a readable transport error. */
class WebrtcSdpException(message: String) : Exception(message)

/**
 * The peer connection's callback half, shared by both WebRTC ends: collects
 * the gathered candidates and opens the gather-complete latch. ICE-state
 * changes flow through [onIceState] (default: log only — the WHIP hold loop
 * polls [PeerConnection.iceConnectionState] on its own thread; the WHEP
 * session registry reacts instead).
 */
internal open class GatherObserver(
    private val tag: String,
    private val onIceState: (PeerConnection.IceConnectionState) -> Unit = { state -> Log.d(tag, "ICE state: $state") },
) : PeerConnection.Observer {

    private val candidates = mutableListOf<IceCandidate>()
    private val gatherLatch = CountDownLatch(1)

    /** Waits (bounded) for gathering completion — or the cap — and answers every candidate so far. */
    fun awaitGatheringComplete(timeoutMs: Long): List<IceCandidate> {
        gatherLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(candidates) { return candidates.toList() }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        synchronized(candidates) { candidates.add(candidate) }
    }

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        if (state == PeerConnection.IceGatheringState.COMPLETE) gatherLatch.countDown()
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        onIceState(state)
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
    override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
}
