import { createEffect, createSignal, For, onCleanup, Show } from 'solid-js'
import type {
  AutomationCatalogActionDto,
  AutomationCatalogConditionDto,
  AutomationCatalogParamDto,
  AutomationCatalogTriggerDto,
  AutomationConditionDto,
  AutomationRuleDto,
} from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'
import {
  automationActionLabelKey,
  automationConditionLabelKey,
  automationParamLabelKey,
  automationTriggerLabelKey,
  canonicalParamValue,
  defaultActionParams,
  defaultConditionParams,
  defaultTriggerParams,
  formatMinuteOfDay,
  isAutoSentinelParam,
  paramSliderBounds,
  parseMinuteOfDay,
  withSpecParam,
} from '../lib/automation'
import { ARM_DAY_LABELS, isDayArmed, toggleArmDayMask } from '../armDays'

interface Props {
  /** The draft being edited; the editor works on a local copy and only onSave hands it back. */
  draft: AutomationRuleDto
  /** True for a rule the store has never seen (title + save semantics). */
  isNew: boolean
  /** The GET /api/automation action catalog — the picker and every param editor render from it, never from a hardcoded per-type form. */
  catalog: AutomationCatalogActionDto[]
  /** The trigger catalog (WHEN picker + event param editors). */
  triggers: AutomationCatalogTriggerDto[]
  /** The condition catalog (ONLY WHEN picker + param editors). */
  conditions: AutomationCatalogConditionDto[]
  saving: boolean
  onClose: () => void
  onSave: (rule: AutomationRuleDto) => void
}

/** One editor-local action/condition. `key` is a stable per-dialog instance id (DOM id uniqueness); stripped on save. */
interface DraftSpec {
  type: string
  params: Record<string, string>
  key: number
}

/** The re-fire cooldown slider's display ceiling (the persisted bound allows a day; no useful cooldown needs a slider that wide). */
const COOLDOWN_SLIDER_MAX = 3_600

/**
 * The rule editor dialog — the web twin of the app's AutomationRuleEditor:
 * Escape or the backdrop closes, nothing persists until Save, and the whole
 * WHEN / ONLY WHEN / THEN shape is catalog-driven (trigger picker, condition
 * picker, action picker all fed by the GET catalogs; per-param editors shaped
 * by each AutomationCatalogParamDto: BOOL switches, optional params behind a
 * configure switch whose off state removes the param, the camera_exposure
 * auto-sentinel params with an Auto toggle mapping onto "0", and INT/FLOAT
 * sliders over the catalog bounds).
 */
