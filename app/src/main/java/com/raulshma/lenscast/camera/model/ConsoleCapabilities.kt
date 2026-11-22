package com.raulshma.lenscast.camera.model

/**
 * What the current console backend can honor. The camera dashboard is one
 * interface over two backends (see [com.raulshma.lenscast.camera.CameraConsole]):
 * the phone's local hardware today, a paired remote LensCast device over its
 * HTTP API in a later package — and the two cannot offer the same feature
 * set. Every flag here is the screen's render verdict for one such feature:
 * the screen reads the single [com.raulshma.lenscast.camera.CameraConsole.capabilities]
 * flow and hides (or stubs) what the backend's flags don't cover, so neither
 * backend needs an `if (remote)` branch in the UI. Pure immutable data: the
 * backends own the verdicts, this only carries them — no Android types, so
 * the flag tables themselves stay JVM-testable.
 */
data class ConsoleCapabilities(
    /**
     * The pro-tools overlays (histogram / zebras / focus peaking) rendered
     * from the backend's analysis frames. Honoring it means feeding real
     * per-frame analysis data ([ProToolsPolicy.ProToolsFrame]); a backend
     * whose pipeline produces no analysis frames cannot fake them.
     * Owner: the backend's frame pipeline.
     */
    val proToolsOverlays: Boolean,
    /**
     * The spirit-level line ([LevelIndicatorPolicy.LevelLineState]) fed by
     * the device's motion sensors. Owner: the backend's sensor runtime.
     */
    val spiritLevel: Boolean,
    /**
     * Tap-to-focus metering from normalized preview coordinates — a write
     * into the backend's camera pipeline, not a local UI computation.
     * Owner: the backend's camera controls.
     */
    val tapToFocus: Boolean,
    /**
     * The roster role gate (admin vs viewer pairing, the same verdict the
     * companion live tab renders as `canControl`): every state write the
     * dashboard offers — shutter, record, torch/flash, stream and server
     * toggles, quick-setting writes, zoom — is offered only when true.
     * Owner: the pairing roster, not the backend transport.
     */
    val canControl: Boolean,
    /**
     * The push-to-talk lane (console mic → device speaker), companion
     * live-tab parity. Owner: the backend's talkback endpoint.
     */
    val talkback: Boolean,
    /**
     * The live listen lane (device mic → console playback), companion
     * live-tab parity. Owner: the backend's live-audio endpoint.
     */
    val liveAudio: Boolean,
    /**
     * The server panel: the connect sheet and the server status controls
     * (start/stop, copyable URLs, TLS fingerprint) — meaningful only when
     * the backend actually serves viewers. Owner: the backend's server.
     */
    val serverPanel: Boolean,
    /**
     * The self-timer shutter countdown — the backend owns the countdown
     * clock and the deferred capture, so a backend that cannot delay a
     * capture cannot offer the timer. Owner: the backend's shutter path.
     */
    val selfTimer: Boolean,
    /**
     * The flash chip beside the lens switch (cycles the main-shutter flash
     * mode OFF → AUTO → ON). Honoring it means a captured photo can actually
     * carry the mode — a backend whose capture route takes no flash carrier
     * must not render a chip that cycles a setting no photo ever sees.
     * Owner: the backend's capture path.
     */
    val flashControl: Boolean,
    /**
     * Picture-in-picture of the live viewfinder. Owner: the backend's
     * renderable preview surface.
     */
    val pip: Boolean,
) {
    companion object {

        /**
         * The local phone's set: a camera phone can do everything the
         * dashboard renders, so the screen hides nothing — exactly today's
         * behavior. [CameraViewModel] publishes this constant; nothing here
         * asks the hardware — the flags are the design's verdict, not a
         * probe result.
         *
         * The invariant that keeps the screen behavior-neutral: **`local()`
         * is what the local dashboard ACTUALLY RENDERS today**, not what the
         * phone could theoretically do. Hence [pip] = false even though the
         * platform offers picture-in-picture: the dashboard has no PiP
         * affordance on the local branch, and a true flag here would ask the
         * screen to render a control it never had (supersedes this table's
         * original all-true note).
         */
        fun local(): ConsoleCapabilities = ConsoleCapabilities(
            proToolsOverlays = true,
            spiritLevel = true,
            tapToFocus = true,
            canControl = true,
            talkback = true,
            liveAudio = true,
            serverPanel = true,
            selfTimer = true,
            flashControl = true,
            pip = false,
        )

        /**
         * The remote paired-device set, in the shape the console package
         * builds: everything a backend cannot promise is OFF by default
         * (the screen hides the feature rather than rendering a dead
         * control), and the device's actual abilities are opt-in flags
         * lifted from its probe/status answers. [canControl] stays a
         * separate argument because it is the roster role's verdict, not the
         * device's — a viewer-role pairing degrades it even on a
         * fully-featured device.
         */
        fun remote(
            proToolsOverlays: Boolean = false,
            spiritLevel: Boolean = false,
            tapToFocus: Boolean = false,
            canControl: Boolean = false,
            talkback: Boolean = false,
            liveAudio: Boolean = false,
            serverPanel: Boolean = false,
            selfTimer: Boolean = false,
            flashControl: Boolean = false,
            pip: Boolean = false,
        ): ConsoleCapabilities = ConsoleCapabilities(
            proToolsOverlays = proToolsOverlays,
            spiritLevel = spiritLevel,
            tapToFocus = tapToFocus,
            canControl = canControl,
            talkback = talkback,
            liveAudio = liveAudio,
            serverPanel = serverPanel,
            selfTimer = selfTimer,
            flashControl = flashControl,
            pip = pip,
        )
    }
}
