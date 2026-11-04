import { describe, expect, it } from 'vitest'
import {
  AUTOMATION_ACTION_GROUPS,
  AUTOMATION_CONDITION_GROUPS,
  AUTOMATION_COOLDOWN_PRESETS,
  AUTOMATION_TRIGGER_GROUPS,
  AUTO_SENTINEL_PARAM_NAMES,
  automationActionLabelKey,
  automationConditionLabelKey,
  automationConditionSummaryLines,
  automationDayLabelKey,
  automationParamLabelKey,
  automationSummaryLines,
  automationTriggerLabelKey,
  automationTriggerSummaryLines,
  canonicalFloatValue,
  canonicalIntValue,
  canonicalParamValue,
  defaultActionParams,
  defaultConditionParams,
  defaultTriggerParams,
  formatMinuteOfDay,
  groupedPickerOptions,
  humanizeDuration,
  isAutoSentinelParam,
  isEveryDayMask,
  isRuleValid,
  lastFiredLabel,
  moveSpec,
  newAutomationRule,
  paramSliderBounds,
  parseMinuteOfDay,
  withRuleDuplicated,
  withSpecParam,
} from './automation'
import type { AutomationCatalogActionDto, AutomationRuleDto } from '../types'


/** Assert the list holds exactly one line and return it — the Kotlin tests' `.single()`. */
function single<T>(lines: T[]): T {
  expect(lines).toHaveLength(1)
  return lines[0]
}

// Mirrors the app's AutomationUiModelTest so the web summary lines, canonical
// renderers and display helpers cannot drift from the JVM-tested Kotlin
// companion — the two UIs must render the same rule the same way.

describe('automation action summaries', () => {
  it('torch on with strength renders two lines', () => {
    const lines = automationSummaryLines({ type: 'torch', params: { enabled: 'true', strength: '3' } })
    expect(lines).toHaveLength(2)
    expect(lines[0].key).toBe('automation.summary.torchOn')
    expect(lines[1].key).toBe('automation.summary.torchStrength')
    expect(lines[1].params).toEqual({ value: '3' })
  })

  it('torch off folds its strength away', () => {
    const lines = automationSummaryLines({ type: 'torch', params: { enabled: 'false', strength: '3' } })
    expect(lines).toHaveLength(1)
    expect(lines[0]).toEqual({ key: 'automation.summary.torchOff' })
  })

  it('camera exposure renders one line per param with auto sentinels', () => {
    const lines = automationSummaryLines({ type: 'camera_exposure', params: { iso: '4000', exposure_time_ms: '0' } })
    expect(lines.map((line) => line.key)).toEqual(['automation.summary.iso', 'automation.summary.exposureAuto'])
    expect(lines[0].params).toEqual({ value: '4000' })
  })

  it('stream start and stop follow the enabled param', () => {
    expect(single(automationSummaryLines({ type: 'stream', params: { enabled: 'true' } })).key).toBe('automation.summary.streamStart')
    expect(single(automationSummaryLines({ type: 'stream', params: { enabled: 'false' } })).key).toBe('automation.summary.streamStop')
    // Absent folds to the "on" verdict, like the Kotlin `!= "false"` check.
    expect(single(automationSummaryLines({ type: 'stream', params: {} })).key).toBe('automation.summary.streamStart')
  })

  it('recording renders the bounded duration, plain start and stop shapes', () => {
    const bounded = single(automationSummaryLines({ type: 'recording', params: { enabled: 'true', duration_seconds: '300' } }))
    expect(bounded.key).toBe('automation.summary.recordingDuration')
    expect(bounded.params).toEqual({ value: 300 })
    // "0" and absent are the unbounded sentinels.
    expect(single(automationSummaryLines({ type: 'recording', params: { enabled: 'true', duration_seconds: '0' } })).key).toBe('automation.summary.recordingStart')
    expect(single(automationSummaryLines({ type: 'recording', params: { enabled: 'true' } })).key).toBe('automation.summary.recordingStart')
    expect(single(automationSummaryLines({ type: 'recording', params: { enabled: 'false' } })).key).toBe('automation.summary.recordingStop')
  })

  it('siren renders the plain, stop and bounded shapes', () => {
    expect(single(automationSummaryLines({ type: 'siren', params: { enabled: 'true' } })).key).toBe('automation.summary.siren')
    expect(single(automationSummaryLines({ type: 'siren', params: { enabled: 'false' } })).key).toBe('automation.summary.sirenStop')
    const bounded = single(automationSummaryLines({ type: 'siren', params: { enabled: 'true', duration_seconds: '30' } }))
    expect(bounded.key).toBe('automation.summary.sirenDuration')
    expect(bounded.params).toEqual({ value: 30 })
  })

  it('photo renders its single line without params', () => {
    expect(single(automationSummaryLines({ type: 'capture_photo', params: {} }))).toEqual({ key: 'automation.summary.photo' })
  })

  it('detection renders one line per present gate', () => {
    const lines = automationSummaryLines({ type: 'detection', params: { motion_enabled: 'true', sound_enabled: 'false' } })
    expect(lines.map((line) => line.key)).toEqual(['automation.summary.motionOn', 'automation.summary.soundOff'])
  })

  it('unknown action types render the shared unknown line', () => {
    expect(single(automationSummaryLines({ type: 'mystery', params: {} })).key).toBe('automation.action.unknown')
  })

  it('camera ev, zoom, fps and lens render their numeric lines', () => {
    expect(single(automationSummaryLines({ type: 'camera_ev', params: { index: '-2' } }))).toEqual({
      key: 'automation.summary.ev',
      params: { value: '-2' },
    })
    expect(single(automationSummaryLines({ type: 'camera_zoom', params: { ratio: '2.5' } })).key).toBe('automation.summary.zoom')
    expect(single(automationSummaryLines({ type: 'camera_fps', params: { fps: '24' } })).key).toBe('automation.summary.fps')
    expect(single(automationSummaryLines({ type: 'camera_lens', params: { index: '1' } })).key).toBe('automation.summary.lens')
  })
})

