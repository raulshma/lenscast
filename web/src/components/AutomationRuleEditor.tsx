import { createEffect, createSignal, For, onCleanup, Show } from 'solid-js'
import type {
  AutomationCatalogActionDto,
  AutomationCatalogConditionDto,
  AutomationCatalogParamDto,
  AutomationCatalogTriggerDto,
  AutomationRuleDto,
} from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { t } from '../lib/i18n'
import {
  AUTOMATION_ACTION_GROUPS,
  AUTOMATION_CONDITION_GROUPS,
  AUTOMATION_COOLDOWN_PRESETS,
  AUTOMATION_TRIGGER_GROUPS,
  automationActionLabelKey,
  automationConditionLabelKey,
  automationConditionSummaryLines,
  automationDayLabelKey,
  automationDescKey,
  automationParamLabelKey,
  automationTriggerLabelKey,
  automationTriggerSummaryLines,
  automationSummaryLines,
  canonicalParamValue,
  conditionDaysMask,
  dayListLabel,
  defaultActionParams,
  defaultConditionParams,
  defaultTriggerParams,
  formatMinuteOfDay,
  groupedPickerOptions,
  humanizeDuration,
  isAutoSentinelParam,
  isEveryDayMask,
  joinSummaryLines,
  moveSpec,
  paramSliderBounds,
  parseMinuteOfDay,
  withSpecParam,
} from '../lib/automation'
import { AutomationClockGlyph, AutomationIcon, type AutomationIconKind } from '../lib/automationIcons'
import { isDayArmed, toggleArmDayMask } from '../armDays'

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
  /** Resolves true when the PUT succeeded — only then does the card close the editor; a failed save keeps the edits on screen. */
  onSave: (rule: AutomationRuleDto) => Promise<boolean>
}

/** One editor-local action/condition. `key` is a stable per-dialog instance id (DOM id uniqueness); stripped on save. */
interface DraftSpec {
  type: string
  params: Record<string, string>
  key: number
}

/** Which catalog the open picker dialog browses; null = closed. */
type PickerTarget = 'trigger' | 'condition' | 'action'

/** The re-fire cooldown slider's display ceiling (the persisted bound allows a day; no useful cooldown needs a slider that wide). */
const COOLDOWN_SLIDER_MAX = 3_600

