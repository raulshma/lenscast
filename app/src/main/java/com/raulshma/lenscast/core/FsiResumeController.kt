package com.raulshma.lenscast.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.R
import com.raulshma.lenscast.streaming.LockBypassActivity
import com.raulshma.lenscast.streaming.StreamStateJournal

/**
 * The post-death camera bring-up prompt (blueprint P1 item 11 — the tier
 * ladder's T6, restart-reliability §3.2 / §5 option B, prior-art
 * "Takeaways" 1). When the process comes back from a death or reboot it
 * lands SERVER-ONLY: the server survives, but the while-in-use camera
 * standing died with the old process and no alarm/boot/battery exemption
 * ever restores it — visibility is the only lever. This controller posts the
 * one notification that buys visibility: a full-screen intent that
 * auto-launches [LockBypassActivity] over locked/off screens (granted),
 * restarting the journal-recorded outputs while visible so the CAMERA-typed
 * promotion and the bind land legitimately.
 *
 * **The opt-in tension.** This is the ONLY unattended path that restores
 * CAMERA after process death, and it buys it by lighting the screen —
 * directly against [AppRelaunch]'s service-first headless promise ("a
 * headless phone restarted from its dashboard must come back serving without
 * lighting up"). Hence the `fsi_camera_resume` setting defaults OFF and the
 * wiring routes it through the Settings Applier like every streaming flag;
 * a user who turns it on has accepted the screen-wake trade-off for the
 * camera-recovery win. On Android 14+ Play installs the FSI permission is
 * revoked at install (fsi-limits), so the store flavor runs the
 * [FsiResumePolicy.Verdict.POST_TAP_FALLBACK] kind — the same high-priority
 * heads-up with a content tap the denied-promotion fallback in
 * StreamingService uses — and the tap still lands the visible start.
 *
 * **The trigger and the debounce.** [onServerOnlyLanded] is called from the
 * runtime service's server-only promotion funnel — every relaunch (crash
 * handler, deep restart), sticky restart, and heartbeat landing passes
 * through it, so the heartbeat's 15-minute cadence re-evaluates the same
 * death event repeatedly. The decision is the pure [FsiResumePolicy]; the
 * once-per-death-event debounce is the journal's `fsiResumePrompted` marker
 * (never re-prompt until the marker clears, which `recordLiveOutputs` does
 * when an output actually goes live — the camera came back and the next
 * death may prompt again). A swipe-kill stands down the whole prompt
 * (nothing may nag about a stream the user deliberately killed), and a held
 * camera (an output already live — the boot-resume case) suppresses here so
 * this prompt and [LockBypassController]'s locked-mount wake never double
 * fire for the same state.
 *
 * **Teardown.** The notification auto-cancels on tap and is canceled by
 * [LockBypassActivity]'s dismissal epilogue and by MainActivity's resume
 * (any visible app is the recovery this prompt exists to reach); the marker
 * itself only clears through the journal's live-output path, so a prompt
 * whose recovery failed stays spent — honest silence beats re-nagging.
 *
 * Like [LockBypassController], this class owns the Android reads
 * (POST_NOTIFICATIONS, the full-screen-intent permission) and the posting;
 * the decision ladder lives in [FsiResumePolicy] and is JVM-tested there.
 */