describe('automation label keys', () => {
  it('every catalog action id has a pretty-label key, unknown ids fall back', () => {
    expect(automationActionLabelKey('torch')).toBe('automation.action.torch')
    expect(automationActionLabelKey('camera_exposure')).toBe('automation.action.camera_exposure')
    expect(automationActionLabelKey('capture_photo')).toBe('automation.action.capture_photo')
    expect(automationActionLabelKey('mystery')).toBe('automation.action.unknown')
  })

  it('param labels disambiguate by type where the catalog reuses names', () => {
    expect(automationParamLabelKey('camera_lens', 'index')).toBe('automation.param.lensIndex')
    expect(automationParamLabelKey('camera_ev', 'index')).toBe('automation.param.evIndex')
    expect(automationParamLabelKey('torch', 'enabled')).toBe('automation.param.torch')
    expect(automationParamLabelKey('stream', 'enabled')).toBe('automation.param.stream')
    expect(automationParamLabelKey('recording', 'enabled')).toBe('automation.param.recording')
    expect(automationParamLabelKey('siren', 'enabled')).toBe('automation.param.siren')
    expect(automationParamLabelKey('unknown_type', 'enabled')).toBe('automation.param.enabled')
    expect(automationParamLabelKey('torch', 'strength')).toBe('automation.param.strength')
    expect(automationParamLabelKey('recording', 'duration_seconds')).toBe('automation.param.duration')
    expect(automationParamLabelKey('detection', 'motion_enabled')).toBe('automation.param.motion')
    expect(automationParamLabelKey('detection', 'sound_enabled')).toBe('automation.param.sound')
    expect(automationParamLabelKey('camera_exposure', 'iso')).toBe('automation.param.iso')
    expect(automationParamLabelKey('camera_exposure', 'exposure_time_ms')).toBe('automation.param.exposureTime')
  })
})

describe('automation canonical param values', () => {
  it('INT values round to whole numbers without decimals', () => {
    expect(canonicalIntValue(3.4)).toBe('3')
    expect(canonicalIntValue(4000)).toBe('4000')
    expect(canonicalIntValue(0.6)).toBe('1')
  })

  it('FLOAT values round to one decimal and lose the trailing .0', () => {
    expect(canonicalFloatValue(1.0)).toBe('1')
    expect(canonicalFloatValue(1.04)).toBe('1')
    expect(canonicalFloatValue(1.06)).toBe('1.1')
    expect(canonicalFloatValue(2.5)).toBe('2.5')
  })

  it('canonicalParamValue picks the renderer by param kind', () => {
    expect(canonicalParamValue({ kind: 'INT' }, 7.6)).toBe('8')
    expect(canonicalParamValue({ kind: 'FLOAT' }, 7.66)).toBe('7.7')
    expect(canonicalParamValue({ kind: 'BOOL' }, 1)).toBe('1')
  })
})