/**
 * The rule editor dialog — the web twin of the app's AutomationRuleEditor:
 * WHEN / ONLY WHEN / THEN sections with colored headers and counts, grouped
 * icon+description picker dialogs (replacing the bare select rows),
 * `<input type="time">` fields for the time-window bounds, cooldown preset
 * chips with a humanized readout, reorder arrows on the action/condition
 * cards (actions execute in list order), a live preview of the whole rule
 * sentence, and the guards: Save is gated on at least one action, and every
 * close path (X, Escape, backdrop) confirms before discarding unsaved edits.
 * Nothing persists until Save resolves true — a failed PUT keeps the editor
 * open. The whole WHEN shape stays catalog-driven (per-param editors shaped
 * by each AutomationCatalogParamDto: BOOL switches, optional params behind a
 * configure switch, the camera_exposure auto-sentinel Auto toggles, INT/FLOAT
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
  // The open catalog picker (null = closed); the inline save gate; the
  // unsaved-edits guard; and the in-flight save flag (Save stays disabled
  // until the PUT resolves).
  const [picker, setPicker] = createSignal<PickerTarget | null>(null)
  const [needAction, setNeedAction] = createSignal(false)
  const [dirty, setDirty] = createSignal(false)
  const [submitting, setSubmitting] = createSignal(false)
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
  const saveDisabled = () => props.saving || submitting() || actions().length === 0

  // Adding an action clears the inline save gate live.
  createEffect(() => {
    if (actions().length > 0) setNeedAction(false)
  })

  function setTriggerParam(paramName: string, value: string | null) {
    triggerParams = withSpecParam(triggerParams, paramName, value)
    setSpecsVersion((v) => v + 1)
    setDirty(true)
  }

  function setSpecParam(spec: DraftSpec, paramName: string, value: string | null) {
    if (value == null || value === '') delete spec.params[paramName]
    else spec.params[paramName] = value
    setSpecsVersion((v) => v + 1)
    setDirty(true)
  }

  function changeTrigger(typeId: string) {
    setTriggerType(typeId)
    triggerParams = defaultTriggerParams(triggerFor(typeId))
    setSpecsVersion((v) => v + 1)
    setDirty(true)
  }

  function removeAction(index: number) {
    setActions((prev) => prev.filter((_, i) => i !== index))
    setDirty(true)
  }

  function addAction(typeId: string) {
    if (!typeId || atActionCap()) return
    setActions((prev) => [...prev, { type: typeId, params: defaultActionParams(catalogFor(typeId)), key: nextKey++ }])
    setDirty(true)
  }

  function moveAction(index: number, delta: number) {
    setActions((prev) => moveSpec(prev, index, delta))
    setDirty(true)
  }

  function removeCondition(index: number) {
    setConditions((prev) => prev.filter((_, i) => i !== index))
    setDirty(true)
  }

  function addCondition(typeId: string) {
    if (!typeId || atConditionCap()) return
    setConditions((prev) => [...prev, { type: typeId, params: defaultConditionParams(conditionFor(typeId)), key: nextKey++ }])
    setDirty(true)
  }

  function moveCondition(index: number, delta: number) {
    setConditions((prev) => moveSpec(prev, index, delta))
    setDirty(true)
  }

  /** The picker dialog's option model for one target. */
  const pickerGroups = (target: PickerTarget) => {
    switch (target) {
      case 'trigger':
        return groupedPickerOptions(AUTOMATION_TRIGGER_GROUPS, props.triggers.map((entry) => entry.id))
      case 'condition':
        return groupedPickerOptions(AUTOMATION_CONDITION_GROUPS, props.conditions.map((entry) => entry.id))
      case 'action':
        return groupedPickerOptions(AUTOMATION_ACTION_GROUPS, props.catalog.map((entry) => entry.id))
    }
  }

  /** One picker pick: the trigger picker swaps the WHEN, the other two append a spec. */
  function pickOption(target: PickerTarget, typeId: string) {
    if (target === 'trigger') changeTrigger(typeId)
    else if (target === 'condition') addCondition(typeId)
    else addAction(typeId)
    setPicker(null)
  }

  // ── live preview ──

  const previewWhenText = () => {
    if (isTimeTrigger()) {
      const dayText = isEveryDayMask(daysMask())
        ? t('automation.daysEveryDay')
        : dayListLabel(daysMask(), t)
      return `${formatMinuteOfDay(minuteOfDay())} · ${dayText}`
    }
    specsVersion()
    return joinSummaryLines(automationTriggerSummaryLines({ triggerType: triggerType(), triggerParams }), t)
  }

  const previewOnlyText = () => {
    const list = conditions()
    if (list.length === 0) return t('automation.previewAlways')
    const joiner = conditionMode() === 'any' ? t('automation.joinOr') : t('automation.joinAnd')
    specsVersion()
    // The same per-condition shape the card's ONLY WHEN row renders — the
    // time window's localized day suffix included, or the preview would
    // diverge from what saving actually shows.
    return list
      .map((condition) => {
        const summary = joinSummaryLines(automationConditionSummaryLines(condition), t)
        if (condition.type === 'time_window' && !isEveryDayMask(conditionDaysMask(condition))) {
          return `${summary} · ${dayListLabel(conditionDaysMask(condition), t)}`
        }
        return summary
      })
      .join(` ${joiner} `)
  }

  const previewThenText = () => {
    const list = actions()
    if (list.length === 0) return t('automation.editor.needAction')
    specsVersion()
    return joinSummaryLines(list.flatMap((action) => automationSummaryLines(action)), t)
  }

  // ── close / save guards ──

  /** Every close path (X, Escape, backdrop) funnels here: unsaved edits confirm first. */
  function attemptClose() {
    if (dirty() && !window.confirm(t('automation.editor.discardBody'))) return
    props.onClose()
  }

  function buildRule(): AutomationRuleDto {
    return {
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
    }
  }

  async function save() {
    if (actions().length === 0) {
      setNeedAction(true)
      return
    }
    setSubmitting(true)
    try {
      const ok = await props.onSave(buildRule())
      if (ok) setDirty(false) // the card closes the editor on success
    } finally {
      setSubmitting(false)
    }
  }

  // Escape closes the picker first, then the editor (with the discard
  // guard), mirroring the dashboard's other dialogs.
  createEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        if (picker()) {
          setPicker(null)
          return
        }
        attemptClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    onCleanup(() => document.removeEventListener('keydown', handleKeyDown))
  })

  return (
    <div class="gallery-overlay automation-editor-overlay" onClick={attemptClose}>
      <div class="automation-editor-modal" onClick={(e) => e.stopPropagation()}>
        <div class="gallery-header">
          <button class="navbar-icon-btn" onClick={attemptClose} title={t('common.close')} aria-label={t('automation.editor.closeAria')}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
          <h2>{props.isNew ? t('automation.editor.newTitle') : t('automation.editor.editTitle')}</h2>
          <button class="card-btn card-btn-primary automation-editor-save" onClick={save} disabled={saveDisabled()}>
            {submitting() ? t('automation.saving') : t('common.save')}
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
              onInput={(e) => {
                setName(e.currentTarget.value)
                setDirty(true)
              }}
              placeholder={t('automation.ruleUnnamed')}
            />
          </div>

          {/* ── WHEN (the trigger) ── */}
          <div class="automation-section automation-section-when">
            <div class="automation-section-head">
              <AutomationClockGlyph />
              <span>{t('automation.when')}</span>
            </div>
            {/* The tappable trigger row: icon + label + description, opening
                the grouped picker (the old bare select's replacement). */}
            <button type="button" class="automation-trigger-pick" onClick={() => setPicker('trigger')}>
              <span class="automation-rule-badge automation-badge-trigger" aria-hidden="true">
                <AutomationIcon kind="trigger" typeId={triggerType()} />
              </span>
              <span class="automation-picker-option-text">
                <span class="automation-picker-option-label">{t(automationTriggerLabelKey(triggerType()))}</span>
                <span class="automation-picker-option-desc">{t(automationDescKey('trigger', triggerType()))}</span>
              </span>
              <span class="automation-trigger-pick-change">{t('automation.editor.change')}</span>
            </button>

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
                      <span class="field-value automation-cooldown-readout">
                        {t(humanizeDuration(cooldownSeconds()).key, humanizeDuration(cooldownSeconds()).params)}
                      </span>
                    </div>
                    <div class="automation-cooldown-presets" role="group" aria-label={t('automation.editor.cooldown')}>
                      <For each={AUTOMATION_COOLDOWN_PRESETS}>
                        {(seconds) => {
                          const line = humanizeDuration(seconds)
                          return (
                            <button
                              type="button"
                              class="automation-cooldown-chip"
                              classList={{ active: cooldownSeconds() === seconds }}
                              onClick={() => {
                                setCooldownSeconds(seconds)
                                setDirty(true)
                              }}
                            >
                              {t(line.key, line.params)}
                            </button>
                          )
                        }}
                      </For>
                    </div>
                    <input
                      id="automation-rule-cooldown"
                      type="range"
                      class="custom-range"
                      min={API_DEFAULTS.automationCooldownMinSeconds}
                      max={COOLDOWN_SLIDER_MAX}
                      step={30}
                      value={Math.min(Math.max(cooldownSeconds(), API_DEFAULTS.automationCooldownMinSeconds), COOLDOWN_SLIDER_MAX)}
                      onInput={(e) => {
                        const parsed = Number.parseInt(e.currentTarget.value, 10)
                        if (Number.isFinite(parsed)) {
                          setCooldownSeconds(parsed)
                          setDirty(true)
                        }
                      }}
                    />
                    <div class="automation-param-hint">{t('automation.editor.cooldownHint')}</div>
                  </div>
                </>
              }
            >
              <div class="field-group">
                <AutomationTimeField
                  id="automation-rule-time"
                  label={t('automation.editor.time')}
                  onCommit={(minute) => {
                    setMinuteOfDay(minute)
                    setDirty(true)
                  }}
                  text={timeText()}
                  onText={(text) => setTimeText(text)}
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
                  <DayChips mask={daysMask()} onToggle={(day) => {
                    setDaysMask(toggleArmDayMask(daysMask(), day))
                    setDirty(true)
                  }} />
                </div>
              </div>
            </Show>

            <div class="field-row field-row-toggle automation-enabled-row">
              <span class="field-label">{t('automation.editor.enabled')}</span>
              <label class="toggle-switch" for="automation-rule-enabled">
                <input
                  id="automation-rule-enabled"
                  type="checkbox"
                  checked={enabled()}
                  onChange={() => {
                    setEnabled(!enabled())
                    setDirty(true)
                  }}
                />
                <span class="toggle-slider" />
              </label>
            </div>
          </div>

          {/* ── ONLY WHEN (the preconditions) ── */}
          <div class="automation-section automation-section-onlywhen">
            <div class="automation-section-head">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                <path d="M4 4h16l-6 8v6l-4 2v-8z" />
              </svg>
              <span>{t('automation.onlyWhen')}</span>
              <span class="automation-section-count">
                {conditions().length}/{API_DEFAULTS.automationMaxConditionsPerRule}
              </span>
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
                      onChange={() => {
                        setConditionMode(conditionMode() === 'any' ? 'all' : 'any')
                        setDirty(true)
                      }}
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
                  onChange={(paramName, value) => setSpecParam(condition, paramName, value)}
                  onRemove={() => removeCondition(index())}
                  canMoveUp={index() > 0}
                  canMoveDown={index() < conditions().length - 1}
                  onMove={(delta) => moveCondition(index(), delta)}
                />
              )}
            </For>

            <button class="card-btn automation-add-spec-btn" onClick={() => setPicker('condition')} disabled={atConditionCap()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {t('automation.editor.addCondition')}
            </button>
          </div>

          {/* ── THEN (the actions) ── */}
          <div class="automation-section automation-section-then">
            <div class="automation-section-head">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                <path d="M6 4l14 8-14 8z" />
              </svg>
              <span>{t('automation.then')}</span>
              <span class="automation-section-count">
                {actions().length}/{API_DEFAULTS.automationMaxActionsPerRule}
              </span>
            </div>

            {/* The inline save gate: a rule without actions cannot fire. */}
            <Show when={needAction()}>
              <div class="automation-editor-error" role="alert">{t('automation.editor.needAction')}</div>
            </Show>

            <For each={actions()}>
              {(action, index) => (
                <AutomationActionEditorCard
                  typeId={action.type}
                  params={action.params}
                  version={specsVersion}
                  catalogAction={catalogFor(action.type)}
                  idPrefix={`automation-action-${action.key}`}
                  lastManualByParam={lastManualByParam}
                  onChange={(paramName, value) => setSpecParam(action, paramName, value)}
                  onRemove={() => removeAction(index())}
                  canMoveUp={index() > 0}
                  canMoveDown={index() < actions().length - 1}
                  onMove={(delta) => moveAction(index(), delta)}
                />
              )}
            </For>

            <button class="card-btn automation-add-spec-btn" onClick={() => setPicker('action')} disabled={atActionCap()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {t('automation.editor.addAction')}
            </button>
          </div>

          {/* ── live preview ── */}
          <div class="automation-preview">
            <div class="automation-preview-title">{t('automation.editor.previewTitle')}</div>
            <div class="automation-when-row automation-when">
              <span class="automation-when-label">{t('automation.when')}</span>
              <span class="automation-when-text">{previewWhenText()}</span>
            </div>
            <div class="automation-when-row automation-onlywhen">
              <span class="automation-when-label">{t('automation.onlyWhen')}</span>
              <span class="automation-when-text">{previewOnlyText()}</span>
            </div>
            <div class="automation-when-row automation-then">
              <span class="automation-when-label">{t('automation.then')}</span>
              <span class="automation-when-text">{previewThenText()}</span>
            </div>
          </div>
        </div>
      </div>

      {/* The grouped catalog picker dialog. */}
      <Show when={picker()} keyed>
        {(target) => (
          <div class="automation-picker-overlay" onClick={() => setPicker(null)}>
            <div class="automation-picker-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={t(`automation.editor.pick${target === 'trigger' ? 'Trigger' : target === 'condition' ? 'Condition' : 'Action'}`)}>
              <div class="automation-picker-head">
                <h3>
                  {t(
                    `automation.editor.pick${target === 'trigger' ? 'Trigger' : target === 'condition' ? 'Condition' : 'Action'}`,
                  )}
                </h3>
                <button class="navbar-icon-btn" onClick={() => setPicker(null)} title={t('common.close')} aria-label={t('common.close')}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M18 6L6 18M6 6l12 12" />
                  </svg>
                </button>
              </div>
              <div class="automation-picker-body">
                <For each={pickerGroups(target)}>
                  {(group) => (
                    <div class="automation-picker-group">
                      <div class="automation-picker-group-title">{t(group.titleKey)}</div>
                      <For each={group.ids}>
                        {(typeId) => {
                          const label =
                            target === 'trigger'
                              ? t(automationTriggerLabelKey(typeId))
                              : target === 'condition'
                                ? t(automationConditionLabelKey(typeId))
                                : t(automationActionLabelKey(typeId))
                          return (
                            <AutomationPickerOption
                              typeId={typeId}
                              kind={target}
                              label={label}
                              description={t(automationDescKey(target, typeId))}
                              selected={target === 'trigger' && typeId === triggerType()}
                              onPick={() => pickOption(target, typeId)}
                            />
                          )
                        }}
                      </For>
                    </div>
                  )}
                </For>
              </div>
            </div>
          </div>
        )}
      </Show>
    </div>
  )
}