class FsiResumeController(
    private val context: Context,
    private val journal: StreamStateJournal,
    /**
     * Whether camera access is currently held — wired to
     * `StreamingManager.isLiveStreaming()` in production (a live output owns
     * the camera). A lambda, not a flow: the verdict is read per evaluation,
     * exactly like the heartbeat's health seam.
     */
    private val cameraAccessHeld: () -> Boolean,
) {

    /**
     * The applied opt-in setting, pushed by the Settings Applier's dedicated
     * single-flow rule (the rtsp-tls pattern). Fresh-process window: the
     * first applier emission can carry the descriptor default before the
     * store's disk read lands — immaterial here, because every trigger
     * landing is at least the relaunch delay (~2 s) after process start,
     * well past DataStore's first read, and the heartbeat re-evaluates
     * regardless.
     */
    @Volatile
    private var settingEnabled = false

    /** The Settings Applier's apply target — the same exactly-once discipline as every runtime flag. */
    fun onSettingApplied(enabled: Boolean) {
        settingEnabled = enabled
    }

    /**
     * One evaluation of the post-death server-only state. Synchronous and
     * cheap (two small file-backed reads + permission checks) — the service's
     * main-thread landing calls it directly, the same class of work
     * `onTaskRemoved`'s journal write already does on main.
     */
    fun onServerOnlyLanded() {
        val state = try {
            journal.load()
        } catch (e: Exception) {
            // An unreadable journal cannot say "was live" — fail toward
            // silence, the same tolerance the boot path documents.
            Log.w(TAG, "Journal unreadable; skipping the FSI resume evaluation: ${e.javaClass.simpleName}")
            return
        }
        val verdict = FsiResumePolicy.decide(
            settingEnabled = settingEnabled,
            // The camera-bearing witness, not raw liveness — the prompt is
            // the CAMERA tier and keys on whether something camera-bearing
            // is recorded. Today the journal writes `web || rtsp` into it
            // (every live output binds the camera through the session), so
            // this reads the same answer anyLive would give; the field is
            // the journal's explicit camera distinction (StreamStateJournal
            // State.cameraBearing), which a future non-camera output kind
            // would clear — and then this gate suppresses, no shape change.
            journalSaysCameraBearing = state.cameraBearing,
            userStopped = state.userStopped,
            cameraAccessHeld = try {
                cameraAccessHeld()
            } catch (_: Exception) {
                false
            },
            alreadyPrompted = state.fsiResumePrompted,
            canUseFullScreenIntent = NotificationManagerCompat.from(context).canUseFullScreenIntent(),
        )
        if (verdict != FsiResumePolicy.Verdict.POST_FULL_SCREEN &&
            verdict != FsiResumePolicy.Verdict.POST_TAP_FALLBACK
        ) {
            Log.d(TAG, "FSI resume skipped: $verdict")
            return
        }
        if (!postNotificationsGranted()) {
            // No way to post anything — skip WITHOUT spending the
            // once-per-death marker, so a later grant can still prompt.
            Log.w(TAG, "FSI resume unavailable: POST_NOTIFICATIONS not granted")
            return
        }
        // Mark BEFORE posting: the debounce must hold even if the post
        // itself fails — a failed post may not turn the heartbeat cadence
        // into a re-prompt loop (the "don't nag" bias the marker exists for).
        runCatching { journal.markFsiResumePrompted() }
            .onFailure { Log.w(TAG, "Failed to record the prompted marker: ${it.javaClass.simpleName}") }
        post(fullScreen = verdict == FsiResumePolicy.Verdict.POST_FULL_SCREEN)
    }

    /** Removes the prompt — the activity's dismissal epilogue and MainActivity's resume. */
    fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(ForegroundNotifications.FSI_RESUME_NOTIFICATION_ID) }
    }

    /** POST_NOTIFICATIONS is a runtime permission only from API 33; before it, posting needs no grant. */
    private fun postNotificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun post(fullScreen: Boolean) {
        runCatching {
            ForegroundNotifications.createChannel(
                context,
                ForegroundNotifications.FSI_RESUME_CHANNEL_ID,
                context.getString(R.string.fsi_resume_channel_name),
                importance = android.app.NotificationManager.IMPORTANCE_HIGH,
                description = context.getString(R.string.fsi_resume_channel_description),
            )
            // Both the auto-launch (FSI) and the manual tap land the same
            // resume shim: unlike the lock-bypass wake (whose content tap
            // goes to MainActivity because the stream is already live and
            // only visibility is missing), this prompt's outputs are DOWN —
            // the landing must START, and LockBypassActivity with the resume
            // extra is the seam that starts while visible. A notification
            // interaction is a documented while-in-use exemption, so the tap
            // kind is as legitimate as the auto-launch.
            val resumeIntent = PendingIntent.getActivity(
                context,
                // A distinct request code: LockBypassController's wake uses 0
                // for the same component, and FLAG_UPDATE_CURRENT would have
                // the two PendingIntents replace each other system-wide.
                REQUEST_CODE,
                Intent(context, LockBypassActivity::class.java)
                    .putExtra(LockBypassActivity.EXTRA_FSI_RESUME, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, ForegroundNotifications.FSI_RESUME_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(context.getString(R.string.fsi_resume_title))
                .setContentText(context.getString(R.string.fsi_resume_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(resumeIntent)
            if (fullScreen) {
                builder.setFullScreenIntent(resumeIntent, true)
            }
            NotificationManagerCompat.from(context)
                .notify(ForegroundNotifications.FSI_RESUME_NOTIFICATION_ID, builder.build())
            Log.d(TAG, "FSI resume notification posted (fullScreen=$fullScreen)")
        }.onFailure {
            Log.w(TAG, "FSI resume notification failed: ${it.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "FsiResumeController"
        private const val REQUEST_CODE = 1
    }
}
