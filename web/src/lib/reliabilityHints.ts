import type { SystemInfo, SystemRestartResponse } from '../types'

// The reliability callouts (unattended-reliability-blueprint.md P0-B items
// 7/8): the GET /api/system grant/doze/battery facts and the typed restart
// refusal become dashboard-visible hints instead of silent overnight
// failures. Pure key-level logic here — the components translate the keys
// through t() so the messages localize like every other string.

/** The restart refusal reason the device sends when the relaunch alarm cannot arm. */
export const RESTART_REASON_EXACT_ALARM_NOT_GRANTED = 'exact_alarm_not_granted'

/** The soft-restart ladder's failure reason (blueprint item 9); `step` names the failed step. */
export const RESTART_REASON_SOFT_RESTART_FAILED = 'soft_restart_failed'

/** The reset-camera reasons the device sends (blueprint item 10). */
export const RESET_REASON_REBIND_DENIED = 'rebind_denied'
export const RESET_REASON_BUDGET_SPENT = 'rebind_budget_spent'
export const RESET_REASON_REBOOT_REQUIRED = 'reboot_required'
export const RESET_REASON_CAMERA_REMOVED = 'camera_removed'

/** How an advisory renders: warning banners alert, info banners just note. */
export interface ReliabilityAdvisory {
  level: 'warning' | 'info'
  /** The i18n key carrying the localized message. */
  messageKey: string
}

/** The GET /api/system fields the advisories read. */
export type ReliabilityFacts = Pick<
  SystemInfo,
  'canScheduleExactAlarms' | 'batteryOptimizationState'
>

/**
 * The reliability callouts for a system panel, most severe first. Absent
 * `canScheduleExactAlarms` (below API 31 — no grant model) is NOT an
 * advisory: there is nothing to grant there. UNRESTRICTED (allowlisted)
 * stays quiet — that is the 24/7 posture, and a green "all good" banner
 * would be noise.
 */
export function reliabilityAdvisories(facts: ReliabilityFacts): ReliabilityAdvisory[] {
  const advisories: ReliabilityAdvisory[] = []
  if (facts.canScheduleExactAlarms === false) {
    // The relaunch alarm cannot arm: a dashboard restart would refuse.
    advisories.push({ level: 'warning', messageKey: 'system.reliability.exactAlarms' })
  }
  switch (facts.batteryOptimizationState) {
    case 'RESTRICTED':
      // Restricted blocks background work outright — existing FGS demoted,
      // alarms untriggered (limits doc §4.2); the server cannot survive on it.
      advisories.push({ level: 'warning', messageKey: 'system.reliability.restricted' })
      break
    case 'OPTIMIZED':
      // The default state throttles the overnight run; Unrestricted is the
      // one-dialog fix (allowlist ⇒ no alarm/network limits).
      advisories.push({ level: 'info', messageKey: 'system.reliability.optimized' })
      break
  }
  return advisories
}

/**
 * The restart refusal's display decision: a localized message key for the
 * reasons the dashboard knows, otherwise the device's own hint text (an
 * unknown future reason should still surface verbatim rather than as a
 * generic shrug). `success:true` never reaches this — callers check it first.
 */
export function restartRefusalDisplay(
  result: SystemRestartResponse,
): { messageKey: string } | { deviceHint: string } {
  if (result.reason === RESTART_REASON_EXACT_ALARM_NOT_GRANTED) {
    return { messageKey: 'system.restartDenied.exactAlarm' }
  }
  if (result.reason === RESTART_REASON_SOFT_RESTART_FAILED) {
    return { messageKey: 'system.restartDenied.softFailed' }
  }
  return result.hint
    ? { deviceHint: result.hint }
    : { messageKey: 'system.restartDenied.generic' }
}

/**
 * The reset-camera answer's display decision (blueprint item 10): a localized
 * message key for the reasons the dashboard knows, otherwise the device's own
 * hint text. `success:true` never reaches this — callers check it first.
 */
export function resetCameraFailureDisplay(
  result: import('../types').CameraResetResponse,
): { messageKey: string } | { deviceHint: string } {
  switch (result.reason) {
    case RESET_REASON_REBIND_DENIED:
      return { messageKey: 'system.resetCameraDenied.rebind' }
    case RESET_REASON_BUDGET_SPENT:
      return { messageKey: 'system.resetCameraDenied.budget' }
    case RESET_REASON_REBOOT_REQUIRED:
      return { messageKey: 'system.resetCameraDenied.reboot' }
    case RESET_REASON_CAMERA_REMOVED:
      return { messageKey: 'system.resetCameraDenied.removed' }
  }
  return result.hint
    ? { deviceHint: result.hint }
    : { messageKey: 'system.resetCameraDenied.generic' }
}
