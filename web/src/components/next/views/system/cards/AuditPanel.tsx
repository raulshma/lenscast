import { createSignal, For, onMount, Show } from 'solid-js'
import type { AuditEntry, AuditLogResponse } from '../../../../../types'
import { clearAuditLog, getAuditLog } from '../../../../../api/client'
import { formatDateTime, t } from '../../../../../lib/i18n'
import { Panel, FieldRow, Button, Badge, Empty } from '../../../ui'

const DEFAULT_LIMIT = 50

function formatTime(timestampMs: number): string {
  return formatDateTime(timestampMs)
}

/**
 * The audit trail's dashboard surface, ported from the classic AuditCard
 * onto the kit: who did what to the camera's config — every mutating Web
 * API dispatch and every login outcome, newest first. The trail lives
 * server-side (filesDir/audit_log.json); this panel reads it, refreshes on
 * demand, and can clear it (with the classic window.confirm guard).
 */
export default function AuditPanel() {
  const [audit, setAudit] = createSignal<AuditLogResponse | null>(null)
  const [busy, setBusy] = createSignal(false)
  const [error, setError] = createSignal('')

  async function refresh() {
    if (busy()) return
    setBusy(true)
    try {
      setAudit(await getAuditLog(DEFAULT_LIMIT))
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : t('audit.loadFailed'))
    } finally {
      setBusy(false)
    }
  }

  async function clear() {
    if (busy() || !window.confirm(t('audit.clearConfirm'))) return
    setBusy(true)
    try {
      await clearAuditLog()
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('audit.clearFailed'))
    } finally {
      setBusy(false)
    }
  }

  onMount(() => {
    void refresh()
  })

  const outcomeTone = (entry: AuditEntry) =>
    entry.outcome === 'ok' ? ('success' as const) : ('danger' as const)

  return (
    <Panel title={t('audit.title')}>
      <FieldRow label={t('audit.recent')}>
        <div class="nx-deterrence">
          <Button size="sm" variant="ghost" disabled={busy()} onClick={refresh}>
            {t('audit.refresh')}
          </Button>
          <Button size="sm" variant="danger-soft" disabled={busy()} onClick={clear}>
            {t('audit.clear')}
          </Button>
        </div>
      </FieldRow>
      <Show when={error()}>
        <span class="nx-field-hint" role="alert">
          {error()}
        </span>
      </Show>
      <Show when={audit() && audit()!.entries.length > 0} fallback={<Empty title={t('audit.empty')} />}>
        <ul class="nx-clients">
          <For each={audit()!.entries}>
            {(entry) => (
              <li class="nx-clients-row">
                <div class="nx-audit-main">
                  <div class="nx-audit-line">
                    <span class="nx-audit-time nx-mono">{formatTime(entry.timestampMs)}</span>
                    {/* Both lines ellipsize; the title keeps the full text reachable. */}
                    <span class="nx-audit-action" title={entry.action}>{entry.action}</span>
                  </div>
                  <Show when={entry.detail}>
                    <span class="nx-audit-detail" title={entry.detail}>{entry.detail}</span>
                  </Show>
                </div>
                <Badge tone={outcomeTone(entry)} mono>{entry.outcome}</Badge>
              </li>
            )}
          </For>
        </ul>
        <Show when={audit() && audit()!.total > audit()!.entries.length}>
          <span class="nx-field-hint">
            {t('audit.showing', { shown: audit()!.entries.length, total: audit()!.total })}
          </span>
        </Show>
      </Show>
    </Panel>
  )
}
