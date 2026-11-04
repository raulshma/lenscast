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
  AutomationCatalogConditionDto,
  AutomationCatalogParamDto,
  AutomationCatalogTriggerDto,
  AutomationConditionDto,
  AutomationRuleDto,
} from '../types'
import { isDayArmed } from '../armDays'

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

/** Every trigger catalog id the label tables cover (AutomationTriggerCatalog). */
export const AUTOMATION_TRIGGER_TYPES = [
  'time_of_day',
  'motion_detected',
  'sound_detected',
  'tamper_detected',
  'charging_changed',
  'battery_level',
  'streaming_changed',
  'viewers_changed',
  'app_started',
] as const

/** Every condition catalog id the label tables cover (AutomationConditionCatalog). */
export const AUTOMATION_CONDITION_TYPES = [
  'time_window',
  'charging',
  'battery_level',
  'power_save',
  'wifi_connected',
  'streaming',
  'viewers',
  'torch',
  'recording',
  'thermal',
  'motion_detection',
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

/**
 * Render summary lines through a t() and comma-join them — the one
 * summary-text shape behind the card's WHEN/ONLY WHEN/THEN rows and the
 * editor's live preview (the two surfaces must render identically). t is a
 * parameter, keeping this module free of i18n imports like the rest.
 */
export function joinSummaryLines(
  lines: AutomationSummaryLine[],
  t: (key: string, params?: Record<string, string | number>) => string,
): string {
  return lines.map((line) => t(line.key, line.params)).join(', ')
}

/** The i18n key for one catalog action id (AutomationViewModel.actionTypeLabelRes); unknown ids fall to the shared "unknown" key. */
export function automationActionLabelKey(typeId: string): string {
  return (AUTOMATION_ACTION_TYPES as readonly string[]).includes(typeId)
    ? `automation.action.${typeId}`
    : 'automation.action.unknown'
}

/** The i18n key for one trigger id (AutomationViewModel.triggerTypeLabelRes). */
export function automationTriggerLabelKey(triggerTypeId: string): string {
  return (AUTOMATION_TRIGGER_TYPES as readonly string[]).includes(triggerTypeId)
    ? `automation.trigger.${triggerTypeId}`
    : 'automation.action.unknown'
}

/** The i18n key for one condition id (AutomationViewModel.conditionTypeLabelRes). */
export function automationConditionLabelKey(conditionTypeId: string): string {
  return (AUTOMATION_CONDITION_TYPES as readonly string[]).includes(conditionTypeId)
    ? `automation.condition.${conditionTypeId}`
    : 'automation.action.unknown'
}

/**
 * The "typeId/paramName" label overrides — names that read differently per
 * catalog type ("connected" is Wi-Fi for one condition and viewer presence
 * for one trigger). Checked before the bare-name fallback, the same ladder
 * as AutomationViewModel.PARAM_LABEL_OVERRIDES.
 */
const PARAM_LABEL_OVERRIDES: Record<string, string> = {
  'camera_lens/index': 'automation.param.lensIndex',
  'torch/enabled': 'automation.param.torch',
  'stream/enabled': 'automation.param.stream',
  'recording/enabled': 'automation.param.recording',
  'siren/enabled': 'automation.param.siren',
  // Triggers
  'battery_level/level': 'automation.param.batteryThreshold',
  'battery_level/when_below': 'automation.param.whenBelow',
  'charging_changed/charging': 'automation.param.chargingPlugged',
  'streaming_changed/streaming': 'automation.param.streamingActive',
  'viewers_changed/connected': 'automation.param.viewersConnected',
  // Conditions
  'time_window/start_minute': 'automation.param.windowStart',
  'time_window/end_minute': 'automation.param.windowEnd',
  'time_window/days_mask': 'automation.param.windowDays',
  'charging/charging': 'automation.param.chargingPlugged',
  'battery_level/min_level': 'automation.param.batteryMin',
  'battery_level/max_level': 'automation.param.batteryMax',
  'power_save/enabled': 'automation.param.powerSaveOn',
  'wifi_connected/connected': 'automation.param.wifiConnected',
  'streaming/active': 'automation.param.streamingActive',
  'viewers/min_count': 'automation.param.viewersMin',
  'viewers/max_count': 'automation.param.viewersMax',
  'torch/on': 'automation.param.torchOn',
  'recording/active': 'automation.param.recordingActive',
  'thermal/normal': 'automation.param.thermalNormal',
  'motion_detection/enabled': 'automation.param.motion',
}

/** The i18n key for one param row label (AutomationViewModel.paramLabelRes). */
export function automationParamLabelKey(typeId: string, paramName: string): string {
  return PARAM_LABEL_OVERRIDES[`${typeId}/${paramName}`] ?? automationParamLabelFallback(paramName)
}

/** The bare-name fallback behind the override ladder. */
function automationParamLabelFallback(paramName: string): string {
  switch (paramName) {
    case 'iso':
      return 'automation.param.iso'
    case 'exposure_time_ms':
      return 'automation.param.exposureTime'
    case 'index':
      return 'automation.param.evIndex'
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

/** A time-window condition's persisted day mask (the sanitize default when absent) — the app's conditionWindowDaysMask. */
export function conditionDaysMask(condition: { params: Record<string, string> }): number {
  const parsed = Number.parseInt(condition.params['days_mask'] ?? '', 10)
  return Number.isFinite(parsed) ? parsed : AUTOMATION_ALL_DAYS_MASK
}

/** "Mon, Wed" over a day mask with the localized day labels (t supplied — no i18n import here). */
export function dayListLabel(daysMask: number, t: (key: string) => string): string {
  return [0, 1, 2, 3, 4, 5, 6]
    .filter((day) => isDayArmed(daysMask, day))
    .map((day) => t(automationDayLabelKey(day)))
    .join(', ')
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
  return defaultSpecParams(catalogAction?.params)
}

/** [defaultActionParams] over the trigger catalog. */
export function defaultTriggerParams(catalogTrigger: Pick<AutomationCatalogTriggerDto, 'params'> | undefined): Record<string, string> {
  return defaultSpecParams(catalogTrigger?.params)
}

/** [defaultActionParams] over the condition catalog. */
export function defaultConditionParams(catalogCondition: Pick<AutomationCatalogConditionDto, 'params'> | undefined): Record<string, string> {
  return defaultSpecParams(catalogCondition?.params)
}

/** The shared required-params-at-defaults fold behind all three catalogs. */
function defaultSpecParams(params: AutomationCatalogParamDto[] | undefined): Record<string, string> {
  const defaults: Record<string, string> = {}
  for (const spec of params ?? []) {
    if (!spec.optional) defaults[spec.name] = spec.defaultValue
  }
  return defaults
}

/** One canonical param write on a params map: null/empty removes the key (AutomationViewModel.withSpecParam). */
export function withSpecParam(
  params: Record<string, string>,
  name: string,
  value: string | null | undefined,
): Record<string, string> {
  const next = { ...params }
  if (value == null || value === '') delete next[name]
  else next[name] = value
  return next
}

/** A fresh rule: 08:00 every day, time trigger, no conditions/actions yet, blank id (the device mints the fire-journal UUID on save) — AutomationScreen.RuleDraft.new(). */
export function newAutomationRule(): AutomationRuleDto {
  return {
    id: '',
    name: '',
    enabled: true,
    triggerType: 'time_of_day',
    minuteOfDay: 8 * 60,
    daysMask: AUTOMATION_ALL_DAYS_MASK,
    triggerParams: {},
    conditions: [],
    conditionMode: 'all',
    cooldownSeconds: 60,
    actions: [],
  }
}

// ── trigger / condition summaries ──

/** The effective BOOL reading of one trigger/condition param, defaulting like the engine's evaluation (AutomationParamValues.paramValue). */
function boolParam(params: Record<string, string>, name: string, fallback: boolean): boolean {
  const raw = params[name]
  if (raw != null) return raw === 'true'
  return fallback
}

function intParam(params: Record<string, string>, name: string): number | null {
  const raw = params[name]
  if (raw == null) return null
  const parsed = parseInt(raw, 10)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * The rule-card summary line for a rule's TRIGGER: [] for time-of-day rules
 * (their card shows the classic HH:mm · days label) and one "when …" line for
 * every event trigger (AutomationViewModel.triggerSummaryLines).
 */
export function automationTriggerSummaryLines(rule: Pick<AutomationRuleDto, 'triggerType' | 'triggerParams'>): AutomationSummaryLine[] {
  const params = rule.triggerParams ?? {}
  switch (rule.triggerType) {
    case 'time_of_day':
      return []
    case 'motion_detected':
      return [{ key: 'automation.summary.triggerMotion' }]
    case 'sound_detected':
      return [{ key: 'automation.summary.triggerSound' }]
    case 'tamper_detected':
      return [{ key: 'automation.summary.triggerTamper' }]
    case 'charging_changed':
      return [
        boolParam(params, 'charging', true)
          ? { key: 'automation.summary.triggerChargingOn' }
          : { key: 'automation.summary.triggerChargingOff' },
      ]
    case 'battery_level': {
      const level = intParam(params, 'level') ?? 20
      return [
        boolParam(params, 'when_below', true)
          ? { key: 'automation.summary.triggerBatteryBelow', params: { value: level } }
          : { key: 'automation.summary.triggerBatteryAbove', params: { value: level } },
      ]
    }
    case 'streaming_changed':
      return [
        boolParam(params, 'streaming', true)
          ? { key: 'automation.summary.triggerStreamStart' }
          : { key: 'automation.summary.triggerStreamStop' },
      ]
    case 'viewers_changed':
      return [
        boolParam(params, 'connected', true)
          ? { key: 'automation.summary.triggerViewerJoin' }
          : { key: 'automation.summary.triggerViewerLeave' },
      ]
    case 'app_started':
      return [{ key: 'automation.summary.triggerAppStarted' }]
    default:
      return []
  }
}

/**
 * The rule-card summary lines for one precondition. The time window's
 * day-mask suffix renders in the caller (it needs the localized day chips'
 * labels), exactly like the app screen composes that one line itself —
 * AutomationViewModel.conditionSummaryLines.
 */
export function automationConditionSummaryLines(condition: Pick<AutomationConditionDto, 'type' | 'params'>): AutomationSummaryLine[] {
  const params = condition.params ?? {}
  switch (condition.type) {
    case 'time_window': {
      const start = formatMinuteOfDay(intParam(params, 'start_minute') ?? 0)
      const end = formatMinuteOfDay(intParam(params, 'end_minute') ?? 1439)
      return [{ key: 'automation.summary.conditionTimeWindow', params: { start, end } }]
    }
    case 'charging':
      return [
        boolParam(params, 'charging', true)
          ? { key: 'automation.summary.conditionChargingOn' }
          : { key: 'automation.summary.conditionChargingOff' },
      ]
    case 'battery_level': {
      const min = intParam(params, 'min_level')
      const max = intParam(params, 'max_level')
      if (min != null && max != null) {
        return [{ key: 'automation.summary.conditionBatteryBetween', params: { min, max } }]
      }
      if (min != null) return [{ key: 'automation.summary.conditionBatteryMin', params: { value: min } }]
      if (max != null) return [{ key: 'automation.summary.conditionBatteryMax', params: { value: max } }]
      return [{ key: 'automation.summary.conditionBatteryAny' }]
    }
    case 'power_save':
      return [
        boolParam(params, 'enabled', false)
          ? { key: 'automation.summary.conditionPowerSaveOn' }
          : { key: 'automation.summary.conditionPowerSaveOff' },
      ]
    case 'wifi_connected':
      return [
        boolParam(params, 'connected', true)
          ? { key: 'automation.summary.conditionWifiOn' }
          : { key: 'automation.summary.conditionWifiOff' },
      ]
    case 'streaming':
      return [
        boolParam(params, 'active', true)
          ? { key: 'automation.summary.conditionStreamingOn' }
          : { key: 'automation.summary.conditionStreamingOff' },
      ]
    case 'viewers': {
      const min = intParam(params, 'min_count')
      const max = intParam(params, 'max_count')
      if (min != null && max != null) {
        return [{ key: 'automation.summary.conditionViewersBetween', params: { min, max } }]
      }
      if (min != null) return [{ key: 'automation.summary.conditionViewersMin', params: { value: min } }]
      if (max != null) return [{ key: 'automation.summary.conditionViewersMax', params: { value: max } }]
      return [{ key: 'automation.summary.conditionViewersAny' }]
    }
    case 'torch':
      return [
        boolParam(params, 'on', true)
          ? { key: 'automation.summary.conditionTorchOn' }
          : { key: 'automation.summary.conditionTorchOff' },
      ]
    case 'recording':
      return [
        boolParam(params, 'active', true)
          ? { key: 'automation.summary.conditionRecordingOn' }
          : { key: 'automation.summary.conditionRecordingOff' },
      ]
    case 'thermal':
      return [
        boolParam(params, 'normal', true)
          ? { key: 'automation.summary.conditionThermalNormal' }
          : { key: 'automation.summary.conditionThermalThrottling' },
      ]
    case 'motion_detection':
      return [
        boolParam(params, 'enabled', true)
          ? { key: 'automation.summary.conditionMotionOn' }
          : { key: 'automation.summary.conditionMotionOff' },
      ]
    default:
      return []
  }
}

// ── picker groups (UI-side constants; the engine catalogs are untouched) ──
// The same grouping the app's AutomationVisuals renders: ids land in labeled
// groups so the pickers read as a menu, not a flat dump of catalog ids.

/** One labeled group of catalog ids in a picker (AutomationVisuals.PickerGroup). */
export interface AutomationPickerGroup {
  /** The i18n key of the group heading. */
  titleKey: string
  /** The catalog ids in display order. */
  ids: readonly string[]
}

export const AUTOMATION_TRIGGER_GROUPS: readonly AutomationPickerGroup[] = [
  { titleKey: 'automation.group.schedule', ids: ['time_of_day'] },
  { titleKey: 'automation.group.deviceEvents', ids: ['motion_detected', 'sound_detected', 'tamper_detected'] },
  { titleKey: 'automation.group.power', ids: ['charging_changed', 'battery_level'] },
  { titleKey: 'automation.group.streamApp', ids: ['streaming_changed', 'viewers_changed', 'app_started'] },
]

export const AUTOMATION_ACTION_GROUPS: readonly AutomationPickerGroup[] = [
  { titleKey: 'automation.group.camera', ids: ['camera_exposure', 'camera_ev', 'camera_zoom', 'camera_fps', 'camera_lens'] },
  { titleKey: 'automation.group.lightSound', ids: ['torch', 'siren'] },
  { titleKey: 'automation.group.streamCapture', ids: ['stream', 'capture_photo', 'recording'] },
  { titleKey: 'automation.group.detection', ids: ['detection'] },
]

export const AUTOMATION_CONDITION_GROUPS: readonly AutomationPickerGroup[] = [
  { titleKey: 'automation.group.time', ids: ['time_window'] },
  { titleKey: 'automation.group.power', ids: ['charging', 'battery_level', 'power_save'] },
  { titleKey: 'automation.group.connectivity', ids: ['wifi_connected'] },
  { titleKey: 'automation.group.deviceState', ids: ['streaming', 'viewers', 'torch', 'recording', 'thermal', 'motion_detection'] },
]

/**
 * The grouped picker model for one catalog: the static groups filtered to the
 * ids the device actually publishes, plus any unknown id parked under a
 * trailing "Other" group so a stale-firmware catalog still renders fully.
 */
export function groupedPickerOptions(
  groups: readonly AutomationPickerGroup[],
  availableIds: readonly string[],
): { titleKey: string; ids: string[] }[] {
  const known = new Set(groups.flatMap((group) => group.ids))
  const result = groups
    .map((group) => ({ titleKey: group.titleKey, ids: group.ids.filter((id) => availableIds.includes(id)) }))
    .filter((group) => group.ids.length > 0)
  const leftover = availableIds.filter((id) => !known.has(id))
  if (leftover.length > 0) result.push({ titleKey: 'automation.group.other', ids: leftover })
  return result
}

/** The i18n key of one picker option's one-line description. */
export function automationDescKey(kind: 'trigger' | 'action' | 'condition', typeId: string): string {
  return `automation.${kind}Desc.${typeId}`
}

/** The i18n key of one ISO day's label (0 = Monday … 6 = Sunday) — the localized replacement for armDays' hardcoded English labels. */
export function automationDayLabelKey(isoDayIndex: number): string {
  return `automation.day.${Math.min(Math.max(Math.round(isoDayIndex), 0), 6)}`
}

// ── cooldown humanizing ──

/** The cooldown preset chips (seconds) — exactly the app's CooldownEditor presets. */
export const AUTOMATION_COOLDOWN_PRESETS: readonly number[] = [30, 60, 300, 900, 1_800, 3_600]

/**
 * Humanize a duration for the cooldown readout and preset chips: seconds
 * under a minute, minutes under an hour (rounded half-up, minimum 1), hours
 * above — AutomationViewModel.humanizeDuration, so the two UIs read the same
 * "5 min" the same way. Returns a summary line (key + params) for t().
 */
export function humanizeDuration(seconds: number): AutomationSummaryLine {
  const clamped = Math.max(Math.round(seconds), 0)
  if (clamped < 60) return { key: 'automation.duration.seconds', params: { value: clamped } }
  if (clamped < 3_600) {
    return { key: 'automation.duration.minutes', params: { value: Math.max(Math.floor((clamped + 30) / 60), 1) } }
  }
  return { key: 'automation.duration.hours', params: { value: Math.max(Math.floor((clamped + 1_800) / 3_600), 1) } }
}

// ── fire status ──

/** The "Last ran …" bucket for a rule's lastFiredAt instant (AutomationLastFiredLabel). */
export type AutomationLastFiredLabel =
  | { kind: 'today'; minuteOfDay: number }
  | { kind: 'yesterday'; minuteOfDay: number }
  | { kind: 'earlier'; epochMillis: number; minuteOfDay: number }

/**
 * The card's last-fired bucket for an epoch-millis instant vs [nowMs], in the
 * browser's local zone (the JVM twin decodes through ZoneId.systemDefault):
 * today/yesterday carry the minute-of-day for the HH:mm templates, anything
 * older carries the epoch instant for a locale-formatted date. A non-positive
 * instant (never fired) is null — the card shows nothing.
 */
export function lastFiredLabel(lastFiredAtMs: number, nowMs: number): AutomationLastFiredLabel | null {
  if (lastFiredAtMs <= 0) return null
  const fired = new Date(lastFiredAtMs)
  const startOfDay = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime()
  const dayGap = Math.round((startOfDay(new Date(nowMs)) - startOfDay(fired)) / 86_400_000)
  const minuteOfDay = fired.getHours() * 60 + fired.getMinutes()
  if (dayGap <= 0) return { kind: 'today', minuteOfDay }
  if (dayGap === 1) return { kind: 'yesterday', minuteOfDay }
  return { kind: 'earlier', epochMillis: lastFiredAtMs, minuteOfDay }
}

// ── rule-list transforms ──

/** A fresh fire-journal UUID (crypto.randomUUID, with a fallback for non-secure contexts). */
export function newRuleId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  } catch {
    // Non-secure context: fall through to the hand-rolled id.
  }
  return `rule-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * A duplicate of the rule with [sourceId], inserted right after it: a fresh
 * id (the journal key must never be shared — a duplicate would inherit the
 * original's dedupe history), the caller-resolved [copyName], a fresh enabled
 * state and a cleared fire status. At [cap] (or for an unknown id) this is a
 * no-op, like the app's withRuleDuplicated.
 */
export function withRuleDuplicated(
  rules: AutomationRuleDto[],
  sourceId: string,
  copyName: string,
  cap: number,
): AutomationRuleDto[] {
  const index = rules.findIndex((rule) => rule.id === sourceId)
  if (index < 0 || rules.length >= cap) return rules
  const copy: AutomationRuleDto = { ...rules[index], id: newRuleId(), name: copyName, enabled: true, lastFiredAt: null }
  return [...rules.slice(0, index + 1), copy, ...rules.slice(index + 1)]
}

/** A rule is saveable only with at least one action (the app editor's gate). */
export function isRuleValid(rule: Pick<AutomationRuleDto, 'actions'>): boolean {
  return rule.actions.length > 0
}

/** Splice one entry one slot up (delta -1) or down (+1); out-of-range is a no-op (the app's ReorderButtons list splice). */
export function moveSpec<T>(list: T[], index: number, delta: number): T[] {
  const target = index + delta
  if (target < 0 || target >= list.length) return list
  const next = [...list]
  const [entry] = next.splice(index, 1)
  next.splice(target, 0, entry)
  return next
}
