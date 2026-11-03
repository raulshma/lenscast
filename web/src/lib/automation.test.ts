import { describe, expect, it } from 'vitest'
import {
  AUTO_SENTINEL_PARAM_NAMES,
  automationActionLabelKey,
  automationParamLabelKey,
  automationSummaryLines,
  canonicalFloatValue,
  canonicalIntValue,
  canonicalParamValue,
  defaultActionParams,
  formatMinuteOfDay,
  isAutoSentinelParam,
  isEveryDayMask,
  newAutomationRule,
  paramSliderBounds,
  parseMinuteOfDay,
} from './automation'
import type { AutomationCatalogActionDto } from '../types'


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
      minuteOfDay: 480,
      daysMask: 127,
      actions: [],
    })
  })

  it('slider bounds fall back safely around the catalog values', () => {
    expect(paramSliderBounds({ min: 1, max: 8 })).toEqual({ min: 1, max: 8 })
    expect(paramSliderBounds({ min: null, max: 1000 })).toEqual({ min: 0, max: 1000 })
    expect(paramSliderBounds({ min: -12, max: null })).toEqual({ min: -12, max: 100 })
    expect(paramSliderBounds({})).toEqual({ min: 0, max: 100 })
  })
})