describe('automation time and day display', () => {
  it('formatMinuteOfDay renders zero-padded 24-hour clock, clamped into the day', () => {
    expect(formatMinuteOfDay(0)).toBe('00:00')
    expect(formatMinuteOfDay(8 * 60 + 5)).toBe('08:05')
    expect(formatMinuteOfDay(1439)).toBe('23:59')
    expect(formatMinuteOfDay(1500)).toBe('23:59')
    expect(formatMinuteOfDay(-5)).toBe('00:00')
  })

  it('parseMinuteOfDay is the format twin and rejects malformed values', () => {
    expect(parseMinuteOfDay('08:05')).toBe(8 * 60 + 5)
    expect(parseMinuteOfDay('23:59')).toBe(1439)
    expect(parseMinuteOfDay(' 07:00 ')).toBe(420)
    expect(parseMinuteOfDay('24:00')).toBe(null)
    expect(parseMinuteOfDay('07:60')).toBe(null)
    expect(parseMinuteOfDay('7am')).toBe(null)
    expect(parseMinuteOfDay('')).toBe(null)
    for (const minute of [0, 420, 1439]) {
      expect(parseMinuteOfDay(formatMinuteOfDay(minute))).toBe(minute)
    }
  })

  it('the every-day mask is exactly 127', () => {
    expect(isEveryDayMask(127)).toBe(true)
    expect(isEveryDayMask(0b0011111)).toBe(false)
  })
})

describe('automation rule construction', () => {
  it('the auto-sentinel fallback set covers exactly the camera_exposure auto params', () => {
    expect(AUTO_SENTINEL_PARAM_NAMES).toEqual(['iso', 'exposure_time_ms'])
  })

  it('isAutoSentinelParam follows the catalog autoAtZero flag when the action id is catalog-known', () => {
    const cameraExposure: Pick<AutomationCatalogActionDto, 'params'> = {
      params: [
        { name: 'iso', kind: 'INT', min: 0, max: 10000, optional: true, defaultValue: '0', autoAtZero: true },
        { name: 'exposure_time_ms', kind: 'FLOAT', min: 0, max: 1000, optional: true, defaultValue: '0', autoAtZero: true },
      ],
    }
    expect(isAutoSentinelParam(cameraExposure, { name: 'iso' })).toBe(true)
    expect(isAutoSentinelParam(cameraExposure, { name: 'exposure_time_ms' })).toBe(true)
    // The catalog flag wins over the hardcoded set: "0" on
    // recording.duration_seconds is the no-limit sentinel, NOT auto.
    const recording: Pick<AutomationCatalogActionDto, 'params'> = {
      params: [
        { name: 'duration_seconds', kind: 'INT', min: 0, max: 86400, optional: true, defaultValue: '0', autoAtZero: false },
      ],
    }
    expect(isAutoSentinelParam(recording, { name: 'duration_seconds' })).toBe(false)
  })

  it('isAutoSentinelParam falls back to the name-set only for ids absent from the catalog', () => {
    // No catalog entry (pre-fetch or stale firmware): the legacy name-set decides.
    expect(isAutoSentinelParam(undefined, { name: 'iso' })).toBe(true)
    expect(isAutoSentinelParam(undefined, { name: 'exposure_time_ms' })).toBe(true)
    expect(isAutoSentinelParam(undefined, { name: 'fps' })).toBe(false)
    // Catalog-known action but the param name missing from its schema: same fallback.
    const torch: Pick<AutomationCatalogActionDto, 'params'> = {
      params: [{ name: 'enabled', kind: 'BOOL', optional: false, defaultValue: 'true', autoAtZero: false }],
    }
    expect(isAutoSentinelParam(torch, { name: 'iso' })).toBe(true)
  })

  it('fresh actions seed required params at their defaults, optional ones absent', () => {
    const torchCatalog = {
      params: [
        { name: 'enabled', kind: 'BOOL' as const, optional: false, defaultValue: 'true', autoAtZero: false },
        { name: 'strength', kind: 'INT' as const, min: 1, max: 8, optional: true, defaultValue: '', autoAtZero: false },
      ],
    }
    expect(defaultActionParams(torchCatalog)).toEqual({ enabled: 'true' })
    expect(defaultActionParams({ params: [] })).toEqual({})
    expect(defaultActionParams(undefined)).toEqual({})
  })

  it('fresh rules draft at 08:00 every day with a blank id and no actions', () => {
    const rule = newAutomationRule()
    expect(rule).toEqual({
      id: '',
      name: '',
      enabled: true,
      triggerType: 'time_of_day',
      minuteOfDay: 480,
      daysMask: 127,
      triggerParams: {},
      conditions: [],
      conditionMode: 'all',
      cooldownSeconds: 60,
      actions: [],
    })
  })

  it('withSpecParam writes, overwrites and removes keys immutably', () => {
    expect(withSpecParam({}, 'level', '20')).toEqual({ level: '20' })
    expect(withSpecParam({ level: '20' }, 'level', '35')).toEqual({ level: '35' })
    // Null/empty remove the key (the optional-param "leave unchanged" shape).
    expect(withSpecParam({ level: '20' }, 'level', null)).toEqual({})
    expect(withSpecParam({ level: '20' }, 'level', '')).toEqual({})
    expect(withSpecParam({ level: '20' }, 'level', undefined)).toEqual({})
    // The input map is never mutated.
    const original = { level: '20' }
    expect(withSpecParam(original, 'level', '35')).toEqual({ level: '35' })
    expect(original).toEqual({ level: '20' })
  })

  it('slider bounds fall back safely around the catalog values', () => {
    expect(paramSliderBounds({ min: 1, max: 8 })).toEqual({ min: 1, max: 8 })
    expect(paramSliderBounds({ min: null, max: 1000 })).toEqual({ min: 0, max: 1000 })
    expect(paramSliderBounds({ min: -12, max: null })).toEqual({ min: -12, max: 100 })
    expect(paramSliderBounds({})).toEqual({ min: 0, max: 100 })
  })
})

