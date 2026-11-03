/**
 * The automation rule editor's pure helpers — the TS mirror of the app's
 * AutomationViewModel companion (JVM-tested in AutomationUiModelTest): the
 * pretty action/param labels, the per-action summary lines, the canonical
 * param-value renderers, and the minute-of-day / day-mask display math. No
 * Solid and no i18n imports: summary lines and labels come back as message
 * keys + params for the caller to resolve through t(), exactly like the
 * Kotlin side returns resource ids + args for stringResource.
 */
import type {
  AutomationActionDto,
  AutomationCatalogActionDto,
  AutomationCatalogParamDto,
  AutomationRuleDto,
} from '../types'

/** Every catalog action id the label/summary tables below cover (AutomationActionCatalog). */
export const AUTOMATION_ACTION_TYPES = [
  'camera_exposure',
  'camera_ev',
  'camera_zoom',
  'camera_fps',
  'camera_lens',
  'torch',
  'stream',
  'capture_photo',
  'recording',
  'siren',
  'detection',
] as const

/** Every day armed — the day-mask default (MotionArmingPolicy.ALL_DAYS_MASK). */
export const AUTOMATION_ALL_DAYS_MASK = 127

/**
 * The camera_exposure params whose "0" value is the documented auto sentinel
 * (AutomationScreen.AUTO_SENTINEL_PARAM_NAMES). Param names are globally
 * unique across the catalog, so a name check is safe.
 *
 * No longer the decision source: the catalog's `autoAtZero` flag is the
 * authority ([isAutoSentinelParam]); this set stays exported as the fallback
 * for action ids absent from the fetched catalog.
 */
export const AUTO_SENTINEL_PARAM_NAMES: readonly string[] = ['iso', 'exposure_time_ms']

/**
 * Whether "0" renders as the Auto/unlimited sentinel for one param row: the
 * catalog's `autoAtZero` flag decides when the action id is found in the
 * fetched catalog (device truth); the name-set is only the fallback for ids
 * (or param names) it doesn't know — pre-fetch or stale firmware. The Kotlin
 * twin is AutomationScreen's
 * `typeFor(id)?.params?.firstOrNull { it.name == spec.name }?.autoAtZero`
 * elvis onto the name-set.
 */
export function isAutoSentinelParam(
  catalogAction: Pick<AutomationCatalogActionDto, 'params'> | undefined,
  spec: Pick<AutomationCatalogParamDto, 'name'>,
): boolean {
  const catalogSpec = catalogAction?.params.find((param) => param.name === spec.name)
  // The catalog flag wins when the action is catalog-known (e.g. an id that
  // gains or loses the sentinel must not ride a stale hardcoded set).
  return catalogSpec?.autoAtZero ?? AUTO_SENTINEL_PARAM_NAMES.includes(spec.name)
}

/** One rendered action-summary line: the message key plus its interpolation params (AutomationSummaryLine). */
export interface AutomationSummaryLine {
  key: string
  params?: Record<string, string | number>
}

/** The i18n key for one catalog action id (AutomationViewModel.actionTypeLabelRes); unknown ids fall to the shared "unknown" key. */
export function automationActionLabelKey(typeId: string): string {
  return (AUTOMATION_ACTION_TYPES as readonly string[]).includes(typeId)
    ? `automation.action.${typeId}`
    : 'automation.action.unknown'
}

/** The i18n key for one param row label (AutomationViewModel.paramLabelRes). */
export function automationParamLabelKey(typeId: string, paramName: string): string {
  switch (paramName) {
    case 'iso':
      return 'automation.param.iso'
    case 'exposure_time_ms':
      return 'automation.param.exposureTime'
    case 'index':
      return typeId === 'camera_lens' ? 'automation.param.lensIndex' : 'automation.param.evIndex'
    case 'ratio':
      return 'automation.param.zoomRatio'
    case 'fps':
      return 'automation.param.fps'
    case 'strength':
      return 'automation.param.strength'
    case 'duration_seconds':
      return 'automation.param.duration'
    case 'motion_enabled':
      return 'automation.param.motion'
    case 'sound_enabled':
      return 'automation.param.sound'
    case 'enabled':
      switch (typeId) {
        case 'torch':
          return 'automation.param.torch'
        case 'stream':
          return 'automation.param.stream'
        case 'recording':
          return 'automation.param.recording'
        case 'siren':
          return 'automation.param.siren'
        default:
          return 'automation.param.enabled'
      }
    default:
      return 'automation.param.enabled'
  }
}

/**
 * The rule-card summary lines for one action, built purely from the catalog
 * id and its params (AutomationViewModel.actionSummaryLines). One line per
 * meaningful setting: an exposure action with both params renders two lines;
 * a disabled torch folds its strength away; "0" is the auto/no-limit
 * sentinel where the catalog documents one.
 */
