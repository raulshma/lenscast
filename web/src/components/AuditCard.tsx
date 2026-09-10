import { createSignal, For, onMount, Show } from 'solid-js'
import type { AuditEntry, AuditLogResponse } from '../types'
import { clearAuditLog, getAuditLog } from '../api/client'
import SettingsCard from './SettingsCard'

const DEFAULT_LIMIT = 50

function formatTime(timestampMs: number): string {
  return new Date(timestampMs).toLocaleString()
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
      setError(e instanceof Error ? e.message : 'Failed to load audit log')
    } finally {
      setBusy(false)
    }
  }

  async function clear() {
    if (busy() || !window.confirm('Clear the audit log?')) return
    setBusy(true)
    try {
      await clearAuditLog()
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to clear audit log')
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
      title="Audit Log"
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Recent Activity</span>
          <div class="motion-zone-actions">
            <button type="button" class="action-btn action-btn-ghost" disabled={busy()} onClick={refresh}>
              Refresh
            </button>
            <button type="button" class="client-kick-btn" disabled={busy()} onClick={clear}>
              Clear
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
              <span>No audited activity yet — config changes and logins will appear here.</span>
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
              Showing {audit()!.entries.length} of {audit()!.total} entries
            </span>
          </Show>
        </Show>
      </div>
    </SettingsCard>
  )
}