describe('automation trigger and condition label keys', () => {
  it('every trigger id gets its catalog key, unknown ids fall back', () => {
    expect(automationTriggerLabelKey('time_of_day')).toBe('automation.trigger.time_of_day')
    expect(automationTriggerLabelKey('motion_detected')).toBe('automation.trigger.motion_detected')
    expect(automationTriggerLabelKey('battery_level')).toBe('automation.trigger.battery_level')
    expect(automationTriggerLabelKey('app_started')).toBe('automation.trigger.app_started')
    expect(automationTriggerLabelKey('teleport_event')).toBe('automation.action.unknown')
  })

  it('every condition id gets its catalog key, unknown ids fall back', () => {
    expect(automationConditionLabelKey('time_window')).toBe('automation.condition.time_window')
    expect(automationConditionLabelKey('battery_level')).toBe('automation.condition.battery_level')
    expect(automationConditionLabelKey('motion_detection')).toBe('automation.condition.motion_detection')
    expect(automationConditionLabelKey('moon_phase')).toBe('automation.action.unknown')
  })

  it('param labels resolve the typeId/paramName override ladder before the bare-name fallback', () => {
    // Trigger overrides — "connected" reads as viewer presence, not Wi-Fi.
    expect(automationParamLabelKey('battery_level', 'level')).toBe('automation.param.batteryThreshold')
    expect(automationParamLabelKey('battery_level', 'when_below')).toBe('automation.param.whenBelow')
    expect(automationParamLabelKey('charging_changed', 'charging')).toBe('automation.param.chargingPlugged')
    expect(automationParamLabelKey('streaming_changed', 'streaming')).toBe('automation.param.streamingActive')
    expect(automationParamLabelKey('viewers_changed', 'connected')).toBe('automation.param.viewersConnected')
    // Condition overrides — the same bare names now resolve per-condition.
    expect(automationParamLabelKey('wifi_connected', 'connected')).toBe('automation.param.wifiConnected')
    expect(automationParamLabelKey('time_window', 'start_minute')).toBe('automation.param.windowStart')
    expect(automationParamLabelKey('time_window', 'end_minute')).toBe('automation.param.windowEnd')
    expect(automationParamLabelKey('time_window', 'days_mask')).toBe('automation.param.windowDays')
    expect(automationParamLabelKey('battery_level', 'min_level')).toBe('automation.param.batteryMin')
    expect(automationParamLabelKey('battery_level', 'max_level')).toBe('automation.param.batteryMax')
    expect(automationParamLabelKey('power_save', 'enabled')).toBe('automation.param.powerSaveOn')
    expect(automationParamLabelKey('viewers', 'min_count')).toBe('automation.param.viewersMin')
    expect(automationParamLabelKey('viewers', 'max_count')).toBe('automation.param.viewersMax')
    expect(automationParamLabelKey('torch', 'on')).toBe('automation.param.torchOn')
    expect(automationParamLabelKey('recording', 'active')).toBe('automation.param.recordingActive')
    expect(automationParamLabelKey('thermal', 'normal')).toBe('automation.param.thermalNormal')
    expect(automationParamLabelKey('motion_detection', 'enabled')).toBe('automation.param.motion')
    // Unknown type + bare name rides the existing fallback ladder.
    expect(automationParamLabelKey('mystery_trigger', 'charging')).toBe('automation.param.enabled')
  })

  it('fresh trigger params seed required params at their defaults, optional ones absent', () => {
    const chargingCatalog = {
      params: [{ name: 'charging', kind: 'BOOL' as const, optional: false, defaultValue: 'true', autoAtZero: false }],
    }
    expect(defaultTriggerParams(chargingCatalog)).toEqual({ charging: 'true' })
    const batteryCatalog = {
      params: [
        { name: 'level', kind: 'INT' as const, min: 1, max: 99, optional: false, defaultValue: '20', autoAtZero: false },
        { name: 'when_below', kind: 'BOOL' as const, optional: true, defaultValue: 'true', autoAtZero: false },
      ],
    }
    expect(defaultTriggerParams(batteryCatalog)).toEqual({ level: '20' })
    expect(defaultTriggerParams(undefined)).toEqual({})
  })

  it('fresh condition params seed required params at their defaults, optional ones absent', () => {
    const timeWindowCatalog = {
      params: [
        { name: 'start_minute', kind: 'INT' as const, min: 0, max: 1439, optional: true, defaultValue: '0', autoAtZero: false },
        { name: 'end_minute', kind: 'INT' as const, min: 0, max: 1439, optional: true, defaultValue: '1439', autoAtZero: false },
        { name: 'days_mask', kind: 'INT' as const, min: 1, max: 127, optional: true, defaultValue: '127', autoAtZero: false },
      ],
    }
    expect(defaultConditionParams(timeWindowCatalog)).toEqual({})
    expect(defaultConditionParams({ params: [] })).toEqual({})
    expect(defaultConditionParams(undefined)).toEqual({})
  })
})