export function automationSummaryLines(action: Pick<AutomationActionDto, 'type' | 'params'>): AutomationSummaryLine[] {
  const lines: AutomationSummaryLine[] = []
  const params = action.params ?? {}
  switch (action.type) {
    case 'camera_exposure': {
      // "0" is the auto sentinel for both params (catalog contract).
      const iso = params['iso']
      if (iso != null) {
        lines.push(iso === '0' ? { key: 'automation.summary.isoAuto' } : { key: 'automation.summary.iso', params: { value: iso } })
      }
      const exposure = params['exposure_time_ms']
      if (exposure != null) {
        lines.push(
          exposure === '0'
            ? { key: 'automation.summary.exposureAuto' }
            : { key: 'automation.summary.exposure', params: { value: exposure } },
        )
      }
      break
    }
    case 'camera_ev': {
      const index = params['index']
      if (index != null) lines.push({ key: 'automation.summary.ev', params: { value: index } })
      break
    }
    case 'camera_zoom': {
      const ratio = params['ratio']
      if (ratio != null) lines.push({ key: 'automation.summary.zoom', params: { value: ratio } })
      break
    }
    case 'camera_fps': {
      const fps = params['fps']
      if (fps != null) lines.push({ key: 'automation.summary.fps', params: { value: fps } })
      break
    }
    case 'camera_lens': {
      const index = params['index']
      if (index != null) lines.push({ key: 'automation.summary.lens', params: { value: index } })
      break
    }
    case 'torch': {
      if (params['enabled'] !== 'false') {
        lines.push({ key: 'automation.summary.torchOn' })
        const strength = params['strength']
        if (strength != null) lines.push({ key: 'automation.summary.torchStrength', params: { value: strength } })
      } else {
        lines.push({ key: 'automation.summary.torchOff' })
      }
      break
    }
    case 'stream':
      lines.push(params['enabled'] !== 'false' ? { key: 'automation.summary.streamStart' } : { key: 'automation.summary.streamStop' })
      break
    case 'capture_photo':
      lines.push({ key: 'automation.summary.photo' })
      break
    case 'recording': {
      if (params['enabled'] === 'false') {
        lines.push({ key: 'automation.summary.recordingStop' })
      } else {
        // "0" (or absent) = unbounded — renders as a plain start.
        const duration = parseInt(params['duration_seconds'] ?? '', 10) || 0
        if (duration > 0) {
          lines.push({ key: 'automation.summary.recordingDuration', params: { value: duration } })
        } else {
          lines.push({ key: 'automation.summary.recordingStart' })
        }
      }
      break
    }
    case 'siren': {
      if (params['enabled'] === 'false') {
        lines.push({ key: 'automation.summary.sirenStop' })
      } else {
        const duration = parseInt(params['duration_seconds'] ?? '', 10) || 0
        if (duration > 0) {
          lines.push({ key: 'automation.summary.sirenDuration', params: { value: duration } })
        } else {
          lines.push({ key: 'automation.summary.siren' })
        }
      }
      break
    }
    case 'detection': {
      const motion = params['motion_enabled']
      if (motion != null) {
        lines.push(motion === 'true' ? { key: 'automation.summary.motionOn' } : { key: 'automation.summary.motionOff' })
      }
      const sound = params['sound_enabled']
      if (sound != null) {
        lines.push(sound === 'true' ? { key: 'automation.summary.soundOn' } : { key: 'automation.summary.soundOff' })
      }
      break
    }
    default:
      lines.push({ key: 'automation.action.unknown' })
  }
  return lines
}

// ── time / day display helpers ──

/** "HH:mm" over a 24-hour clock from a minute-of-day, clamped into 0..1439 (AutomationViewModel.formatMinuteOfDay). */
export function formatMinuteOfDay(minuteOfDay: number): string {
  const minute = Math.min(Math.max(Math.round(minuteOfDay), 0), 1439)
  return `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
}

/** Minute-of-day 0..1439 from an "HH:mm" value (`<input type="time">`), or null when malformed/out of range (the parse twin of formatMinuteOfDay). */
export function parseMinuteOfDay(value: string): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim())
  if (!match) return null
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (hours > 23 || minutes > 59) return null
  return hours * 60 + minutes
}

/** True when the mask arms all seven days — the compact "Every day" shape (AutomationViewModel.isEveryDay). */
export function isEveryDayMask(daysMask: number): boolean {
  return daysMask === AUTOMATION_ALL_DAYS_MASK
}

// ── canonical param values ──

/** INT slider value → canonical wire string (whole number, no decimals) — AutomationViewModel.canonicalIntValue. */
export function canonicalIntValue(value: number): string {
  return String(Math.round(value))
}

/**
 * FLOAT slider value → canonical wire string: rounded to one decimal,
 * integral values losing the trailing ".0" (the catalog's canonical number
 * rendering, so a saved "1" matches the documented default "1") —
 * AutomationViewModel.canonicalFloatValue. JS numbers already render without
 * a trailing ".0", so the rounding is the only transformation.
 */
export function canonicalFloatValue(value: number): string {
  return String(Math.round(value * 10) / 10)
}

/** Canonical wire string for a slider value per the spec's param kind (AutomationScreen.canonicalFor). */
export function canonicalParamValue(spec: Pick<AutomationCatalogParamDto, 'kind'>, value: number): string {
  return spec.kind === 'FLOAT' ? canonicalFloatValue(value) : canonicalIntValue(value)
}

/** The effective slider span for one param: the catalog bounds with the web's safe fallbacks when the catalog leaves a side unbounded. */
export function paramSliderBounds(spec: Pick<AutomationCatalogParamDto, 'min' | 'max'>): { min: number; max: number } {
  return { min: spec.min ?? 0, max: spec.max ?? 100 }
}

// ── rule/action construction ──

/**
 * The params a freshly picked action starts with: every required param at its
 * catalog default, optional ones absent (their absence IS the "leave
 * unchanged" semantics the executor honors) — AutomationViewModel.defaultActionParams.
 */
export function defaultActionParams(catalogAction: Pick<AutomationCatalogActionDto, 'params'> | undefined): Record<string, string> {
  const params: Record<string, string> = {}
  for (const spec of catalogAction?.params ?? []) {
    if (!spec.optional) params[spec.name] = spec.defaultValue
  }
  return params
}

/** A fresh rule: 08:00, every day, no actions yet, blank id (the device mints the fire-journal UUID on save) — AutomationScreen.RuleDraft.new(). */
export function newAutomationRule(): AutomationRuleDto {
  return {
    id: '',
    name: '',
    enabled: true,
    minuteOfDay: 8 * 60,
    daysMask: AUTOMATION_ALL_DAYS_MASK,
    actions: [],
  }
}
