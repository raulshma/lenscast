package com.raulshma.lenscast.automation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric pass over the REAL [AutomationReceiver.onReceive] dispatch —
 * no policy re-implementation, the receiver class itself under the
 * framework.
 *
 * **Seams found: none.** The receiver hard-wires
 * `context.applicationContext as? MainApplication` and resolves every
 * collaborator (StreamToggle, RecordingController, PhotoCaptureManager,
 * CameraService, StreamingManager, SirenAutoStop, StreamStateJournal) off
 * MainApplication's lazy `val` graph. There is no setter, no factory, no
 * injection point — so no collaborator can be faked without refactoring the
 * receiver (deliberately out of scope). The one seam this test exercises is
 * the application cast itself: the config supplies
 * [ColdStartApplication], a plain `Application` that is exactly the
 * cold-started-process shape the receiver's guard exists for.
 *
 * **Covered here** (all real code paths):
 * - the cold-start guard: `onReceive` returns early for every documented
 *   action (extras included) and for unknown/foreign/action-less broadcasts,
 *   touching no seam and never crashing;
 * - the goAsync-before-dispatch ordering, via a second `onReceive` on the
 *   same receiver instance — `goAsync()` throws on an already-armed
 *   PendingResult, so the second dispatch only succeeds when the first took
 *   the guard and never opened the async window;
 * - the manifest gate: exported + permission-guarded, the intent-filter
 *   resolving every documented action, the AUTOMATION permission declared
 *   dangerous and self-held, and Robolectric's grant/deny flipping
 *   `checkPermission`;
 * - the real Bundle reads the wire extras go through before the (already
 *   JVM-pinned) [AutomationIntentPolicy] verdicts — including the
 *   non-integer extra degrading to the zero default inside the framework's
 *   `Bundle.getInt`, the half of that contract the JVM test could only
 *   assert by convention.
 *
 * **Not coverable here (device-only):** the dispatch bodies themselves
 * (the StreamToggle/RecordingController/PhotoCaptureManager/CameraService/
 * SirenPlayer calls — unreachable without a MainApplication composition
 * root, which has no injectable seam), the goAsync → coroutine →
 * `finish()` lifecycle under a live composition root, and the OS-level
 * enforcement of the receiver's `android:permission` attribute on broadcast
 * delivery (asserted here only as manifest metadata + the permission-check
 * flip, not as a `sendBroadcast` round trip).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomationReceiverRoboTest.ColdStartApplication::class)
class AutomationReceiverRoboTest {

    // A minimal application: NOT MainApplication, so the receiver's
    // `as? MainApplication` cast fails exactly as it would in a
    // cold-started process whose composition root is not up yet.
    class ColdStartApplication : Application()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    // ── onReceive: the real dispatch on a cold-started process ──