describe('automation trigger summaries', () => {
  it('time-of-day rules render no trigger line (the card shows HH:mm · days)', () => {
    expect(automationTriggerSummaryLines({ triggerType: 'time_of_day', triggerParams: {} })).toEqual([])
  })

  it('paramless detection triggers render one line each', () => {
    expect(automationTriggerSummaryLines({ triggerType: 'motion_detected', triggerParams: {} })).toEqual([
      { key: 'automation.summary.triggerMotion' },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'sound_detected', triggerParams: {} })).toEqual([
      { key: 'automation.summary.triggerSound' },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'tamper_detected', triggerParams: {} })).toEqual([
      { key: 'automation.summary.triggerTamper' },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'app_started', triggerParams: {} })).toEqual([
      { key: 'automation.summary.triggerAppStarted' },
    ])
  })

  it('charging and streaming follow their BOOL param with the on default', () => {
    expect(automationTriggerSummaryLines({ triggerType: 'charging_changed', triggerParams: { charging: 'true' } })).toEqual([
      { key: 'automation.summary.triggerChargingOn' },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'charging_changed', triggerParams: { charging: 'false' } })).toEqual([
      { key: 'automation.summary.triggerChargingOff' },
    ])
    // Absent folds to the "on" verdict, like the Kotlin default.
    expect(automationTriggerSummaryLines({ triggerType: 'charging_changed', triggerParams: {} })).toEqual([
      { key: 'automation.summary.triggerChargingOn' },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'streaming_changed', triggerParams: { streaming: 'false' } })).toEqual([
      { key: 'automation.summary.triggerStreamStop' },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'viewers_changed', triggerParams: { connected: 'false' } })).toEqual([
      { key: 'automation.summary.triggerViewerLeave' },
    ])
  })

  it('battery renders below/above with the level, defaulting like the engine', () => {
    expect(automationTriggerSummaryLines({ triggerType: 'battery_level', triggerParams: { level: '15', when_below: 'true' } })).toEqual([
      { key: 'automation.summary.triggerBatteryBelow', params: { value: 15 } },
    ])
    expect(automationTriggerSummaryLines({ triggerType: 'battery_level', triggerParams: { level: '80', when_below: 'false' } })).toEqual([
      { key: 'automation.summary.triggerBatteryAbove', params: { value: 80 } },
    ])
    const defaulted = automationTriggerSummaryLines({ triggerType: 'battery_level', triggerParams: {} })
    expect(defaulted).toEqual([{ key: 'automation.summary.triggerBatteryBelow', params: { value: 20 } }])
    // A malformed level folds to the default, never NaN.
    expect(automationTriggerSummaryLines({ triggerType: 'battery_level', triggerParams: { level: 'bogus' } })).toEqual([
      { key: 'automation.summary.triggerBatteryBelow', params: { value: 20 } },
    ])
  })

  it('unknown trigger types render no line (the card already shows the unknown label)', () => {
    expect(automationTriggerSummaryLines({ triggerType: 'mystery', triggerParams: {} })).toEqual([])
  })
})