/** Localized, toggle-safe day chips (the ISO bit order, Monday first). */
function DayChips(props: { mask: number; onToggle: (isoDayIndex: number) => void }) {
  return (
    <For each={[0, 1, 2, 3, 4, 5, 6]}>
      {(day) => (
        <button
          type="button"
          class="arm-day-chip"
          classList={{ active: isDayArmed(props.mask, day) }}
          onClick={() => props.onToggle(day)}
        >
          {t(automationDayLabelKey(day))}
        </button>
      )}
    </For>
  )
}

/**
 * One HH:mm field over `<input type="time">`: [text] is the raw input text
 * (partial keystrokes survive via [onText]) and the value only commits on a
 * valid full "HH:mm" — the same contract as the trigger's time input.
 */
function AutomationTimeField(props: {
  id: string
  label: string
  onCommit: (minuteOfDay: number) => void
  text: string
  onText: (text: string) => void
}) {
  return (
    <div class="field-row automation-time-field">
      <label class="field-label" for={props.id}>{props.label}</label>
      <input
        id={props.id}
        type="time"
        class="field-input automation-time-input"
        value={props.text}
        onInput={(e) => {
          const raw = e.currentTarget.value
          const parsed = parseMinuteOfDay(raw)
          if (parsed != null) {
            props.onCommit(parsed)
            props.onText(formatMinuteOfDay(parsed))
          } else {
            props.onText(raw)
          }
        }}
      />
    </div>
  )
}