    @Test
    fun `every documented action passes through real onReceive without crashing or touching a seam`() {
        val cases = listOf(
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_START_STREAM),
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_STOP_STREAM),
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_CAPTURE_PHOTO),
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_START_RECORDING)
                .putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 7200),
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_STOP_RECORDING),
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_SET_TORCH)
                .putExtra(AutomationReceiver.EXTRA_ENABLED, true),
            AutomationReceiver.intent(context, AutomationReceiver.ACTION_SET_SIREN)
                .putExtra(AutomationReceiver.EXTRA_ENABLED, false)
                .putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 30),
        )
        cases.forEach { intent ->
            // The not-our-application guard must swallow the broadcast
            // silently; any exception here means the guard leaked a real
            // MainApplication collaborator into a cold-started process.
            AutomationReceiver().onReceive(context, intent)
        }
    }

    @Test
    fun `unknown, foreign and action-less broadcasts route to nothing without crashing`() {
        val cases = listOf(
            // Unlisted package-scoped action
            AutomationReceiver.intent(context, "com.raulshma.lenscast.action.SELF_DESTRUCT"),
            // Foreign package's action
            AutomationReceiver.intent(context, "com.example.app.action.START_STREAM"),
            // Case is significant on the wire
            AutomationReceiver.intent(context, "com.raulshma.lenscast.action.start_stream"),
            // An action-bearing intent with no explicit receiver target at all
            Intent(AutomationReceiver.ACTION_START_STREAM),
        )
        cases.forEach { intent ->
            AutomationReceiver().onReceive(context, intent)
        }
    }

    @Test
    fun `a cold-started onReceive returns before goAsync - a second dispatch on the same instance stays legal`() {
        val single = AutomationReceiver()
        single.onReceive(context, AutomationReceiver.intent(context, AutomationReceiver.ACTION_START_STREAM))
        // goAsync() throws IllegalStateException when a PendingResult is
        // already armed on the receiver instance, so this second receive
        // succeeding is the visible proof the first took the guard and
        // never opened the async broadcast window.
        single.onReceive(context, AutomationReceiver.intent(context, AutomationReceiver.ACTION_STOP_STREAM))
    }

    // ── the manifest gate: exported, permission-guarded, intent-filtered ──

    @Test
    fun `the receiver is exported but permission-guarded in the merged manifest`() {
        val info = automationReceiverInfo()
        assertTrue("the automation receiver must stay exported", info.exported)
        assertEquals(AUTOMATION_PERMISSION, info.permission)
    }

    @Test
    fun `the manifest intent-filter resolves every documented action to the receiver`() {
        listOf(
            AutomationReceiver.ACTION_START_STREAM,
            AutomationReceiver.ACTION_STOP_STREAM,
            AutomationReceiver.ACTION_CAPTURE_PHOTO,
            AutomationReceiver.ACTION_START_RECORDING,
            AutomationReceiver.ACTION_STOP_RECORDING,
            AutomationReceiver.ACTION_SET_TORCH,
            AutomationReceiver.ACTION_SET_SIREN,
        ).forEach { action ->
            val matches = context.packageManager.queryBroadcastReceivers(
                Intent(action).setPackage(context.packageName),
                0,
            )
            assertTrue("no receiver resolves $action", matches.orEmpty().isNotEmpty())
            assertTrue(
                "$action resolved to something other than the automation receiver",
                matches.orEmpty().any { it.activityInfo.name == AutomationReceiver::class.java.name },
            )
        }
    }

    @Test
    fun `the automation permission is declared dangerous and held by this app`() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(
            "the app must declare the AUTOMATION permission (the widget's own broadcasts need it)",
            packageInfo.permissions.orEmpty().any { it.name == AUTOMATION_PERMISSION },
        )
        assertTrue(
            "the app must request the AUTOMATION permission it guards the receiver with",
            packageInfo.requestedPermissions.orEmpty().contains(AUTOMATION_PERMISSION),
        )
        // Dangerous level: a sender app only gains it through a runtime
        // request, never at install — the manifest's whole reason for the gate.
        assertEquals(
            PermissionInfo.PROTECTION_DANGEROUS,
            context.packageManager.getPermissionInfo(AUTOMATION_PERMISSION, 0).protectionLevel,
        )
    }

    @Test
    fun `the permission gate flips with Robolectric's grant and deny`() {
        val app = context as Application
        Shadows.shadowOf(app).denyPermissions(AUTOMATION_PERMISSION)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkPermission(AUTOMATION_PERMISSION, Process.myPid(), Process.myUid()),
        )
        Shadows.shadowOf(app).grantPermissions(AUTOMATION_PERMISSION)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkPermission(AUTOMATION_PERMISSION, Process.myPid(), Process.myUid()),
        )
    }

    // ── wire extras through the real Bundle into the one policy ──

    @Test
    fun `duration edges survive the real Bundle read and clamp in the policy`() {
        fun recordedDuration(configure: Intent.() -> Unit): Long {
            val intent = AutomationReceiver.intent(
                context,
                AutomationReceiver.ACTION_START_RECORDING,
            ).apply(configure)
            return AutomationIntentPolicy.recordingDurationSeconds(
                intent.getIntExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 0),
            )
        }
        assertEquals(0L, recordedDuration { }) // absent extra → unbounded
        assertEquals(0L, recordedDuration { putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, -1) })
        assertEquals(30L, recordedDuration { putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 30) })
        assertEquals(3600L, recordedDuration { putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 3600) })
        // Above the one-hour RecordingConfig ceiling: clamped, not rejected.
        assertEquals(3600L, recordedDuration { putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 7200) })
    }

    @Test
    fun `a non-integer duration extra degrades to the zero default inside the real Bundle`() {
        // The receiver reads with getIntExtra; the framework's Bundle.getInt
        // catches the cross-type ClassCastException and answers the 0 default,
        // so the policy only ever sees an Int. This pins the framework half
        // of the non-integer contract the JVM test asserts by convention.
        val intent = AutomationReceiver.intent(
            context,
            AutomationReceiver.ACTION_START_RECORDING,
        ).putExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, "not-a-number")
        assertEquals(0, intent.getIntExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 0))
        // And the degraded value is the unbounded verdict downstream.
        assertEquals(
            0L,
            AutomationIntentPolicy.recordingDurationSeconds(
                intent.getIntExtra(AutomationReceiver.EXTRA_DURATION_SECONDS, 0),
            ),
        )
    }

    @Test
    fun `the enabled extra reads through the real Bundle into the toggle verdict`() {
        fun verdict(configure: Intent.() -> Unit, currentState: Boolean): Boolean {
            val intent = AutomationReceiver.intent(
                context,
                AutomationReceiver.ACTION_SET_TORCH,
            ).apply(configure)
            return AutomationIntentPolicy.resolveEnable(
                hasEnabledExtra = intent.hasExtra(AutomationReceiver.EXTRA_ENABLED),
                enabledExtraValue = intent.getBooleanExtra(AutomationReceiver.EXTRA_ENABLED, false),
                currentState = currentState,
            )
        }
        assertEquals(true, verdict({ putExtra(AutomationReceiver.EXTRA_ENABLED, true) }, currentState = true))
        assertEquals(false, verdict({ putExtra(AutomationReceiver.EXTRA_ENABLED, false) }, currentState = true))
        // Absent extra = toggle: hasExtra is the verdict's gate, not the
        // default the getter would hand back.
        assertEquals(false, verdict({ }, currentState = true))
        assertEquals(true, verdict({ }, currentState = false))
    }

    // ── helpers ──

    private fun automationReceiverInfo(): android.content.pm.ActivityInfo =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers.orEmpty()
            .single { it.name == AutomationReceiver::class.java.name }

    private companion object {
        // The manifest pins this literal; a rename silently breaks every
        // external Tasker/MacroDroid/adb integration.
        const val AUTOMATION_PERMISSION = "com.raulshma.lenscast.permission.AUTOMATION"
    }
}
