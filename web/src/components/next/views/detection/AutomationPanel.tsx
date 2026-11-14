import { createSignal, For, Show } from 'solid-js'
import type { AutomationRuleDto, AutomationStateDto } from '../../../../types'
import { getAutomation, putAutomation } from '../../../../api/client'
import { formatDate, t } from '../../../../lib/i18n'
import { createVisiblePoll } from '../../../../hooks/visiblePoll'
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
} from '../../../../lib/automation'
import { AutomationBoltGlyph, AutomationClockGlyph, AutomationIcon } from '../../../../lib/automationIcons'
import ToggleField from '../../ui/ToggleField'
import Button from '../../ui/Button'
import Empty from '../../ui/Empty'
import Led from '../../ui/Led'
import Panel from '../../ui/Panel'
import AutomationRuleEditor from '../../../AutomationRuleEditor'

interface Props {
  /** True for a viewer-role session: the panel renders read-only. */
  readOnly?: () => boolean
}

/**
 * The automation engine, ported from the classic AutomationCard onto the
 * kit where it pays (chrome, master toggle, banners, rule buttons). The
 * rule rows and the catalog-driven editor keep their DOM — their styling
 * flows through the token bridge — because that sub-UI is its own world.
 * Fetches its own document, refetches on a 60s visibility-gated poll, and
 * every mutation PUTs the whole { enabled, rules } document.
 */
export default function AutomationPanel(props: Props) {
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

  /** Days label over a mask: "Every day" or the localized day list. */
  const daysText = (mask: number): string =>
    isEveryDayMask(mask) ? t('automation.daysEveryDay') : dayListLabel(mask, t)

  /** The localized and/or joiner between a rule's conditions. */
  const conditionJoiner = (rule: AutomationRuleDto): string =>
    rule.conditionMode === 'any' ? t('automation.joinOr') : t('automation.joinAnd')

  /** The WHEN row text for event rules: the localized trigger summary. */
  const whenText = (rule: AutomationRuleDto): string =>
    joinSummaryLines(automationTriggerSummaryLines(rule), t)

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
    <Panel
      title={t('automation.title')}
      badge={<Led tone={state()?.enabled ? 'ok' : 'off'} />}
    >
      <Show when={!state() && !loadError()}>
        <div class="nx-banner nx-banner-neutral" role="note">{t('automation.loading')}</div>
      </Show>

      <Show when={loadError()}>
        <div class="nx-banner nx-banner-danger" role="alert">{loadError()}</div>
      </Show>

      <Show when={state()}>
        <Show when={readOnly()}>
          <div class="nx-banner nx-banner-neutral" role="note" aria-live="polite">
            {t('automation.readOnlyBanner')}
          </div>
        </Show>

        <ToggleField
          label={t('automation.masterEnabled')}
          checked={state()!.enabled}
          disabled={readOnly() || saving()}
          onChange={toggleMaster}
        />
        {/* Rules stay visible (and editable) while the engine is off — a
            banner explains they will not fire until it is on. */}
        <Show when={!state()!.enabled}>
          <div class="nx-banner nx-banner-warning" role="note" aria-live="polite">
            {t('automation.engineOffBanner')}
          </div>
        </Show>
        <Show when={state()!.rules.length > 0}>
          <span class="nx-field-hint">
            {t('automation.rulesCount', { count: state()!.rules.length, total: limits().maxRules })}
          </span>
        </Show>

        {/* The empty state: what rules do, plus the first CTA — the add
            button below stays for the non-empty list. */}
        <Show when={state()!.rules.length === 0}>
          <Empty title={t('automation.emptyTitle')} hint={t('automation.emptyBody')} icon={<AutomationBoltGlyph />} />
          <Button
            variant="primary"
            size="sm"
            onClick={addRule}
            disabled={readOnly() || saving() || atRuleCap()}
            ariaLabel={t('automation.addRule')}
          >
            {t('automation.addRule')}
          </Button>
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
                    {/* Time rules show the time (mono) and the days as two
                        separate facts; event rules show their localized
                        trigger label. */}
                    <span class="automation-rule-sub">
                      <Show
                        when={rule.triggerType === 'time_of_day'}
                        fallback={t(automationTriggerLabelKey(rule.triggerType))}
                      >
                        <span class="nx-rule-time nx-mono">{formatMinuteOfDay(rule.minuteOfDay)}</span>
                        <span>{daysText(rule.daysMask)}</span>
                      </Show>
                    </span>
                  </span>
                </button>
                <label class="nx-switch" for={`automation-rule-toggle-${rule.id}`}>
                  <input
                    id={`automation-rule-toggle-${rule.id}`}
                    type="checkbox"
                    checked={rule.enabled}
                    disabled={readOnly() || saving()}
                    onChange={() => setRuleEnabled(rule, !rule.enabled)}
                  />
                  <span class="nx-switch-track" aria-hidden="true" />
                  <span class="nx-switch-thumb" aria-hidden="true" />
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
                    <span class="automation-when-text">
                      <For each={rule.conditions}>
                        {(condition, index) => (
                          <>
                            <Show when={index() > 0}>
                              <span class="nx-rule-joiner">{conditionJoiner(rule)} </span>
                            </Show>
                            <span class="nx-rule-condition">
                              {automationConditionText(condition)}
                              <Show when={condition.type === 'time_window' && !isEveryDayMask(conditionDaysMask(condition))}>
                                <span class="nx-rule-days"> — {daysText(conditionDaysMask(condition))}</span>
                              </Show>
                            </span>
                          </>
                        )}
                      </For>
                    </span>
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
                <Button size="sm" variant="ghost" onClick={() => editRule(rule)} disabled={readOnly()} title={t('automation.editRuleAria')} ariaLabel={t('automation.editRuleAria')}>
                  {t('automation.edit')}
                </Button>
                <Button size="sm" variant="ghost" onClick={() => duplicateRule(rule)} disabled={readOnly() || saving() || atRuleCap()} title={t('automation.duplicateRuleAria')} ariaLabel={t('automation.duplicateRuleAria')}>
                  {t('automation.duplicate')}
                </Button>
                <Button size="sm" variant="danger-soft" onClick={() => removeRule(rule)} disabled={readOnly() || saving()} title={t('automation.deleteRuleAria')} ariaLabel={t('automation.deleteRuleAria')}>
                  {t('common.delete')}
                </Button>
              </div>
            </div>
          )}
        </For>

        <Show when={state()!.rules.length > 0}>
          <div class="nx-automation-add">
            <Button variant="primary" size="sm" onClick={addRule} disabled={readOnly() || saving() || atRuleCap()} ariaLabel={t('automation.addRule')}>
              {t('automation.addRule')}
            </Button>
            <Show when={atRuleCap()}>
              <span class="nx-field-hint">{t('automation.maxRulesReached', { count: limits().maxRules })}</span>
            </Show>
          </div>
        </Show>

        <Show when={saving()}>
          <div class="nx-banner nx-banner-neutral" role="note">{t('automation.saving')}</div>
        </Show>
        <Show when={saveError()}>
          <div class="nx-banner nx-banner-danger" role="alert">{saveError()}</div>
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
    </Panel>
  )
}
