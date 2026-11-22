package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability tables' verdict contracts: `local()` must stay exactly what
 * the local dashboard renders (the behavior-neutral invariant — the screen
 * hides nothing on the local branch), and `remote()` must default every
 * flag OFF so an unknown device never renders a dead control — with each
 * capability opted in independently, pinned one flag per case so a wiring
 * swap between any pair (liveAudio and talkback included) cannot hide
 * inside a batched assertion.
 */
class ConsoleCapabilitiesTest {

    // ── local(): the dashboard renders all of this today ──

    @Test
    fun `local keeps every rendered affordance on`() {
        val caps = ConsoleCapabilities.local()
        assertTrue(caps.proToolsOverlays)
        assertTrue(caps.spiritLevel)
        assertTrue(caps.tapToFocus)
        assertTrue(caps.canControl)
        assertTrue(caps.talkback)
        assertTrue(caps.liveAudio)
        assertTrue(caps.serverPanel)
        assertTrue(caps.selfTimer)
        // The flash chip exists on the local dashboard (the flash mode is a
        // real capture setting), so the chip renders.
        assertTrue(caps.flashControl)
    }

    @Test
    fun `local pins pip off — the dashboard renders no pip affordance today`() {
        // The behavior-neutral invariant: local() = what the local dashboard
        // ACTUALLY RENDERS. Picture-in-picture is platform-available but the
        // dashboard has no affordance for it; a true here would ask the
        // screen to render a control it never had.
        assertFalse(ConsoleCapabilities.local().pip)
    }

    // ── remote(): everything off unless the device opts in ──

    @Test
    fun `remote defaults every flag off`() {
        val caps = ConsoleCapabilities.remote()
        assertFalse(caps.proToolsOverlays)
        assertFalse(caps.spiritLevel)
        assertFalse(caps.tapToFocus)
        assertFalse(caps.canControl)
        assertFalse(caps.talkback)
        assertFalse(caps.liveAudio)
        assertFalse(caps.serverPanel)
        assertFalse(caps.selfTimer)
        // The flash mode has no remote wire carrier (a chip that cannot move
        // a photo's flash must not render), and PiP stays opt-in.
        assertFalse(caps.flashControl)
        assertFalse(caps.pip)
    }

    // ── remote(): each capability is its own opt-in — one flag per case,
    //    every other flag asserted false, so a wiring swap between any pair
    //    (liveAudio and talkback included) fails by name ──

    @Test
    fun `remote opt-in is per flag — proToolsOverlays`() =
        assertOnlyEnables(ConsoleCapabilities.remote(proToolsOverlays = true), "proToolsOverlays")

    @Test
    fun `remote opt-in is per flag — spiritLevel`() =
        assertOnlyEnables(ConsoleCapabilities.remote(spiritLevel = true), "spiritLevel")

    @Test
    fun `remote opt-in is per flag — tapToFocus`() =
        assertOnlyEnables(ConsoleCapabilities.remote(tapToFocus = true), "tapToFocus")

    @Test
    fun `remote opt-in is per flag — canControl`() =
        assertOnlyEnables(ConsoleCapabilities.remote(canControl = true), "canControl")

    @Test
    fun `remote opt-in is per flag — talkback`() =
        assertOnlyEnables(ConsoleCapabilities.remote(talkback = true), "talkback")

    @Test
    fun `remote opt-in is per flag — liveAudio`() =
        assertOnlyEnables(ConsoleCapabilities.remote(liveAudio = true), "liveAudio")

    @Test
    fun `remote opt-in is per flag — serverPanel`() =
        assertOnlyEnables(ConsoleCapabilities.remote(serverPanel = true), "serverPanel")

    @Test
    fun `remote opt-in is per flag — selfTimer`() =
        assertOnlyEnables(ConsoleCapabilities.remote(selfTimer = true), "selfTimer")

    @Test
    fun `remote opt-in is per flag — flashControl`() =
        assertOnlyEnables(ConsoleCapabilities.remote(flashControl = true), "flashControl")

    @Test
    fun `remote opt-in is per flag — pip`() =
        assertOnlyEnables(ConsoleCapabilities.remote(pip = true), "pip")

    /**
     * Asserts that [caps] enables exactly [enabled]: every capability is
     * named below exactly once, so each per-flag case checks all ten
     * verdicts and a swapped parameter/field wiring between any pair fails
     * both of their cases.
     */
    private fun assertOnlyEnables(caps: ConsoleCapabilities, enabled: String) {
        val flags = listOf(
            "proToolsOverlays" to caps.proToolsOverlays,
            "spiritLevel" to caps.spiritLevel,
            "tapToFocus" to caps.tapToFocus,
            "canControl" to caps.canControl,
            "talkback" to caps.talkback,
            "liveAudio" to caps.liveAudio,
            "serverPanel" to caps.serverPanel,
            "selfTimer" to caps.selfTimer,
            "flashControl" to caps.flashControl,
            "pip" to caps.pip,
        )
        for ((name, value) in flags) {
            if (name == enabled) {
                assertTrue("$name must be the enabled opt-in", value)
            } else {
                assertFalse("$name must stay off when only $enabled is opted in", value)
            }
        }
    }
}
