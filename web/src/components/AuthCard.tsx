import { createSignal, For, Show } from 'solid-js'
import { createVisiblePoll } from '../hooks/visiblePoll'
import SettingsCard from './SettingsCard'
import {
  getAuthConfig,
  getAuthSessions,
  getSettings,
  login,
  revokeAuthSession,
  saveStreamingPatch,
  updateAuthConfig,
} from '../api/client'
import type { AuthSessionInfo } from '../api/client'

/**
 * Remote credential rotation + session management. Talks to
 * /api/auth/config and /api/auth/sessions; an empty password keeps the
 * stored secret, and a rotated password revokes every session (this
 * browser included — you re-login immediately with the new one).
 *
 * Also the API-token surface: Generate mints a random token client-side
 * (crypto.getRandomValues, 32 bytes, base64url), PUTs it once as the
 * write-only `apiToken` field, and shows it exactly once — the server keeps
 * only its SHA-256 hash. The token grants read-only GET/HEAD access for
 * programmatic clients (Home Assistant, curl) via `Authorization: Bearer`
 * or `X-Api-Token`.
 */
export default function AuthCard() {
  const [enabled, setEnabled] = createSignal(false)
  const [username, setUsername] = createSignal('')
  const [password, setPassword] = createSignal('')
  const [sessions, setSessions] = createSignal<AuthSessionInfo[]>([])
  const [msg, setMsg] = createSignal('')
  const [busy, setBusy] = createSignal(false)
  const [tokenEnabled, setTokenEnabled] = createSignal(false)
  const [tokenConfigured, setTokenConfigured] = createSignal(false)
  const [generatedToken, setGeneratedToken] = createSignal('')
  const [tokenBusy, setTokenBusy] = createSignal(false)
  const [tokenMsg, setTokenMsg] = createSignal('')

  async function refresh() {
    try {
      const sessionsBody = await getAuthSessions()
      setSessions(sessionsBody.sessions ?? [])
      const config = await getAuthConfig()
      setEnabled(config.enabled ?? false)
      setUsername(config.username ?? '')
      const settings = await getSettings()
      setTokenEnabled(settings.streaming?.apiTokenEnabled ?? false)
      setTokenConfigured(settings.streaming?.apiTokenConfigured ?? false)
    } catch {
      // Auth endpoints may themselves 401 when auth is required but session
      // expired — the global auth flow handles that case.
    }
  }

  function generateApiToken(): string {
    const bytes = new Uint8Array(32)
    crypto.getRandomValues(bytes)
    let binary = ''
    bytes.forEach((b) => {
      binary += String.fromCharCode(b)
    })
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  }

  async function generateToken() {
    if (tokenBusy()) return
    setTokenBusy(true)
    setTokenMsg('')
    setGeneratedToken('')
    try {
      const token = generateApiToken()
      await saveStreamingPatch({ apiToken: token, apiTokenEnabled: true })
      // Shown exactly once — only the hash left the wire afterwards.
      setGeneratedToken(token)
      setTokenEnabled(true)
      setTokenConfigured(true)
    } catch (e: any) {
      setTokenMsg(e?.message || 'Token update failed')
    } finally {
      setTokenBusy(false)
    }
  }

  async function toggleTokenEnabled() {
    if (tokenBusy()) return
    setTokenBusy(true)
    setTokenMsg('')
    const next = !tokenEnabled()
    try {
      await saveStreamingPatch({ apiTokenEnabled: next })
      setTokenEnabled(next)
    } catch (e: any) {
      setTokenMsg(e?.message || 'Token update failed')
    } finally {
      setTokenBusy(false)
    }
  }

  async function copyToken() {
    try {
      await navigator.clipboard.writeText(generatedToken())
      setTokenMsg('Copied to clipboard')
    } catch {
      setTokenMsg('Copy failed — select the text manually')
    }
  }

  async function apply() {
    if (busy()) return
    setBusy(true)
    setMsg('')
    const enteredPassword = password()
    try {
      await updateAuthConfig({ enabled: enabled(), username: username(), password: enteredPassword })
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
      await revokeAuthSession(prefix)
      await refresh()
    } catch { }
  }

  createVisiblePoll(refresh, 10_000)

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

      {/* API token (read-only programmatic access) */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">API Token</span>
          <label class="toggle-switch" for="api-token-toggle">
            <input
              id="api-token-toggle"
              type="checkbox"
              checked={tokenEnabled()}
              onChange={() => void toggleTokenEnabled()}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <div class="field-row">
          <span class="field-label">Configured</span>
          <span class="field-value">{tokenConfigured() ? 'Yes' : 'No'}</span>
        </div>
        <button type="button" class="action-btn action-btn-primary" disabled={tokenBusy()} onClick={() => void generateToken()}>
          <span>{tokenBusy() ? 'Saving…' : 'Generate new token'}</span>
        </button>
        <Show when={generatedToken()}>
          <input
            id="api-token-reveal"
            type="text"
            class="field-input field-input-full"
            readonly
            value={generatedToken()}
            onClick={(e) => e.currentTarget.select()}
          />
          <div class="deterrence-row">
            <button type="button" class="action-btn action-btn-ghost" onClick={() => void copyToken()}>
              <span>Copy</span>
            </button>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>Copy it now — it is shown only once. It grants read-only GET access (never /api/auth/*) for scripts via Authorization: Bearer or X-Api-Token.</span>
          </div>
        </Show>
        <Show when={tokenMsg()}>
          <span class="clients-cap-row">{tokenMsg()}</span>
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
