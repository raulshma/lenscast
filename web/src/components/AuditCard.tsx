import { createSignal, For, onMount, Show } from 'solid-js'
import type { AuditEntry, AuditLogResponse } from '../types'
import { clearAuditLog, getAuditLog } from '../api/client'
import SettingsCard from './SettingsCard'
import { formatDateTime, t } from '../lib/i18n'

const DEFAULT_LIMIT = 50

function formatTime(timestampMs: number): string {
  return formatDateTime(timestampMs)
}

/**
 * The audit trail's dashboard surface: who did what to the camera's config —
 * every mutating Web API dispatch and every login outcome, newest first. The
 * trail lives server-side (filesDir/audit_log.json); this card reads it,
 * refreshes on demand, and can clear it.
 */
export default function AuditCard() {
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

  const outcomeBadge = (entry: AuditEntry) =>
    entry.outcome === 'ok' ? 'audit-badge-ok' : 'audit-badge-error'

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M9 12h6M9 16h6M13 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V9l-6-6z" />
          <path d="M13 3v6h6" />
        </svg>
      }
      title={t('audit.title')}
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('audit.recent')}</span>
          <div class="motion-zone-actions">
            <button type="button" class="action-btn action-btn-ghost" disabled={busy()} onClick={refresh}>
              {t('audit.refresh')}
            </button>
            <button type="button" class="client-kick-btn" disabled={busy()} onClick={clear}>
              {t('audit.clear')}
            </button>
          </div>
        </div>
        <Show when={error()}>
          <span class="clients-cap-row" role="alert">
            {error()}
          </span>
        </Show>
        <Show
          when={audit() && audit()!.entries.length > 0}
          fallback={
            <div class="status-banner status-banner-info stream-mode-hint" role="note">
              <span class="status-banner-dot" aria-hidden="true" />
              <span>{t('audit.empty')}</span>
            </div>
          }
        >
          <div class="audit-list">
            <For each={audit()!.entries}>
              {(entry) => (
                <div class="client-row">
                  <span class="client-id" title={entry.detail || entry.action}>
                    {formatTime(entry.timestampMs)} · {entry.action}
                    {entry.detail ? ` · ${entry.detail}` : ''}
                  </span>
                  <span class={`audit-badge ${outcomeBadge(entry)}`}>{entry.outcome}</span>
                </div>
              )}
            </For>
          </div>
          <Show when={audit() && audit()!.total > audit()!.entries.length}>
            <span class="clients-cap-row">
              {t('audit.showing', { shown: audit()!.entries.length, total: audit()!.total })}
            </span>
          </Show>
        </Show>
      </div>
    </SettingsCard>
  )
}
