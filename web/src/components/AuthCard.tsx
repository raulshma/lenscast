import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import { getAuthStatus, getSessionStatus, login } from '../api/client'

interface SessionInfo {
  tokenPrefix: string
  expiresAtMs: number
}

/**
 * Remote credential rotation + session management. Talks to
 * /api/auth/config and /api/auth/sessions; an empty password keeps the
 * stored secret, and a rotated password revokes every session (this
 * browser included — you re-login immediately with the new one).
 */
export default function AuthCard() {
  const [enabled, setEnabled] = createSignal(false)
  const [username, setUsername] = createSignal('')
  const [password, setPassword] = createSignal('')
  const [sessions, setSessions] = createSignal<SessionInfo[]>([])
  const [msg, setMsg] = createSignal('')
  const [busy, setBusy] = createSignal(false)

  async function jsonFetch(input: string, init: RequestInit = {}): Promise<any> {
    const response = await fetch(input, {
      credentials: 'same-origin',
      ...init,
      headers: { 'X-Requested-With': 'XMLHttpRequest', ...(init.headers ?? {}) },
    })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(body?.error || `HTTP ${response.status}`)
    return body
  }

  async function refresh() {
    if (typeof document !== 'undefined' && document.hidden) return
    try {
      const sessionsBody = await jsonFetch('/api/auth/sessions')
      setSessions(sessionsBody.sessions ?? [])
      const config = await jsonFetch('/api/auth/config')
      setEnabled(config.enabled ?? false)
      setUsername(config.username ?? '')
    } catch {
      // Auth endpoints may themselves 401 when auth is required but session
      // expired — the global auth flow handles that case.
    }
  }

  async function apply() {
    if (busy()) return
    setBusy(true)
    setMsg('')
    const enteredPassword = password()
    try {
      await jsonFetch('/api/auth/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: enabled(), username: username(), password: enteredPassword }),
      })
      setMsg('Credentials updated')
      setPassword('')
      if (enteredPassword) {
        // Password rotation revoked all sessions, including this one.
        await login(username(), enteredPassword).catch(() => {})
      }
      await refresh()
    } catch (e: any) {
      setMsg(e?.message || 'Update failed')
    } finally {
      setBusy(false)
    }
  }

  async function revoke(prefix: string) {
    try {
      await jsonFetch(`/api/auth/sessions/${encodeURIComponent(prefix)}`, { method: 'DELETE' })
      await refresh()
    } catch { }
  }

  onMount(() => {
    void refresh()
    const timer = setInterval(refresh, 10_000)
    onCleanup(() => clearInterval(timer))
  })

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          <path d="M9 12l2 2 4-4" />
        </svg>
      }
      title="Access & Sessions"
    >
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">Require Authentication</span>
          <label class="toggle-switch" for="auth-enabled-toggle">
            <input
              id="auth-enabled-toggle"
              type="checkbox"
              checked={enabled()}
              onChange={() => setEnabled(!enabled())}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={enabled()}>
          <input
            id="auth-username"
            type="text"
            class="field-input field-input-full"
            placeholder="Username"
            autocomplete="off"
            value={username()}
            onInput={(e) => setUsername(e.currentTarget.value)}
          />
          <input
            id="auth-password"
            type="password"
            class="field-input field-input-full"
            placeholder="New password (blank = unchanged)"
            autocomplete="new-password"
            value={password()}
            onInput={(e) => setPassword(e.currentTarget.value)}
          />
          <button type="button" class="action-btn action-btn-primary" disabled={busy()} onClick={() => void apply()}>
            <span>{busy() ? 'Saving…' : 'Save credentials'}</span>
          </button>
          <Show when={msg()}>
            <span class="clients-cap-row">{msg()}</span>
          </Show>
        </Show>
      </div>

      <div class="field-group">
        <div class="field-row">
          <span class="field-label">Active Sessions ({sessions().length})</span>
        </div>
        <ul class="clients-list">
          <For each={sessions()} fallback={<li class="clients-empty">No active sessions</li>}>
            {(session) => (
              <li class="client-row">
                <span class="client-id" title={session.tokenPrefix}>
                  session {session.tokenPrefix}… · expires {new Date(session.expiresAtMs).toLocaleTimeString()}
                </span>
                <button type="button" class="client-kick-btn" onClick={() => void revoke(session.tokenPrefix)}>
                  Revoke
                </button>
              </li>
            )}
          </For>
        </ul>
      </div>
    </SettingsCard>
  )
}
