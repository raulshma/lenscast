import { describe, expect, it } from 'vitest'
import {
  RESET_REASON_BUDGET_SPENT,
  RESET_REASON_CAMERA_REMOVED,
  RESET_REASON_REBIND_DENIED,
  RESET_REASON_REBOOT_REQUIRED,
  RESTART_REASON_EXACT_ALARM_NOT_GRANTED,
  RESTART_REASON_SOFT_RESTART_FAILED,
  reliabilityAdvisories,
  resetCameraFailureDisplay,
  restartRefusalDisplay,
} from './reliabilityHints'

// Blueprint items 7/8 banner + refusal logic: which GET /api/system facts
// raise which advisory (and which stay quiet), and how the typed restart
// refusal maps to a localized key — with the device hint as the fallback
// for reasons the dashboard does not know yet.

describe('reliabilityAdvisories', () => {
  it('the unrestricted 24/7 posture stays quiet', () => {
    expect(
      reliabilityAdvisories({ canScheduleExactAlarms: true, batteryOptimizationState: 'UNRESTRICTED' }),
    ).toEqual([])
  })

  it('a missing exact-alarm grant warns about the restart path', () => {
    expect(
      reliabilityAdvisories({ canScheduleExactAlarms: false, batteryOptimizationState: 'UNRESTRICTED' }),
    ).toEqual([{ level: 'warning', messageKey: 'system.reliability.exactAlarms' }])
  })

  it('an absent grant answer (below API 31) is not an advisory', () => {
    expect(
      reliabilityAdvisories({ canScheduleExactAlarms: undefined, batteryOptimizationState: 'UNRESTRICTED' }),
    ).toEqual([])
  })

  it('the restricted battery state warns', () => {
    expect(
      reliabilityAdvisories({ canScheduleExactAlarms: true, batteryOptimizationState: 'RESTRICTED' }),
    ).toEqual([{ level: 'warning', messageKey: 'system.reliability.restricted' }])
  })

  it('the optimized battery state gently suggests unrestricted', () => {
    expect(
      reliabilityAdvisories({ canScheduleExactAlarms: true, batteryOptimizationState: 'OPTIMIZED' }),
    ).toEqual([{ level: 'info', messageKey: 'system.reliability.optimized' }])
  })

  it('a blocked grant and a restricted battery stack, warning first', () => {
    expect(
      reliabilityAdvisories({ canScheduleExactAlarms: false, batteryOptimizationState: 'RESTRICTED' }),
    ).toEqual([
      { level: 'warning', messageKey: 'system.reliability.exactAlarms' },
      { level: 'warning', messageKey: 'system.reliability.restricted' },
    ])
  })
})

describe('restartRefusalDisplay', () => {
  it('maps the exact-alarm refusal to its localized key', () => {
    expect(
      restartRefusalDisplay({ success: false, reason: RESTART_REASON_EXACT_ALARM_NOT_GRANTED, hint: 'device text' }),
    ).toEqual({ messageKey: 'system.restartDenied.exactAlarm' })
  })

  it('falls back to the device hint for an unknown reason', () => {
    expect(restartRefusalDisplay({ success: false, reason: 'something_new', hint: 'device text' })).toEqual({
      deviceHint: 'device text',
    })
  })

  it('falls back to the generic key when an unknown reason carries no hint', () => {
    expect(restartRefusalDisplay({ success: false, reason: 'something_new' })).toEqual({
      messageKey: 'system.restartDenied.generic',
    })
  })

  it('a hint-less known reason still localizes', () => {
    expect(restartRefusalDisplay({ success: false, reason: RESTART_REASON_EXACT_ALARM_NOT_GRANTED })).toEqual({
      messageKey: 'system.restartDenied.exactAlarm',
    })
  })

  it('maps the soft-restart ladder failure to its localized key (blueprint item 9)', () => {
    expect(
      restartRefusalDisplay({ success: false, mode: 'soft', reason: RESTART_REASON_SOFT_RESTART_FAILED, step: 'startServer' }),
    ).toEqual({ messageKey: 'system.restartDenied.softFailed' })
  })
})

describe('resetCameraFailureDisplay (blueprint item 10)', () => {
  it('maps every known policy reason to its localized key', () => {
    expect(resetCameraFailureDisplay({ success: false, verdict: 'REBIND', reason: RESET_REASON_REBIND_DENIED })).toEqual({
      messageKey: 'system.resetCameraDenied.rebind',
    })
    expect(resetCameraFailureDisplay({ success: false, verdict: 'CAMERAX_HANDLES', reason: RESET_REASON_BUDGET_SPENT })).toEqual({
      messageKey: 'system.resetCameraDenied.budget',
    })
    expect(resetCameraFailureDisplay({ success: false, verdict: 'REBOOT_REQUIRED', reason: RESET_REASON_REBOOT_REQUIRED })).toEqual({
      messageKey: 'system.resetCameraDenied.reboot',
    })
    expect(resetCameraFailureDisplay({ success: false, verdict: 'WAIT_CAMERA_ADDED', reason: RESET_REASON_CAMERA_REMOVED })).toEqual({
      messageKey: 'system.resetCameraDenied.removed',
    })
  })

  it('falls back to the device hint for an unknown reason', () => {
    expect(resetCameraFailureDisplay({ success: false, reason: 'something_new', hint: 'device text' })).toEqual({
      deviceHint: 'device text',
    })
  })

  it('falls back to the generic key when an unknown reason carries no hint', () => {
    expect(resetCameraFailureDisplay({ success: false, reason: 'something_new' })).toEqual({
      messageKey: 'system.resetCameraDenied.generic',
    })
  })
})