describe('automation condition summaries', () => {
  it('time windows render the formatted span with minute defaults', () => {
    expect(automationConditionSummaryLines({ type: 'time_window', params: { start_minute: '1320', end_minute: '360' } })).toEqual([
      { key: 'automation.summary.conditionTimeWindow', params: { start: '22:00', end: '06:00' } },
    ])
    // Absent minutes fold to the catalog defaults (00:00 → 23:59).
    expect(automationConditionSummaryLines({ type: 'time_window', params: {} })).toEqual([
      { key: 'automation.summary.conditionTimeWindow', params: { start: '00:00', end: '23:59' } },
    ])
  })

  it('BOOL conditions follow their param with the engine defaults', () => {
    expect(automationConditionSummaryLines({ type: 'charging', params: { charging: 'true' } })).toEqual([
      { key: 'automation.summary.conditionChargingOn' },
    ])
    expect(automationConditionSummaryLines({ type: 'charging', params: { charging: 'false' } })).toEqual([
      { key: 'automation.summary.conditionChargingOff' },
    ])
    // power_save defaults to OFF (the engine's "enabled" default is "false").
    expect(automationConditionSummaryLines({ type: 'power_save', params: {} })).toEqual([
      { key: 'automation.summary.conditionPowerSaveOff' },
    ])
    expect(automationConditionSummaryLines({ type: 'power_save', params: { enabled: 'true' } })).toEqual([
      { key: 'automation.summary.conditionPowerSaveOn' },
    ])
    expect(automationConditionSummaryLines({ type: 'wifi_connected', params: { connected: 'false' } })).toEqual([
      { key: 'automation.summary.conditionWifiOff' },
    ])
    expect(automationConditionSummaryLines({ type: 'streaming', params: { active: 'false' } })).toEqual([
      { key: 'automation.summary.conditionStreamingOff' },
    ])
    expect(automationConditionSummaryLines({ type: 'torch', params: { on: 'false' } })).toEqual([
      { key: 'automation.summary.conditionTorchOff' },
    ])
    expect(automationConditionSummaryLines({ type: 'recording', params: { active: 'true' } })).toEqual([
      { key: 'automation.summary.conditionRecordingOn' },
    ])
    expect(automationConditionSummaryLines({ type: 'thermal', params: { normal: 'false' } })).toEqual([
      { key: 'automation.summary.conditionThermalThrottling' },
    ])
    expect(automationConditionSummaryLines({ type: 'motion_detection', params: { enabled: 'false' } })).toEqual([
      { key: 'automation.summary.conditionMotionOff' },
    ])
  })

  it('battery renders between/min/max/any shapes off the present bounds', () => {
    expect(automationConditionSummaryLines({ type: 'battery_level', params: { min_level: '20', max_level: '80' } })).toEqual([
      { key: 'automation.summary.conditionBatteryBetween', params: { min: 20, max: 80 } },
    ])
    expect(automationConditionSummaryLines({ type: 'battery_level', params: { min_level: '20' } })).toEqual([
      { key: 'automation.summary.conditionBatteryMin', params: { value: 20 } },
    ])
    expect(automationConditionSummaryLines({ type: 'battery_level', params: { max_level: '80' } })).toEqual([
      { key: 'automation.summary.conditionBatteryMax', params: { value: 80 } },
    ])
    expect(automationConditionSummaryLines({ type: 'battery_level', params: {} })).toEqual([
      { key: 'automation.summary.conditionBatteryAny' },
    ])
  })

  it('viewers renders between/min/max/any shapes off the present bounds', () => {
    expect(automationConditionSummaryLines({ type: 'viewers', params: { min_count: '1', max_count: '5' } })).toEqual([
      { key: 'automation.summary.conditionViewersBetween', params: { min: 1, max: 5 } },
    ])
    expect(automationConditionSummaryLines({ type: 'viewers', params: { min_count: '2' } })).toEqual([
      { key: 'automation.summary.conditionViewersMin', params: { value: 2 } },
    ])
    expect(automationConditionSummaryLines({ type: 'viewers', params: { max_count: '0' } })).toEqual([
      { key: 'automation.summary.conditionViewersMax', params: { value: 0 } },
    ])
    expect(automationConditionSummaryLines({ type: 'viewers', params: {} })).toEqual([
      { key: 'automation.summary.conditionViewersAny' },
    ])
  })

  it('unknown condition types render no line (the card already shows the unknown label)', () => {
    expect(automationConditionSummaryLines({ type: 'mystery', params: {} })).toEqual([])
  })
})

// ── picker groups ──