/** One picker row: icon, label, one-line description, check on the current selection. */
function AutomationPickerOption(props: {
  typeId: string
  kind: AutomationIconKind
  label: string
  description: string
  selected: boolean
  onPick: () => void
}) {
  return (
    <button type="button" class="automation-picker-option" classList={{ selected: props.selected }} onClick={props.onPick}>
      <span class="automation-picker-option-icon" aria-hidden="true">
        <AutomationIcon kind={props.kind} typeId={props.typeId} />
      </span>
      <span class="automation-picker-option-text">
        <span class="automation-picker-option-label">{props.label}</span>
        <span class="automation-picker-option-desc">{props.description}</span>
      </span>
      <Show when={props.selected}>
        <svg class="automation-picker-option-check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
          <path d="M5 13l4 4L19 7" />
        </svg>
      </Show>
    </button>
  )
}

/** One action card inside the editor: pretty label, reorder arrows, stream-interrupt warning, per-param editors. */
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
  canMoveUp: boolean
  canMoveDown: boolean
  onMove: (delta: number) => void
}) {
  return (
    <div class="automation-action-row">
      <div class="automation-action-head">
        <span class="automation-action-icon" aria-hidden="true">
          <AutomationIcon kind="action" typeId={props.typeId} />
        </span>
        <span class="automation-action-label">{t(automationActionLabelKey(props.typeId))}</span>
        <span class="automation-reorder">
          <ReorderButtons canUp={props.canMoveUp} canDown={props.canMoveDown} onMove={props.onMove} />
        </span>
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

/** The paired ↑/↓ reorder buttons (actions execute in list order — surfaced). */
function ReorderButtons(props: { canUp: boolean; canDown: boolean; onMove: (delta: number) => void }) {
  return (
    <>
      <button
        type="button"
        class="navbar-icon-btn"
        disabled={!props.canUp}
        onClick={() => props.onMove(-1)}
        title={t('automation.editor.moveUpAria')}
        aria-label={t('automation.editor.moveUpAria')}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 15l-6-6-6 6" />
        </svg>
      </button>
      <button
        type="button"
        class="navbar-icon-btn"
        disabled={!props.canDown}
        onClick={() => props.onMove(1)}
        title={t('automation.editor.moveDownAria')}
        aria-label={t('automation.editor.moveDownAria')}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
    </>
  )
}

/** One condition card inside the editor: pretty label, reorder arrows, remove affordance; the time window renders as time fields + day chips. */
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
  canMoveUp: boolean
  canMoveDown: boolean
  onMove: (delta: number) => void
}) {
  return (
    <div class="automation-action-row">
      <div class="automation-action-head">
        <span class="automation-action-icon" aria-hidden="true">
          <AutomationIcon kind="condition" typeId={props.typeId} />
        </span>
        <span class="automation-action-label">{t(automationConditionLabelKey(props.typeId))}</span>
        <span class="automation-reorder">
          <ReorderButtons canUp={props.canMoveUp} canDown={props.canMoveDown} onMove={props.onMove} />
        </span>
        <button class="navbar-icon-btn" onClick={props.onRemove} title={t('common.delete')} aria-label={t('automation.editor.removeConditionAria')}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
      <Show
        when={props.typeId === 'time_window'}
        fallback={
          <For each={props.catalogCondition?.params ?? []}>
            {(spec) => (
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
            )}
          </For>
        }
      >
        <AutomationTimeWindowEditor
          idPrefix={props.idPrefix}
          params={props.params}
          version={props.version}
          onChange={props.onChange}
        />
      </Show>
    </div>
  )
}

