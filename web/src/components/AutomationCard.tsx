import { createSignal, For, onMount, Show } from 'solid-js'
import type { AutomationRuleDto, AutomationStateDto } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import { getAutomation, putAutomation } from '../api/client'
import { t } from '../lib/i18n'
import {
  AUTOMATION_ALL_DAYS_MASK,
  automationConditionLabelKey,
  automationConditionSummaryLines,
  automationSummaryLines,
  automationTriggerLabelKey,
  automationTriggerSummaryLines,
  formatMinuteOfDay,
  isEveryDayMask,
  newAutomationRule,
} from '../lib/automation'
import { ARM_DAY_LABELS, isDayArmed } from '../armDays'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import AutomationRuleEditor from './AutomationRuleEditor'

interface Props {
  /** True for a viewer-role session: the card renders read-only. */
  readOnly?: () => boolean
}

/**
 * The Automation settings card — the web surface of GET/PUT /api/automation:
 * the master engine switch, one row per time-of-day rule (name, HH:mm, day
 * summary, per-action summary lines, per-rule kill switch, edit/delete) and
 * the catalog-driven rule editor dialog. The card fetches its own document
 * (the RecordingTimeline precedent for self-contained cards) and every
 * mutation PUTs the whole { enabled, rules } document, replacing the local
 * state with the response's sanitized truth — clamped minutes/masks,
 * canonical action params, minted ids.
 */