describe('picker groups', () => {
  it('every catalog id is grouped exactly once within its catalog (the picker can never hide or duplicate an option)', () => {
    // The three id spaces overlap by design (torch / recording / battery_level
    // exist in two catalogs), so uniqueness holds per catalog, not across.
    for (const groups of [AUTOMATION_TRIGGER_GROUPS, AUTOMATION_ACTION_GROUPS, AUTOMATION_CONDITION_GROUPS]) {
      const grouped = groups.flatMap((g) => g.ids)
      expect(new Set(grouped).size).toBe(grouped.length)
    }
    // The known catalog ids (the app's AutomationVisuals coverage).
    const knownTriggers = ['time_of_day', 'motion_detected', 'sound_detected', 'tamper_detected', 'charging_changed', 'battery_level', 'streaming_changed', 'viewers_changed', 'app_started']
    const knownActions = ['camera_exposure', 'camera_ev', 'camera_zoom', 'camera_fps', 'camera_lens', 'torch', 'stream', 'capture_photo', 'recording', 'siren', 'detection']
    const knownConditions = ['time_window', 'charging', 'battery_level', 'power_save', 'wifi_connected', 'streaming', 'viewers', 'torch', 'recording', 'thermal', 'motion_detection']
    expect(AUTOMATION_TRIGGER_GROUPS.flatMap((g) => g.ids).sort()).toEqual([...knownTriggers].sort())
    expect(AUTOMATION_ACTION_GROUPS.flatMap((g) => g.ids).sort()).toEqual([...knownActions].sort())
    expect(AUTOMATION_CONDITION_GROUPS.flatMap((g) => g.ids).sort()).toEqual([...knownConditions].sort())
  })

  it('groupedPickerOptions filters to the ids the device publishes and parks unknowns under "Other"', () => {
    const groups = groupedPickerOptions(AUTOMATION_TRIGGER_GROUPS, ['time_of_day', 'motion_detected', 'mystery_trigger'])
    expect(groups.map((g) => g.titleKey)).toEqual(['automation.group.schedule', 'automation.group.deviceEvents', 'automation.group.other'])
    expect(groups[0].ids).toEqual(['time_of_day'])
    expect(groups[1].ids).toEqual(['motion_detected'])
    expect(groups[2].ids).toEqual(['mystery_trigger'])
    // A fully-known catalog renders no "Other" group.
    const all = groupedPickerOptions(AUTOMATION_ACTION_GROUPS, AUTOMATION_ACTION_GROUPS.flatMap((g) => g.ids))
    expect(all).toHaveLength(4)
    expect(all.at(-1)!.titleKey).not.toBe('automation.group.other')
    // Empty catalog renders no groups at all.
    expect(groupedPickerOptions(AUTOMATION_CONDITION_GROUPS, [])).toEqual([])
  })
})

// ── cooldown humanizing ──

describe('humanizeDuration', () => {
  it('rounds to the nearest unit with a floor of one (the JVM twin AutomationViewModel.humanizeDuration)', () => {
    expect(humanizeDuration(0)).toEqual({ key: 'automation.duration.seconds', params: { value: 0 } })
    expect(humanizeDuration(59)).toEqual({ key: 'automation.duration.seconds', params: { value: 59 } })
    expect(humanizeDuration(60)).toEqual({ key: 'automation.duration.minutes', params: { value: 1 } })
    expect(humanizeDuration(61)).toEqual({ key: 'automation.duration.minutes', params: { value: 1 } })
    expect(humanizeDuration(90)).toEqual({ key: 'automation.duration.minutes', params: { value: 2 } })
    expect(humanizeDuration(3599)).toEqual({ key: 'automation.duration.minutes', params: { value: 60 } })
    expect(humanizeDuration(3600)).toEqual({ key: 'automation.duration.hours', params: { value: 1 } })
    expect(humanizeDuration(5400)).toEqual({ key: 'automation.duration.hours', params: { value: 2 } })
    expect(humanizeDuration(-30)).toEqual({ key: 'automation.duration.seconds', params: { value: 0 } })
  })

  it('the preset chips land exactly on their unit', () => {
    expect(humanizeDuration(30).key).toBe('automation.duration.seconds')
    expect(humanizeDuration(300)).toEqual({ key: 'automation.duration.minutes', params: { value: 5 } })
    expect(humanizeDuration(900)).toEqual({ key: 'automation.duration.minutes', params: { value: 15 } })
    expect(humanizeDuration(1800)).toEqual({ key: 'automation.duration.minutes', params: { value: 30 } })
    expect(humanizeDuration(3600)).toEqual({ key: 'automation.duration.hours', params: { value: 1 } })
    expect(AUTOMATION_COOLDOWN_PRESETS).toEqual([30, 60, 300, 900, 1800, 3600])
  })
})

// ── fire status ──