/**
 * The time-window editor: real HH:mm fields for the window bounds (the raw
 * minute sliders' replacement — AutomationScreen's AutomationTimeField twin)
 * plus the localized day chips. Bounds default to the sanitize defaults
 * (0 / 1439); a from > until wraps to the next day, like the engine.
 */
function AutomationTimeWindowEditor(props: {
  idPrefix: string
  /** The stable draft params record — mutated in place by onChange. */
  params: Record<string, string>
  /** The editor's mutation counter — read before every params lookup. */
  version: () => number
  onChange: (name: string, value: string | null) => void
}) {
  const startMinute = () => {
    props.version()
    const parsed = Number.parseInt(props.params['start_minute'] ?? '', 10)
    return Number.isFinite(parsed) ? parsed : 0
  }
  const endMinute = () => {
    props.version()
    const parsed = Number.parseInt(props.params['end_minute'] ?? '', 10)
    return Number.isFinite(parsed) ? parsed : 1_439
  }
  const daysMask = () => {
    props.version()
    const parsed = Number.parseInt(props.params['days_mask'] ?? '', 10)
    return Number.isFinite(parsed) ? parsed : 127
  }
  return (
    <div class="field-group automation-window-editor">
      <div class="automation-window-fields">
        <AutomationTimeField
          id={`${props.idPrefix}-start`}
          label={t('automation.param.windowStart')}
          onCommit={(minute) => props.onChange('start_minute', String(minute))}
          text={formatMinuteOfDay(startMinute())}
          onText={() => { /* type=time is always a full value or empty */ }}
        />
        <AutomationTimeField
          id={`${props.idPrefix}-end`}
          label={t('automation.param.windowEnd')}
          onCommit={(minute) => props.onChange('end_minute', String(minute))}
          text={formatMinuteOfDay(endMinute())}
          onText={() => { /* type=time is always a full value or empty */ }}
        />
      </div>
      <div class="field-row">
        <span class="field-label">{t('automation.param.windowDays')}</span>
      </div>
      <div class="arm-days-row" role="group" aria-label={t('automation.param.windowDays')}>
        <DayChips
          mask={daysMask()}
          onToggle={(day) => props.onChange('days_mask', String(toggleArmDayMask(daysMask(), day)))}
        />
      </div>
      <div class="automation-param-hint">{t('automation.editor.windowWrapHint')}</div>
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
