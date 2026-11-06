import { createSignal, For, Show } from 'solid-js'
import type { AutomationRuleDto, AutomationStateDto } from '../types'
import { getAutomation, putAutomation } from '../api/client'
import { formatDate, t } from '../lib/i18n'
import { createVisiblePoll } from '../hooks/visiblePoll'
import {
  automationConditionSummaryLines,
  automationMissedRunSummaryLine,
  automationSummaryLines,
  automationTriggerLabelKey,
  automationTriggerSummaryLines,
  conditionDaysMask,
  dayListLabel,
  automationLimits,
  formatMinuteOfDay,
  isEveryDayMask,
  joinSummaryLines,
  lastFiredLabel,
  newAutomationRule,
  withRuleDuplicated,
} from '../lib/automation'
import { AutomationBoltGlyph, AutomationClockGlyph, AutomationIcon } from '../lib/automationIcons'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import AutomationRuleEditor from './AutomationRuleEditor'

interface Props {
  /** True for a viewer-role session: the card renders read-only. */
  readOnly?: () => boolean
}

/**
 * The Automation settings card — the web surface of GET/PUT /api/automation:
 * the master engine switch, one structured card per rule (trigger badge, the
 * WHEN / ONLY WHEN / THEN summary rows, the last-fired line, per-rule kill
 * switch, duplicate/delete) and the catalog-driven rule editor dialog. The
 * card fetches its own document (the RecordingTimeline precedent for
 * self-contained cards), refetches on a 60s visibility-gated poll so the fire
 * status stays live, and every mutation PUTs the whole { enabled, rules }
 * document, replacing the local state with the response's sanitized truth —
 * clamped minutes/masks, canonical action params, minted ids.
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
  // The editor contract's caps/presets: device truth once fetched
  // (AutomationStateDto.limits), the contract-test-pinned fallback before.
  const limits = () => automationLimits(state())
  const atRuleCap = () => (state()?.rules.length ?? 0) >= limits().maxRules
  /** The published icon kind of a rule's trigger, resolved from the fetched catalog. */
  const triggerIcon = (typeId: string): string => state()?.triggers.find((entry) => entry.id === typeId)?.icon ?? ''

  /** The WHEN row text: "HH:mm · days" for time rules, the "when …" line for events. */
  const whenText = (rule: AutomationRuleDto): string => {
    if (rule.triggerType === 'time_of_day') {
      const dayText = isEveryDayMask(rule.daysMask)
        ? t('automation.daysEveryDay')
        : dayListLabel(rule.daysMask, t)
      return `${formatMinuteOfDay(rule.minuteOfDay)} · ${dayText}`
    }
    return joinSummaryLines(automationTriggerSummaryLines(rule), t)
  }

  /** The ONLY WHEN row: all conditions joined by the mode's localized joiner. */
  const onlyWhenText = (rule: AutomationRuleDto): string => {
    const joiner = rule.conditionMode === 'any' ? t('automation.joinOr') : t('automation.joinAnd')
    return rule.conditions
      .map((condition) => {
        const summary = automationConditionText(condition)
        if (condition.type === 'time_window' && !isEveryDayMask(conditionDaysMask(condition))) {
          return `${summary} · ${dayListLabel(conditionDaysMask(condition), t)}`
        }
        return summary
      })
      .join(` ${joiner} `)
  }

  /** One condition's summary (label-less, self-descriptive — the app's conditionText). */
  const automationConditionText = (condition: { type: string; params: Record<string, string> }): string =>
    joinSummaryLines(automationConditionSummaryLines(condition), t)

  /** The THEN row: the merged action summaries, or the missing-actions warning. */
  const thenText = (rule: AutomationRuleDto): string =>
    rule.actions.length === 0
      ? t('automation.ruleNoActions')
      : joinSummaryLines(rule.actions.flatMap((action) => automationSummaryLines(action)), t)

  /** The card's "Last ran …" line for the rule's fire status, null when it never fired. */
  const lastFiredText = (rule: AutomationRuleDto): string | null => {
    const label = rule.lastFiredAt != null ? lastFiredLabel(rule.lastFiredAt, Date.now()) : null
    if (!label) return null
    const time = formatMinuteOfDay(label.minuteOfDay)
    switch (label.kind) {
      case 'today':
        return t('automation.lastFired.today', { time })
      case 'yesterday':
        return t('automation.lastFired.yesterday', { time })
      case 'earlier':
        return t('automation.lastFired.on', { date: formatDate(label.epochMillis, { dateStyle: 'medium' }), time })
    }
  }

  // The initial fetch plus a 60s visibility-gated poll: the fire journal only
  // changes device-side, so the last-fired lines stay live without a poll
  // ladder. A failed background refetch keeps the stale rules on screen.
  createVisiblePoll(
    () => void reload(true),
    60_000,
  )

  async function reload(silent = false) {
    if (!silent) setLoadError('')
    try {
      setState(await getAutomation())
    } catch (error) {
      if (!silent || !state()) {
        setLoadError(error instanceof Error && error.message ? error.message : t('automation.loadFailed'))
      }
    }
  }

  /** The one persist seam: PUT the whole document, adopt the sanitized response. True = the response was adopted. */
  async function persist(enabled: boolean, rules: AutomationRuleDto[]): Promise<boolean> {
    setSaving(true)
    setSaveError('')
    try {
      setState(await putAutomation({ enabled, rules }))
      return true
    } catch (error) {
      setSaveError(error instanceof Error && error.message ? error.message : t('automation.saveFailed'))
      return false
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
    // The Gallery/AuditCard precedent: destructive, so a confirm guards it.
    if (!window.confirm(t('automation.deleteConfirm', { name: rule.name || t('automation.ruleUnnamed') }))) return
    void persist(current.enabled, current.rules.filter((entry) => entry.id !== rule.id))
  }

  function duplicateRule(rule: AutomationRuleDto) {
    const current = state()
    if (!current || readOnly() || saving() || atRuleCap()) return
    const copyName = rule.name ? t('automation.ruleCopyOf', { name: rule.name }) : t('automation.ruleCopy')
    void persist(current.enabled, withRuleDuplicated(current.rules, rule.id, copyName, limits().maxRules))
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

  /**
   * The editor hands back its draft; the editor stays open until the PUT
   * succeeds (a failed save keeps the edits on screen with the error
   * visible in the card behind). A blank id is a fresh draft (the device
   * mints the fire-journal UUID on save); anything else updates the rule
   * with that id. The add path is capped client-side like the app's
   * withRuleAdded.
   */
  async function handleEditorSave(rule: AutomationRuleDto): Promise<boolean> {
    const current = state()
    if (!current || saving()) return false
    let rules: AutomationRuleDto[]
    if (rule.id !== '') {
      rules = current.rules.map((entry) => (entry.id === rule.id ? rule : entry))
    } else {
      if (current.rules.length >= limits().maxRules) return false
      rules = [...current.rules, rule]
    }
    const ok = await persist(current.enabled, rules)
    if (ok) setEditing(null)
    return ok
  }

  return (
    <SettingsCard
      icon={<AutomationClockGlyph />}
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
          <Show when={state()!.rules.length > 0}>
            <span class="automation-cap-note">
              {t('automation.rulesCount', { count: state()!.rules.length, total: limits().maxRules })}
            </span>
          </Show>
        </div>

        {/* The empty state: what rules do, plus the first CTA — the add
            button below stays for the non-empty list. */}
        <Show when={state()!.rules.length === 0}>
          <div class="automation-empty">
            <span class="automation-empty-icon" aria-hidden="true">
              <AutomationBoltGlyph />
            </span>
            <span class="automation-empty-title">{t('automation.emptyTitle')}</span>
            <span class="automation-empty-body">{t('automation.emptyBody')}</span>
            <button class="card-btn card-btn-primary" onClick={addRule} disabled={readOnly() || saving() || atRuleCap()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {t('automation.addRule')}
            </button>
          </div>
        </Show>

        <For each={state()!.rules}>
          {(rule) => (
            <div class="automation-rule-row" classList={{ 'automation-rule-disabled': !rule.enabled }}>
              <div class="automation-rule-head">
                {/* The badge + name zone taps to edit; the switch and the
                    button row stay separate targets. */}
                <button
                  type="button"
                  class="automation-rule-main"
                  onClick={() => editRule(rule)}
                  disabled={readOnly()}
                  title={t('automation.editRuleAria')}
                >
                  <span class="automation-rule-badge automation-badge-trigger" aria-hidden="true">
                    <AutomationIcon icon={triggerIcon(rule.triggerType)} />
                  </span>
                  <span class="automation-rule-heading">
                    <span class="automation-rule-name">{rule.name || t('automation.ruleUnnamed')}</span>
                    {/* Time rules show the classic HH:mm · days; event rules
                        show their localized trigger label. */}
                    <span class="automation-rule-sub">
                      {rule.triggerType === 'time_of_day'
                        ? whenText(rule)
                        : t(automationTriggerLabelKey(rule.triggerType))}
                    </span>
                  </span>
                </button>
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

              <div class="automation-when-rows">
                <Show when={rule.triggerType !== 'time_of_day'}>
                  <div class="automation-when-row automation-when">
                    <span class="automation-when-label">{t('automation.when')}</span>
                    <span class="automation-when-text">{whenText(rule)}</span>
                  </div>
                </Show>
                <Show when={rule.conditions.length > 0}>
                  <div class="automation-when-row automation-onlywhen">
                    <span class="automation-when-label">{t('automation.onlyWhen')}</span>
                    <span class="automation-when-text">{onlyWhenText(rule)}</span>
                  </div>
                </Show>
                <div class="automation-when-row automation-then">
                  <span class="automation-when-label">{t('automation.then')}</span>
                  <span class="automation-when-text" classList={{ 'automation-then-missing': rule.actions.length === 0 }}>
                    {thenText(rule)}
                  </span>
                </div>
                {/* IF MISSED: the missed-run policy, only when it deviates from
                    the historical catch-up default (time rules only). */}
                <Show when={automationMissedRunSummaryLine(rule)} keyed>
                  {(line) => (
                    <div class="automation-when-row automation-onlywhen">
                      <span class="automation-when-label">{t('automation.ifMissed')}</span>
                      <span class="automation-when-text">{t(line.key, line.params)}</span>
                    </div>
                  )}
                </Show>
                <Show when={lastFiredText(rule)} keyed>
                  {(text) => (
                    <div class="automation-lastfired">
                      <AutomationClockGlyph />
                      <span>{text}</span>
                    </div>
                  )}
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
                <button class="card-btn card-btn-ghost" onClick={() => duplicateRule(rule)} disabled={readOnly() || saving() || atRuleCap()} title={t('automation.duplicateRuleAria')} aria-label={t('automation.duplicateRuleAria')}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="9" y="9" width="12" height="12" rx="2" />
                    <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
                  </svg>
                  {t('automation.duplicate')}
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

        <Show when={state()!.rules.length > 0}>
          <div class="field-group automation-add-rule">
            <button class="card-btn card-btn-primary" onClick={addRule} disabled={readOnly() || saving() || atRuleCap()}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 5v14M5 12h14" />
              </svg>
              {t('automation.addRule')}
            </button>
            <Show when={atRuleCap()}>
              <span class="automation-cap-note">{t('automation.maxRulesReached', { count: limits().maxRules })}</span>
            </Show>
          </div>
        </Show>

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
            limits={limits()}
            saving={saving()}
            onClose={() => setEditing(null)}
            onSave={handleEditorSave}
          />
        )}
      </Show>
    </SettingsCard>
  )
}