export default function AutomationCard(props: Props) {
  const [state, setState] = createSignal<AutomationStateDto | null>(null)
  const [loadError, setLoadError] = createSignal('')
  const [saveError, setSaveError] = createSignal('')
  const [saving, setSaving] = createSignal(false)
  // The rule draft the editor dialog is open on; null = closed.
  const [editing, setEditing] = createSignal<AutomationRuleDto | null>(null)
  const [isNewRule, setIsNewRule] = createSignal(false)

  const readOnly = () => props.readOnly?.() ?? false
  const atRuleCap = () => (state()?.rules.length ?? 0) >= API_DEFAULTS.automationMaxRules

  /** A time-window condition's persisted day mask (the sanitize default when absent). */
  const conditionDaysMask = (condition: { params: Record<string, string> }): number => {
    const parsed = Number.parseInt(condition.params['days_mask'] ?? '', 10)
    return Number.isFinite(parsed) ? parsed : AUTOMATION_ALL_DAYS_MASK
  }

  /** The armed ISO day indices (0 = Monday … 6 = Sunday) in a day mask. */
  const armedDaysOf = (daysMask: number): number[] =>
    ARM_DAY_LABELS.map((_, i) => i).filter((i) => isDayArmed(daysMask, i))

  onMount(() => {
    void reload()
  })

  async function reload() {
    setLoadError('')
    try {
      setState(await getAutomation())
    } catch (error) {
      setLoadError(error instanceof Error && error.message ? error.message : t('automation.loadFailed'))
    }
  }

  /** The one persist seam: PUT the whole document, adopt the sanitized response. */
  async function persist(enabled: boolean, rules: AutomationRuleDto[]) {
    setSaving(true)
    setSaveError('')
    try {
      setState(await putAutomation({ enabled, rules }))
    } catch (error) {
      setSaveError(error instanceof Error && error.message ? error.message : t('automation.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  function toggleMaster() {
    const current = state()
    if (current && !readOnly() && !saving()) void persist(!current.enabled, current.rules)
  }

  function setRuleEnabled(rule: AutomationRuleDto, enabled: boolean) {
    const current = state()
    if (!current || readOnly() || saving()) return
    void persist(current.enabled, current.rules.map((entry) => (entry.id === rule.id ? { ...entry, enabled } : entry)))
  }

  function removeRule(rule: AutomationRuleDto) {
    const current = state()
    if (!current || readOnly() || saving()) return
    void persist(current.enabled, current.rules.filter((entry) => entry.id !== rule.id))
  }

  function editRule(rule: AutomationRuleDto) {
    if (readOnly()) return
    setIsNewRule(false)
    setEditing({
      ...rule,
      actions: rule.actions.map((action) => ({ ...action, params: { ...action.params } })),
    })
  }

  function addRule() {
    if (readOnly() || atRuleCap()) return
    setIsNewRule(true)
    setEditing(newAutomationRule())
  }

  function handleEditorSave(rule: AutomationRuleDto) {
    const current = state()
    setEditing(null)
    if (!current || saving()) return
    // A blank id is a fresh draft (the device mints the fire-journal UUID on
    // save); anything else updates the rule with that id. The add path is
    // capped client-side like the app's withRuleAdded.
    if (rule.id !== '') {
      void persist(current.enabled, current.rules.map((entry) => (entry.id === rule.id ? rule : entry)))
    } else if (current.rules.length < API_DEFAULTS.automationMaxRules) {
      void persist(current.enabled, [...current.rules, rule])
    }
  }

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 3" />
        </svg>
      }
      title={t('automation.title')}
    >
      <Show when={!state() && !loadError()}>
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('automation.loading')}</span>
        </div>
      </Show>

      <Show when={loadError()}>
        <div class="error-banner">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <span>{loadError()}</span>
        </div>
      </Show>

      <Show when={state()}>
        <Show when={readOnly()}>
          <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('automation.readOnlyBanner')}</span>
          </div>
        </Show>

        <div class="field-group">
          <ToggleRow
            id="automation-master-toggle"
            label={t('automation.masterEnabled')}
            checked={state()!.enabled}
            disabled={readOnly() || saving()}
            onToggle={toggleMaster}
          />
          {/* Rules stay visible (and editable) while the engine is off — a
              banner explains they will not fire until it is on. */}
          <Show when={!state()!.enabled}>
            <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{t('automation.engineOffBanner')}</span>
            </div>
          </Show>
        </div>

        <For each={state()!.rules}>
          {(rule) => (
            <div class="automation-rule-row" classList={{ 'automation-rule-disabled': !rule.enabled }}>
              <div class="automation-rule-head">
                <div class="automation-rule-title">
                  <span class="automation-rule-name">{rule.name || t('automation.ruleUnnamed')}</span>
                  {/* Time rules show the classic HH:mm; event rules show their
                      localized trigger label (their "when …" detail rides the
                      trigger summary line below). */}
                  <span class="automation-rule-time">
                    {rule.triggerType === 'time_of_day'
                      ? formatMinuteOfDay(rule.minuteOfDay)
                      : t(automationTriggerLabelKey(rule.triggerType))}
                  </span>
                </div>
                <label class="toggle-switch" for={`automation-rule-toggle-${rule.id}`}>
                  <input
                    id={`automation-rule-toggle-${rule.id}`}
                    type="checkbox"
                    checked={rule.enabled}
                    disabled={readOnly() || saving()}
                    onChange={() => setRuleEnabled(rule, !rule.enabled)}
                  />
                  <span class="toggle-slider" />
                </label>
              </div>

              <Show when={rule.triggerType === 'time_of_day'}>
                <div class="automation-rule-days">
                  <Show
                    when={!isEveryDayMask(rule.daysMask)}
                    fallback={<span class="automation-rule-everyday">{t('automation.daysEveryDay')}</span>}
                  >
                    <span class="automation-day-chips">
                      <For each={ARM_DAY_LABELS}>
                        {(label, i) => (
                          <Show when={isDayArmed(rule.daysMask, i())}>
                            <span class="automation-day-chip active">{label}</span>
                          </Show>
                        )}
                      </For>
                    </span>
                  </Show>
                </div>
              </Show>

              <div class="automation-summary-lines">
                <For each={automationTriggerSummaryLines(rule)}>
                  {(line) => <div class="automation-summary-line">{t(line.key, line.params)}</div>}
                </For>
                <Show when={rule.conditions.length > 0}>
                  <div class="automation-summary-line automation-summary-conditions">
                    {t('automation.ruleConditions')}
                    <For each={rule.conditions}>
                      {(condition) => (
                        <div class="automation-summary-line automation-summary-condition">
                          {t(automationConditionLabelKey(condition.type))}
                          <For each={automationConditionSummaryLines(condition)}>
                            {(line) => <span> · {t(line.key, line.params)}</span>}
                          </For>
                          {/* The time window's day suffix needs the localized
                              day chips' labels, so it composes here. */}
                          <Show when={condition.type === 'time_window' && !isEveryDayMask(conditionDaysMask(condition))}>
                            <span>
                              {' · '}
                              <For each={armedDaysOf(conditionDaysMask(condition))}>
                                {(day, i) => (
                                  <>
                                    <Show when={i() > 0}>, </Show>
                                    {ARM_DAY_LABELS[day]}
                                  </>
                                )}
                              </For>
                            </span>
                          </Show>
                        </div>
                      )}
                    </For>
                  </div>
                </Show>
                <Show
                  when={rule.actions.length > 0}
                  fallback={<span class="automation-summary-empty">{t('automation.ruleNoActions')}</span>}
                >
                  <For each={rule.actions.flatMap((action) => automationSummaryLines(action))}>
                    {(line) => <div class="automation-summary-line">{t(line.key, line.params)}</div>}
                  </For>
                </Show>
              </div>

              <div class="automation-rule-buttons">
                <button class="card-btn card-btn-ghost" onClick={() => editRule(rule)} disabled={readOnly()} title={t('automation.editRuleAria')} aria-label={t('automation.editRuleAria')}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7" />
                    <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z" />
                  </svg>
                  {t('automation.edit')}
                </button>
                <button class="card-btn card-btn-danger" onClick={() => removeRule(rule)} disabled={readOnly() || saving()} title={t('automation.deleteRuleAria')} aria-label={t('automation.deleteRuleAria')}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6" />
                  </svg>
                  {t('common.delete')}
                </button>
              </div>
            </div>
          )}
        </For>

        <div class="field-group automation-add-rule">
          <button class="card-btn card-btn-primary" onClick={addRule} disabled={readOnly() || saving() || atRuleCap()}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 5v14M5 12h14" />
            </svg>
            {t('automation.addRule')}
          </button>
          <Show when={atRuleCap()}>
            <span class="automation-cap-note">{t('automation.maxRulesReached', { count: API_DEFAULTS.automationMaxRules })}</span>
          </Show>
        </div>

        <Show when={saving()}>
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('automation.saving')}</span>
          </div>
        </Show>
        <Show when={saveError()}>
          <div class="error-banner">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <span>{saveError()}</span>
          </div>
        </Show>
      </Show>

      <Show when={editing()} keyed>
        {(draft) => (
          <AutomationRuleEditor
            draft={draft}
            isNew={isNewRule()}
            catalog={state()?.catalog ?? []}
            triggers={state()?.triggers ?? []}
            conditions={state()?.conditions ?? []}
            saving={saving()}
            onClose={() => setEditing(null)}
            onSave={handleEditorSave}
          />
        )}
      </Show>
    </SettingsCard>
  )
}
