package com.raulshma.lenscast.camera.model

import androidx.camera.core.CameraState as CameraXCameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraErrorPolicyTest {

    // ── the code constants are pinned to the CameraX taxonomy they mirror ──

    @Test
    fun `the mirrored codes match CameraX CameraState StateError codes`() {
        // A drift between this policy's ints and CameraX's would silently
        // mis-classify every error; the pin fails the build here instead.
        assertEquals(CameraXCameraState.ERROR_MAX_CAMERAS_IN_USE, CameraErrorPolicy.CODE_MAX_CAMERAS_IN_USE)
        assertEquals(CameraXCameraState.ERROR_CAMERA_IN_USE, CameraErrorPolicy.CODE_CAMERA_IN_USE)
        assertEquals(CameraXCameraState.ERROR_OTHER_RECOVERABLE_ERROR, CameraErrorPolicy.CODE_OTHER_RECOVERABLE_ERROR)
        assertEquals(CameraXCameraState.ERROR_STREAM_CONFIG, CameraErrorPolicy.CODE_STREAM_CONFIG)
        assertEquals(CameraXCameraState.ERROR_CAMERA_DISABLED, CameraErrorPolicy.CODE_CAMERA_DISABLED)
        assertEquals(CameraXCameraState.ERROR_CAMERA_FATAL_ERROR, CameraErrorPolicy.CODE_CAMERA_FATAL_ERROR)
        assertEquals(CameraXCameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED, CameraErrorPolicy.CODE_DO_NOT_DISTURB_MODE_ENABLED)
        assertEquals(CameraXCameraState.ERROR_CAMERA_REMOVED, CameraErrorPolicy.CODE_CAMERA_REMOVED)
    }

    // ── recoverable: CameraX owns the retry ──

    @Test
    fun `recoverable codes are CameraX's to handle at every attempt count`() {
        val recoverable = listOf(
            CameraErrorPolicy.CODE_MAX_CAMERAS_IN_USE,
            CameraErrorPolicy.CODE_CAMERA_IN_USE,
            CameraErrorPolicy.CODE_OTHER_RECOVERABLE_ERROR,
        )
        recoverable.forEach { code ->
            (0..CameraErrorPolicy.MAX_REBIND_ATTEMPTS + 2).forEach { attempts ->
                assertEquals(
                    CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
                    CameraErrorPolicy.decide(code, attempts),
                )
            }
        }
    }

    // ── critical: the app decides ──

    @Test
    fun `stream config errors rebind while the bounded lever has attempts left`() {
        assertEquals(
            CameraErrorPolicy.Verdict.REBIND,
            CameraErrorPolicy.decide(CameraErrorPolicy.CODE_STREAM_CONFIG, 0),
        )
        assertEquals(
            CameraErrorPolicy.Verdict.REBIND,
            CameraErrorPolicy.decide(CameraErrorPolicy.CODE_STREAM_CONFIG, CameraErrorPolicy.MAX_REBIND_ATTEMPTS - 1),
        )
    }

    @Test
    fun `stream config errors give the lever up at the attempt cap`() {
        // The bounded rebind is spent — CameraX/the watchdog ladder own any
        // further recovery; the error state stays published either way.
        assertEquals(
            CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
            CameraErrorPolicy.decide(CameraErrorPolicy.CODE_STREAM_CONFIG, CameraErrorPolicy.MAX_REBIND_ATTEMPTS),
        )
        assertEquals(
            CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
            CameraErrorPolicy.decide(CameraErrorPolicy.CODE_STREAM_CONFIG, 99),
        )
    }

    @Test
    fun `an unknown code defaults to the bounded rebind lever`() {
        // Future CameraX codes land on the surveillance-honest side: try the
        // rebind, bounded, rather than sitting Idle through an unclassified
        // error (the status quo this policy replaces).
        assertEquals(CameraErrorPolicy.Verdict.REBIND, CameraErrorPolicy.decide(42, 0))
        assertEquals(
            CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
            CameraErrorPolicy.decide(42, CameraErrorPolicy.MAX_REBIND_ATTEMPTS),
        )
    }

    @Test
    fun `policy-disabled errors carry the distinct POLICY_DENIED verdict`() {
        // ERROR_CAMERA_DISABLED is a device-policy verdict (the locked-mount
        // while-in-use denial): like DND no app-side lever exists — report
        // and wait for the policy to change — but the verdict is typed so
        // streaming's blocked-by-lock detector never string-matches the
        // human-readable message. Every attempt count: no lever to spend.
        (0..CameraErrorPolicy.MAX_REBIND_ATTEMPTS + 2).forEach { attempts ->
            assertEquals(
                CameraErrorPolicy.Verdict.POLICY_DENIED,
                CameraErrorPolicy.decide(CameraErrorPolicy.CODE_CAMERA_DISABLED, attempts),
            )
        }
    }

    @Test
    fun `do-not-disturb errors have no app-side lever`() {
        // DND is a user toggle; a rebind cannot fix it — report and wait for
        // the user to change it (CAMERAX_HANDLES: no dedicated verdict).
        assertEquals(
            CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
            CameraErrorPolicy.decide(CameraErrorPolicy.CODE_DO_NOT_DISTURB_MODE_ENABLED, 0),
        )
    }

    @Test
    fun `fatal errors require a device reboot at every attempt count`() {
        // camera2 ERROR_CAMERA_SERVICE: no sanctioned in-process recovery —
        // never rebind, never wait; the dashboard must say "reboot the phone".
        (0..CameraErrorPolicy.MAX_REBIND_ATTEMPTS + 2).forEach { attempts ->
            assertEquals(
                CameraErrorPolicy.Verdict.REBOOT_REQUIRED,
                CameraErrorPolicy.decide(CameraErrorPolicy.CODE_CAMERA_FATAL_ERROR, attempts),
            )
        }
    }

    @Test
    fun `removed cameras wait for onCameraAdded rather than rebinding`() {
        // A removed (e.g. unplugged USB) camera cannot rebind into anything —
        // the sanctioned action is unbind-and-wait for the camera's return.
        (0..CameraErrorPolicy.MAX_REBIND_ATTEMPTS + 2).forEach { attempts ->
            assertEquals(
                CameraErrorPolicy.Verdict.WAIT_CAMERA_ADDED,
                CameraErrorPolicy.decide(CameraErrorPolicy.CODE_CAMERA_REMOVED, attempts),
            )
        }
    }

    // ── the verdict rides the published error state (blueprint item 4's
    //    "distinguishable downstream" contract) ──

    @Test
    fun `the published error state carries the policy verdict for downstream`() {
        // The fatal class must be tellable from self-healing without
        // string-matching: CameraState.Error(message=…, recovery=REBOOT_REQUIRED).
        val fatal = CameraState.Error("Camera fatal error — reboot the device", CameraErrorPolicy.Verdict.REBOOT_REQUIRED)
        assertEquals(CameraErrorPolicy.Verdict.REBOOT_REQUIRED, fatal.recovery)
        assertEquals(CameraErrorPolicy.Verdict.REBIND, CameraState.Error("Camera error, code 4", CameraErrorPolicy.Verdict.REBIND).recovery)
    }

    @Test
    fun `legacy errors without a verdict render the unchanged toString`() {
        // The status wire ships cameraState.toString() — the pre-policy wire
        // format must survive for errors raised outside the taxonomy path.
        assertEquals("Error(message=oops)", CameraState.Error("oops").toString())
        assertNull(CameraState.Error("oops").recovery)
    }

    @Test
    fun `a verdict-carrying error renders the verdict in its toString`() {
        // The API-facing cameraStateName gains exactly the verdict suffix —
        // the one string the dashboard needs to split "reboot the phone"
        // from "will self-heal" (wave 3 consumes it).
        val rendered = CameraState.Error(
            "Camera fatal error — reboot the device",
            CameraErrorPolicy.Verdict.REBOOT_REQUIRED,
        ).toString()
        assertTrue(rendered, rendered.contains("recovery=REBOOT_REQUIRED"))
        assertTrue(rendered, rendered.startsWith("Error(message=Camera fatal error — reboot the device"))
    }

    // ── the manual reset-camera lever (blueprint P1 item 10) ──

    @Test
    fun `the manual lever spends the same bounded budget the observer spends`() {
        // REBIND while the budget holds, CAMERAX_HANDLES once it is spent —
        // exactly decide()'s unknown-code branch, so the dashboard's reset
        // button cannot loop rebinds past MAX_REBIND_ATTEMPTS.
        assertEquals(
            CameraErrorPolicy.Verdict.REBIND,
            CameraErrorPolicy.decideResetCamera(null, 0),
        )
        assertEquals(
            CameraErrorPolicy.Verdict.REBIND,
            CameraErrorPolicy.decideResetCamera(null, CameraErrorPolicy.MAX_REBIND_ATTEMPTS - 1),
        )
        assertEquals(
            CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
            CameraErrorPolicy.decideResetCamera(null, CameraErrorPolicy.MAX_REBIND_ATTEMPTS),
        )
    }

    @Test
    fun `a standing taxonomy verdict owns the manual answer and spends nothing`() {
        // REBOOT_REQUIRED: no in-process cure — the manual lever must surface
        // "reboot the phone" verbatim, never spend the rebind lever on it.
        assertEquals(
            CameraErrorPolicy.Verdict.REBOOT_REQUIRED,
            CameraErrorPolicy.decideResetCamera(CameraErrorPolicy.Verdict.REBOOT_REQUIRED, 0),
        )
        assertEquals(
            CameraErrorPolicy.Verdict.WAIT_CAMERA_ADDED,
            CameraErrorPolicy.decideResetCamera(CameraErrorPolicy.Verdict.WAIT_CAMERA_ADDED, 0),
        )
        // POLICY_DENIED (the standing camera-disabled error) groups with
        // CAMERAX_HANDLES on the manual path: surfaces verbatim, spends
        // nothing — the wire handler's catch-all answers it.
        assertEquals(
            CameraErrorPolicy.Verdict.POLICY_DENIED,
            CameraErrorPolicy.decideResetCamera(CameraErrorPolicy.Verdict.POLICY_DENIED, 0),
        )
        // A standing REBIND verdict falls through to the budget gate.
        assertEquals(
            CameraErrorPolicy.Verdict.REBIND,
            CameraErrorPolicy.decideResetCamera(CameraErrorPolicy.Verdict.REBIND, 0),
        )
        assertEquals(
            CameraErrorPolicy.Verdict.CAMERAX_HANDLES,
            CameraErrorPolicy.decideResetCamera(CameraErrorPolicy.Verdict.REBIND, CameraErrorPolicy.MAX_REBIND_ATTEMPTS),
        )
    }
}