export default function AutomationRuleEditor(props: Props) {
  let nextKey = 0
  const [name, setName] = createSignal(props.draft.name)
  const [triggerType, setTriggerType] = createSignal(props.draft.triggerType)
  const [minuteOfDay, setMinuteOfDay] = createSignal(props.draft.minuteOfDay)
  const [daysMask, setDaysMask] = createSignal(props.draft.daysMask)
  const [enabled, setEnabled] = createSignal(props.draft.enabled)
  // The trigger's params ride a stable in-place record + the shared mutation
  // counter (see actionsVersion): swapping the trigger reseeds them from the
  // new catalog entry's required defaults.
  let triggerParams: Record<string, string> = { ...props.draft.triggerParams }
  // Action/condition drafts keep a stable object identity across param edits:
  // the editors mutate `params` in place and bump the version, so the value
  // displays stay reactive WITHOUT the param-editor DOM (an open range
  // slider!) being torn down mid-drag.
  const [actions, setActions] = createSignal<DraftSpec[]>(
    props.draft.actions.map((action) => ({ type: action.type, params: { ...action.params }, key: nextKey++ })),
  )
  const [conditions, setConditions] = createSignal<DraftSpec[]>(
    props.draft.conditions.map((condition) => ({ type: condition.type, params: { ...condition.params }, key: nextKey++ })),
  )
  const [specsVersion, setSpecsVersion] = createSignal(0)
  const [conditionMode, setConditionMode] = createSignal(props.draft.conditionMode)
  const [cooldownSeconds, setCooldownSeconds] = createSignal(props.draft.cooldownSeconds)
  const [addType, setAddType] = createSignal('')
  const [addConditionType, setAddConditionType] = createSignal('')
  // The raw <input type="time"> text, so partial keystrokes survive (the
  // minuteOfDay signal only moves on a valid full "HH:mm").
  const [timeText, setTimeText] = createSignal(formatMinuteOfDay(props.draft.minuteOfDay))
  // Last manual value per auto-sentinel param name, so toggling Auto off
  // restores it (the app's AutoManualParamEditor `lastManual`). Lives at the
  // editor root because spec rows are keyed by stable identity, not per
  // param change.
  const lastManualByParam = new Map<string, number>()

  const catalogFor = (typeId: string): AutomationCatalogActionDto | undefined =>
    props.catalog.find((entry) => entry.id === typeId)
  const triggerFor = (typeId: string): AutomationCatalogTriggerDto | undefined =>
    props.triggers.find((entry) => entry.id === typeId)
  const conditionFor = (typeId: string): AutomationCatalogConditionDto | undefined =>
    props.conditions.find((entry) => entry.id === typeId)

  const isTimeTrigger = () => triggerType() === 'time_of_day'
  const atActionCap = () => actions().length >= API_DEFAULTS.automationMaxActionsPerRule
  const atConditionCap = () => conditions().length >= API_DEFAULTS.automationMaxConditionsPerRule

  function setTriggerParam(name: string, value: string | null) {
    triggerParams = withSpecParam(triggerParams, name, value)
    setSpecsVersion((v) => v + 1)
  }

  function setSpecParam(spec: DraftSpec, name: string, value: string | null) {
    if (value == null || value === '') delete spec.params[name]
    else spec.params[name] = value
    setSpecsVersion((v) => v + 1)
  }

  function changeTrigger(typeId: string) {
    setTriggerType(typeId)
    triggerParams = defaultTriggerParams(triggerFor(typeId))
    setSpecsVersion((v) => v + 1)
  }

  function removeAction(index: number) {
    setActions((prev) => prev.filter((_, i) => i !== index))
  }

  function addAction() {
    const typeId = addType()
    if (!typeId || atActionCap()) return
    setActions((prev) => [...prev, { type: typeId, params: defaultActionParams(catalogFor(typeId)), key: nextKey++ }])
    setAddType('')
  }

  function removeCondition(index: number) {
    setConditions((prev) => prev.filter((_, i) => i !== index))
  }

  function addCondition() {
    const typeId = addConditionType()
    if (!typeId || atConditionCap()) return
    setConditions((prev) => [...prev, { type: typeId, params: defaultConditionParams(conditionFor(typeId)), key: nextKey++ }])
    setAddConditionType('')
  }

  function save() {
    props.onSave({
      id: props.draft.id,
      name: name(),
      enabled: enabled(),
      triggerType: triggerType(),
      minuteOfDay: minuteOfDay(),
      daysMask: daysMask(),
      triggerParams: { ...triggerParams },
      conditions: conditions().map((condition) => ({ type: condition.type, params: { ...condition.params } })),
      conditionMode: conditionMode(),
      cooldownSeconds: cooldownSeconds(),
      actions: actions().map((action) => ({ type: action.type, params: { ...action.params } })),
    })
  }

  // Escape closes, mirroring the dashboard's other dialogs.
  createEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        props.onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    onCleanup(() => document.removeEventListener('keydown', handleKeyDown))
  })

  return (
    <div class="gallery-overlay automation-editor-overlay" onClick={props.onClose}>
      <div class="automation-editor-modal" onClick={(e) => e.stopPropagation()}>
        <div class="gallery-header">
          <button class="navbar-icon-btn" onClick={props.onClose} title={t('common.close')} aria-label={t('automation.editor.closeAria')}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
          <h2>{props.isNew ? t('automation.editor.newTitle') : t('automation.editor.editTitle')}</h2>
          <button class="card-btn card-btn-primary automation-editor-save" onClick={save} disabled={props.saving}>
            {t('common.save')}
          </button>
        </div>

        <div class="automation-editor-body">
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('automation.editor.name')}</span>
            </div>
            <input
              id="automation-rule-name"
              type="text"
              class="field-input field-select-full"
              value={name()}
              onInput={(e) => setName(e.currentTarget.value)}
              placeholder={t('automation.ruleUnnamed')}
            />
          </div>

          {/* ── WHEN (the trigger) ── */}
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('automation.editor.trigger')}</span>
            </div>
            <select
              id="automation-trigger-select"
              class="field-select field-select-full"
              value={triggerType()}
              onChange={(e) => changeTrigger(e.currentTarget.value)}
            >
              <For each={props.triggers}>
                {(entry) => (
                  <option value={entry.id}>{t(automationTriggerLabelKey(entry.id))}</option>
                )}
              </For>
              {/* Pre-fetch fallback: at least the draft's own trigger must be
                  selectable so the value survives a save before the catalogs
                  load. */}
              <Show when={triggerType() && !props.triggers.some((entry) => entry.id === triggerType())}>
                <option value={triggerType()}>{triggerType()}</option>
              </Show>
            </select>
          </div>

          <Show
            when={isTimeTrigger()}
            fallback={
              <>
                <For each={triggerFor(triggerType())?.params ?? []}>
                  {(spec) => (
                    <AutomationParamEditor
                      typeId={triggerType()}
                      spec={spec}
                      catalogAction={undefined}
                      params={triggerParams}
                      version={specsVersion}
                      idPrefix="automation-trigger-param"
                      lastManualByParam={lastManualByParam}
                      onChange={setTriggerParam}
                    />
                  )}
                </For>
                <div class="field-group">
                  <div class="field-row">
                    <span class="field-label">{t('automation.editor.cooldown')}</span>
                    <span class="field-value">{cooldownSeconds()}s</span>
                  </div>
                  <input
                    id="automation-rule-cooldown"
                    type="range"
                    class="custom-range"
                    min={API_DEFAULTS.automationCooldownMinSeconds}
                    max={COOLDOWN_SLIDER_MAX}
                    step={5}
                    value={Math.min(Math.max(cooldownSeconds(), API_DEFAULTS.automationCooldownMinSeconds), COOLDOWN_SLIDER_MAX)}
                    onInput={(e) => {
                      const parsed = Number.parseInt(e.currentTarget.value, 10)
                      if (Number.isFinite(parsed)) setCooldownSeconds(parsed)
                    }}
                  />
                  <div class="automation-param-hint">{t('automation.editor.cooldownHint')}</div>
                </div>
              </>
            }
          >
            <div class="field-group">
              <div class="field-row">
                <span class="field-label">{t('automation.editor.time')}</span>
              </div>
              <input
                id="automation-rule-time"
                type="time"
                class="field-input field-select-full"
                value={timeText()}
                onInput={(e) => {
                  const text = e.currentTarget.value
                  const parsed = parseMinuteOfDay(text)
                  if (parsed != null) {
                    setMinuteOfDay(parsed)
                    // Normalize ("7:5" style input never occurs with type=time,
                    // but a locale-parsed value is always re-padded).
                    setTimeText(formatMinuteOfDay(parsed))
                  } else {
                    setTimeText(text)
                  }
                }}
              />
            </div>

            <div class="field-group">
              <div class="field-row">
                <span class="field-label">{t('automation.editor.days')}</span>
              </div>
              {/* Same ISO day bits as the arm schedule (bit 0 = Monday … bit 6 =
                  Sunday); the toggle verdict never clears the last armed day,
                  like MotionArmingPolicy.toggleDay on the app side. */}
              <div class="arm-days-row" role="group" aria-label={t('automation.daysAria')}>
                <For each={ARM_DAY_LABELS}>
                  {(label, i) => (
                    <button
                      type="button"
                      class="arm-day-chip"
                      classList={{ active: isDayArmed(daysMask(), i()) }}
                      onClick={() => setDaysMask(toggleArmDayMask(daysMask(), i()))}
                    >
                      {label}
                    </button>
                  )}
                </For>
              </div>
            </div>
          </Show>

          <div class="field-group">
            <div class="field-row field-row-toggle">
              <span class="field-label">{t('automation.editor.enabled')}</span>
              <label class="toggle-switch" for="automation-rule-enabled">
                <input
                  id="automation-rule-enabled"
                  type="checkbox"
                  checked={enabled()}
                  onChange={() => setEnabled(!enabled())}
                />
                <span class="toggle-slider" />
              </label>
            </div>
          </div>

          {/* ── ONLY WHEN (the preconditions) ── */}
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('automation.editor.conditions')}</span>
              <span class="field-value">
                {conditions().length}/{API_DEFAULTS.automationMaxConditionsPerRule}
              </span>
            </div>
          </div>

          <Show when={conditions().length > 0}>
            <div class="field-group">
              <div class="field-row field-row-toggle">
                <span class="field-label">{t('automation.editor.matchAny')}</span>
                <label class="toggle-switch" for="automation-rule-match-any">
                  <input
                    id="automation-rule-match-any"
                    type="checkbox"
                    checked={conditionMode() === 'any'}
                    onChange={() => setConditionMode(conditionMode() === 'any' ? 'all' : 'any')}
                  />
                  <span class="toggle-slider" />
                </label>
              </div>
              <div class="automation-param-hint">{t('automation.editor.matchAnyHint')}</div>
            </div>
          </Show>

          <For each={conditions()}>
            {(condition, index) => (
              <AutomationConditionEditorCard
                typeId={condition.type}
                params={condition.params}
                version={specsVersion}
                catalogCondition={conditionFor(condition.type)}
                idPrefix={`automation-condition-${condition.key}`}
                onChange={(name, value) => setSpecParam(condition, name, value)}
                onRemove={() => removeCondition(index())}
              />
            )}
          </For>

          <div class="field-group automation-add-action">
            <select
              id="automation-add-condition-select"
              class="field-select field-select-full"
              disabled={atConditionCap()}
              value={addConditionType()}
              onChange={(e) => setAddConditionType(e.currentTarget.value)}
            >
              <option value="">{t('automation.editor.addCondition')}</option>
              <For each={props.conditions}>
                {(entry) => (
                  <option value={entry.id}>{t(automationConditionLabelKey(entry.id))}</option>
                )}
              </For>
            </select>
            <button class="card-btn" onClick={addCondition} disabled={!addConditionType() || atConditionCap()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {t('automation.editor.addCondition')}
            </button>
          </div>

          {/* ── THEN (the actions) ── */}
          <div class="field-group">
            <div class="field-row">
              <span class="field-label">{t('automation.editor.actions')}</span>
              <span class="field-value">
                {actions().length}/{API_DEFAULTS.automationMaxActionsPerRule}
              </span>
            </div>
          </div>

          <For each={actions()}>
            {(action, index) => (
              <AutomationActionEditorCard
                typeId={action.type}
                params={action.params}
                version={specsVersion}
                catalogAction={catalogFor(action.type)}
                idPrefix={`automation-action-${action.key}`}
                lastManualByParam={lastManualByParam}
                onChange={(name, value) => setSpecParam(action, name, value)}
                onRemove={() => removeAction(index())}
              />
            )}
          </For>

          <div class="field-group automation-add-action">
            <select
              id="automation-add-action-select"
              class="field-select field-select-full"
              disabled={atActionCap()}
              value={addType()}
              onChange={(e) => setAddType(e.currentTarget.value)}
            >
              <option value="">{t('automation.editor.addAction')}</option>
              <For each={props.catalog}>
                {(entry) => (
                  <option value={entry.id}>{t(automationActionLabelKey(entry.id))}</option>
                )}
              </For>
            </select>
            <button class="card-btn" onClick={addAction} disabled={!addType() || atActionCap()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {t('automation.editor.addAction')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

/** One action card inside the editor: pretty label, stream-interrupt warning, per-param editors. */
function AutomationActionEditorCard(props: {
  typeId: string
  /** The stable draft params record — mutated in place by onChange. */
  params: Record<string, string>
  /** The editor's mutation counter — read before every params lookup so the value displays stay reactive. */
  version: () => number
  catalogAction: AutomationCatalogActionDto | undefined
  /** Stable per-instance id prefix (param names repeat across action instances). */
  idPrefix: string
  /** Shared last-manual-value store for the auto-sentinel sliders. */
  lastManualByParam: Map<string, number>
  onChange: (name: string, value: string | null) => void
  onRemove: () => void
}) {
  return (
    <div class="automation-action-row">
      <div class="automation-action-head">
        <span class="automation-action-label">{t(automationActionLabelKey(props.typeId))}</span>
        <button class="navbar-icon-btn" onClick={props.onRemove} title={t('common.delete')} aria-label={t('automation.editor.removeActionAria')}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
      {/* Unknown-to-this-build action ids still render (label + remove) but
          have no param schema to edit. */}
      <Show when={props.catalogAction?.interruptsStream}>
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('automation.editor.interruptsStream')}</span>
        </div>
      </Show>
      <For each={props.catalogAction?.params ?? []}>
        {(spec) => (
          <AutomationParamEditor
            typeId={props.typeId}
            spec={spec}
            catalogAction={props.catalogAction}
            params={props.params}
            version={props.version}
            idPrefix={props.idPrefix}
            lastManualByParam={props.lastManualByParam}
            onChange={props.onChange}
          />
        )}
      </For>
    </div>
  )
}

/** One condition card inside the editor: pretty label, remove affordance, per-param editors (the time window's day mask renders as chips). */
function AutomationConditionEditorCard(props: {
  typeId: string
  /** The stable draft params record — mutated in place by onChange. */
  params: Record<string, string>
  /** The editor's mutation counter — read before every params lookup. */
  version: () => number
  catalogCondition: AutomationCatalogConditionDto | undefined
  /** Stable per-instance id prefix (param names repeat across condition instances). */
  idPrefix: string
  onChange: (name: string, value: string | null) => void
  onRemove: () => void
}) {
  return (
    <div class="automation-action-row">
      <div class="automation-action-head">
        <span class="automation-action-label">{t(automationConditionLabelKey(props.typeId))}</span>
        <button class="navbar-icon-btn" onClick={props.onRemove} title={t('common.delete')} aria-label={t('automation.editor.removeConditionAria')}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
      <For each={props.catalogCondition?.params ?? []}>
        {(spec) => (
          <Show
            when={props.typeId === 'time_window' && spec.name === 'days_mask'}
            fallback={
              <AutomationParamEditor
                typeId={props.typeId}
                spec={spec}
                catalogAction={undefined}
                params={props.params}
                version={props.version}
                idPrefix={props.idPrefix}
                lastManualByParam={new Map()}
                onChange={props.onChange}
              />
            }
          >
            <AutomationDaysMaskEditor
              idPrefix={props.idPrefix}
              params={props.params}
              version={props.version}
              onChange={props.onChange}
            />
          </Show>
        )}
      </For>
      <Show when={props.typeId === 'time_window'}>
        <div class="automation-param-hint">{t('automation.editor.windowWrapHint')}</div>
      </Show>
    </div>
  )
}

/** The time-window day-mask editor: the same ISO day chips as the trigger's day row. */
function AutomationDaysMaskEditor(props: {
  idPrefix: string
  /** The stable draft params record — mutated in place by onChange. */
  params: Record<string, string>
  /** The editor's mutation counter — read before every params lookup. */
  version: () => number
  onChange: (name: string, value: string | null) => void
}) {
  const mask = () => {
    props.version()
    const parsed = Number.parseInt(props.params['days_mask'] ?? '', 10)
    return Number.isFinite(parsed) ? parsed : 127
  }
  return (
    <div class="field-group">
      <div class="field-row">
        <span class="field-label">{t('automation.param.windowDays')}</span>
      </div>
      <div class="arm-days-row" role="group" aria-label={t('automation.param.windowDays')}>
        <For each={ARM_DAY_LABELS}>
          {(label, i) => (
            <button
              type="button"
              class="arm-day-chip"
              classList={{ active: isDayArmed(mask(), i()) }}
              onClick={() => props.onChange('days_mask', String(toggleArmDayMask(mask(), i())))}
            >
              {label}
            </button>
          )}
        </For>
      </div>
    </div>
  )
}

/**
 * The param editor for one catalog param — the web twin of the app's
 * AutomationParamEditor: BOOL switches (optional ones behind a configure
 * gate), auto-sentinel numerics (iso / exposure_time_ms) with an Auto toggle
 * mapping onto "0", other optional numerics behind the configure gate, and
 * required numerics as a plain slider over the catalog bounds. Shared by
 * action, trigger, and condition rows: [catalogAction] is undefined for the
 * trigger/condition uses, which makes the auto-sentinel decision ride the
 * name-set fallback (no trigger/condition param carries those names).
 */
function AutomationParamEditor(props: {
  typeId: string
  spec: AutomationCatalogParamDto
  /** This action's catalog entry — the auto-sentinel decision rides its spec. */
  catalogAction: AutomationCatalogActionDto | undefined
  /** The stable draft params record — mutated in place by onChange. */
  params: Record<string, string>
  /** The editor's mutation counter — read before every params lookup. */
  version: () => number
  /** Stable per-instance id prefix (param names repeat across spec instances). */
  idPrefix: string
  /** Shared last-manual-value store for the auto-sentinel sliders. */
  lastManualByParam: Map<string, number>
  onChange: (name: string, value: string | null) => void
}) {
  const label = () => t(automationParamLabelKey(props.typeId, props.spec.name))
  const inputId = () => `${props.idPrefix}-${props.spec.name}`
  const bounds = () => paramSliderBounds(props.spec)
  const isAutoSentinel = () => isAutoSentinelParam(props.catalogAction, props.spec)
  // The reactive param value: the version bump tells the read the in-place
  // record may have changed (Solid cannot see a plain object mutation).
  const value = () => {
    props.version()
    return props.params[props.spec.name]
  }

  function numericValue(): number {
    const parsed = Number.parseFloat(value() ?? props.spec.defaultValue)
    const { min } = bounds()
    return Number.isFinite(parsed) ? parsed : min
  }

  // HTML range inputs clamp to their min/max, so no extra coercion is needed
  // before the canonical string is persisted.
  function sliderInput(rawValue: string) {
    const parsed = Number.parseFloat(rawValue)
    if (!Number.isFinite(parsed)) return
    props.lastManualByParam.set(props.spec.name, parsed)
    props.onChange(props.spec.name, canonicalParamValue(props.spec, parsed))
  }

  // ── BOOL ──
  if (props.spec.kind === 'BOOL') {
    const configured = () => value() != null
    return (
      <Show
        when={props.spec.optional}
        fallback={
          <div class="field-row field-row-toggle">
            <span class="field-label">{label()}</span>
            <label class="toggle-switch" for={inputId()}>
              <input
                id={inputId()}
                type="checkbox"
                // Absent folds to the "on" verdict, like the app's `!= "false"`.
                checked={value() !== 'false'}
                onChange={(e) => props.onChange(props.spec.name, e.currentTarget.checked ? 'true' : 'false')}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        }
      >
        <div class="field-row field-row-toggle">
          <span class="field-label">{label()}</span>
          <label class="toggle-switch" for={inputId()}>
            <input
              id={inputId()}
              type="checkbox"
              checked={configured()}
              onChange={(e) => props.onChange(props.spec.name, e.currentTarget.checked ? 'true' : null)}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={configured()}>
          <div class="field-row field-row-toggle automation-param-nested">
            <span class="field-label">{t('automation.param.enabled')}</span>
            <label class="toggle-switch" for={`${inputId()}-value`}>
              <input
                id={`${inputId()}-value`}
                type="checkbox"
                checked={value() === 'true'}
                onChange={(e) => props.onChange(props.spec.name, e.currentTarget.checked ? 'true' : 'false')}
              />
              <span class="toggle-slider" />
            </label>
          </div>
        </Show>
      </Show>
    )
  }

  // ── INT / FLOAT ──
  if (isAutoSentinel()) {
    // camera_exposure iso / exposure_time_ms: "0" is the auto sentinel, so
    // the configured editor is an Auto switch plus the manual slider floored
    // at 1 (manual 0 would mean auto again).
    const configured = () => value() != null
    const isAuto = () => value() === '0'
    const manualMin = () => Math.max(bounds().min, 1)
    return (
      <>
        <div class="field-row field-row-toggle">
          <span class="field-label">{label()}</span>
          <label class="toggle-switch" for={inputId()}>
            <input
              id={inputId()}
              type="checkbox"
              checked={configured()}
              onChange={(e) => props.onChange(props.spec.name, e.currentTarget.checked ? '0' : null)}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={configured()}>
          <div class="field-row field-row-toggle automation-param-nested">
            <span class="field-label">{t('automation.editor.auto')}</span>
            <label class="toggle-switch" for={`${inputId()}-auto`}>
              <input
                id={`${inputId()}-auto`}
                type="checkbox"
                checked={isAuto()}
                onChange={(e) => {
                  if (e.currentTarget.checked) {
                    props.onChange(props.spec.name, '0')
                  } else {
                    const manual = props.lastManualByParam.get(props.spec.name) ?? manualMin()
                    props.onChange(props.spec.name, canonicalParamValue(props.spec, manual))
                  }
                }}
              />
              <span class="toggle-slider" />
            </label>
          </div>
          <Show when={!isAuto()}>
            <div class="field-group automation-param-nested">
              <div class="field-row">
                <span class="field-label">{label()}</span>
                <span class="field-value">{value()}</span>
              </div>
              <input
                type="range"
                class="custom-range"
                min={manualMin()}
                max={bounds().max}
                step={props.spec.kind === 'INT' ? 1 : 0.1}
                value={numericValue()}
                onInput={(e) => sliderInput(e.currentTarget.value)}
              />
            </div>
          </Show>
        </Show>
      </>
    )
  }

  if (props.spec.optional) {
    // torch strength / durations / optional range bounds (battery min/max,
    // viewer min/max): the configure gate seeds the param at its documented
    // floor.
    const configured = () => value() != null
    return (
      <>
        <div class="field-row field-row-toggle">
          <span class="field-label">{label()}</span>
          <label class="toggle-switch" for={inputId()}>
            <input
              id={inputId()}
              type="checkbox"
              checked={configured()}
              onChange={(e) => props.onChange(props.spec.name, e.currentTarget.checked ? canonicalParamValue(props.spec, bounds().min) : null)}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={configured()}>
          <div class="field-group automation-param-nested">
            <div class="field-row">
              <span class="field-label">{label()}</span>
              <span class="field-value">{value()}</span>
            </div>
            <input
              type="range"
              class="custom-range"
              min={bounds().min}
              max={bounds().max}
              step={props.spec.kind === 'INT' ? 1 : 0.1}
              value={numericValue()}
              onInput={(e) => sliderInput(e.currentTarget.value)}
            />
            <Show when={props.spec.name === 'duration_seconds'}>
              <div class="automation-param-hint">{t('automation.editor.durationHint')}</div>
            </Show>
            <Show when={props.spec.name === 'strength'}>
              <div class="automation-param-hint">{t('automation.editor.torchStrengthHint')}</div>
            </Show>
          </div>
        </Show>
      </>
    )
  }

  // Required numeric: the slider directly, seeded at the catalog default.
  return (
    <div class="field-group">
      <div class="field-row">
        <span class="field-label">{label()}</span>
        <span class="field-value">{value() ?? props.spec.defaultValue}</span>
      </div>
      <input
        type="range"
        class="custom-range"
        min={bounds().min}
        max={bounds().max}
        step={props.spec.kind === 'INT' ? 1 : 0.1}
        value={numericValue()}
        onInput={(e) => sliderInput(e.currentTarget.value)}
      />
    </div>
  )
}