describe('lastFiredLabel', () => {
  // Constructed in local time, so the buckets hold in every timezone.
  const now = new Date(2026, 10, 4, 12, 0, 0).getTime()
  const todayMs = new Date(2026, 10, 4, 11, 58, 30).getTime()
  const yesterdayMs = new Date(2026, 10, 3, 23, 59).getTime()
  const earlierMs = new Date(2026, 10, 1, 8, 15).getTime()

  it('buckets today by the local minute-of-day', () => {
    expect(lastFiredLabel(todayMs, now)).toEqual({ kind: 'today', minuteOfDay: 11 * 60 + 58 })
    // Same-day earlier instant (negative gap) still reads today.
    expect(lastFiredLabel(now - 1000, now)?.kind).toBe('today')
  })

  it('buckets yesterday and earlier', () => {
    expect(lastFiredLabel(yesterdayMs, now)).toEqual({ kind: 'yesterday', minuteOfDay: 23 * 60 + 59 })
    const earlier = lastFiredLabel(earlierMs, now)
    expect(earlier).toEqual({ kind: 'earlier', epochMillis: earlierMs, minuteOfDay: 8 * 60 + 15 })
  })

  it('a non-positive instant is never fired (null)', () => {
    expect(lastFiredLabel(0, now)).toBeNull()
    expect(lastFiredLabel(-5, now)).toBeNull()
  })

  it('buckets by the calendar day, not a fixed 24h window', () => {
    // 00:30 today against 01:00 today is "today" — and a different calendar
    // day is always yesterday/earlier, however close the instants are.
    const earlyToday = new Date(2026, 10, 4, 0, 30).getTime()
    const nowLate = new Date(2026, 10, 4, 1, 0).getTime()
    expect(lastFiredLabel(earlyToday, nowLate)?.kind).toBe('today')
    // 23:30 yesterday against 01:00 the day after tomorrow is "earlier".
    const yesterdayLate = new Date(2026, 10, 3, 23, 30).getTime()
    const nowLater = new Date(2026, 10, 5, 1, 0).getTime()
    expect(lastFiredLabel(yesterdayLate, nowLater)?.kind).toBe('earlier')
  })
})

// ── rule-list transforms ──

describe('withRuleDuplicated', () => {
  const a: AutomationRuleDto = { ...newAutomationRule(), id: 'a', name: 'A', enabled: false, lastFiredAt: 1234 }
  const b: AutomationRuleDto = { ...newAutomationRule(), id: 'b', name: 'B' }

  it('inserts after the source with a fresh id, the copy name, enabled and cleared fire status', () => {
    const rules = withRuleDuplicated([a, b], 'a', 'Copy of A', 16)
    expect(rules).toHaveLength(3)
    expect(rules[0].id).toBe('a')
    expect(rules[1].id).not.toBe('a')
    expect(rules[1].id).not.toBe('')
    expect(rules[1].name).toBe('Copy of A')
    expect(rules[1].enabled).toBe(true)
    expect(rules[1].lastFiredAt).toBeNull()
    expect(rules[2].id).toBe('b')
    // The source rule itself is untouched.
    expect(rules[0]).toEqual(a)
  })

  it('is a no-op for an unknown id or at the cap', () => {
    expect(withRuleDuplicated([a], 'zz', 'X', 16)).toEqual([a])
    const full = Array.from({ length: 16 }, (_, i) => ({ ...newAutomationRule(), id: `r${i}` }))
    expect(withRuleDuplicated(full, 'r0', 'Copy', 16)).toEqual(full)
  })
})

describe('moveSpec', () => {
  it('splices one slot up and down, no-op out of range', () => {
    expect(moveSpec(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c'])
    expect(moveSpec(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b'])
    expect(moveSpec(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
    expect(moveSpec(['a', 'b'], 1, 1)).toEqual(['a', 'b'])
    // The input list is never mutated.
    const list = ['a', 'b', 'c']
    moveSpec(list, 0, 1)
    expect(list).toEqual(['a', 'b', 'c'])
  })
})

describe('isRuleValid', () => {
  it('gates on at least one action', () => {
    expect(isRuleValid({ actions: [] })).toBe(false)
    expect(isRuleValid({ actions: [{ type: 'torch', params: { enabled: 'true' } }] })).toBe(true)
  })
})

describe('automationDayLabelKey', () => {
  it('clamps the ISO index into 0..6', () => {
    expect(automationDayLabelKey(0)).toBe('automation.day.0')
    expect(automationDayLabelKey(6)).toBe('automation.day.6')
    expect(automationDayLabelKey(-3)).toBe('automation.day.0')
    expect(automationDayLabelKey(9)).toBe('automation.day.6')
  })
})
