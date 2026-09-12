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
import { formatTime, t } from '../lib/i18n'

/**
 * Remote credential rotation + session management. Talks to
 * /api/auth/config and /api/auth/sessions; an empty password keeps the
 * stored secret, and a rotated password revokes every session (this
 * browser included — you re-login immediately with the new one).
 *
 * Also the optional viewer-access section: a second credential pair whose
 * sessions are read-only (enforced server-side by RoleGatePolicy). The
 * viewer password field is write-only — "(unchanged)" keeps the stored
 * hash — and a viewer change revokes only viewer sessions.
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
  const [viewerEnabled, setViewerEnabled] = createSignal(false)
  const [viewerUsername, setViewerUsername] = createSignal('')
  // Write-only draft, exactly like the MQTT password field: the server keeps
  // the stored hash until a non-empty value is sent, so the field binds to a
  // local draft only and never to the secret.
  const [viewerPasswordDraft, setViewerPasswordDraft] = createSignal('')
  const [sessions, setSessions] = createSignal<AuthSessionInfo[]>([])
  const [msg, setMsg] = createSignal('')
  const [busy, setBusy] = createSignal(false)
  const [viewerBusy, setViewerBusy] = createSignal(false)
  const [viewerMsg, setViewerMsg] = createSignal('')
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
      setViewerEnabled(config.viewerEnabled ?? false)
      setViewerUsername(config.viewerUsername ?? '')
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
      setTokenMsg(e?.message || t('auth.tokenFailed'))
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
      setTokenMsg(e?.message || t('auth.tokenFailed'))
    } finally {
      setTokenBusy(false)
    }
  }

  async function copyToken() {
    try {
      await navigator.clipboard.writeText(generatedToken())
      setTokenMsg(t('auth.copied'))
    } catch {
      setTokenMsg(t('auth.copyFailed'))
    }
  }

  async function apply() {
    if (busy()) return
    setBusy(true)
    setMsg('')
    const enteredPassword = password()
    try {
      await updateAuthConfig({ enabled: enabled(), username: username(), password: enteredPassword })
      setMsg(t('auth.updated'))
      setPassword('')
      if (enteredPassword) {
        // Password rotation revoked all sessions, including this one.
        await login(username(), enteredPassword).catch(() => {})
      }
      await refresh()
    } catch (e: any) {
      setMsg(e?.message || t('auth.updateFailed'))
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

  // The viewer save always carries the admin section too (the PUT replaces
  // the whole config): the admin password stays empty (= unchanged), and the
  // admin fields are whatever the card currently shows. A viewer change —
  // username edit, password set/rotate, enable/disable — revokes only the
  // viewer sessions, so this admin session survives and no re-login is owed.
  async function applyViewer() {
    if (viewerBusy()) return
    setViewerBusy(true)
    setViewerMsg('')
    const enteredPassword = viewerPasswordDraft()
    try {
      await updateAuthConfig({
        enabled: enabled(),
        username: username(),
        password: '',
        viewerEnabled: viewerEnabled(),
        viewerUsername: viewerUsername(),
        viewerPassword: enteredPassword,
      })
      setViewerMsg(t('auth.viewerUpdated'))
      setViewerPasswordDraft('')
      await refresh()
    } catch (e: any) {
      setViewerMsg(e?.message || t('auth.updateFailed'))
    } finally {
      setViewerBusy(false)
    }
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
      title={t('auth.title')}
    >
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('auth.require')}</span>
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
            placeholder={t('auth.usernamePlaceholder')}
            autocomplete="off"
            value={username()}
            onInput={(e) => setUsername(e.currentTarget.value)}
          />
          <input
            id="auth-password"
            type="password"
            class="field-input field-input-full"
            placeholder={t('auth.passwordPlaceholder')}
            autocomplete="new-password"
            value={password()}
            onInput={(e) => setPassword(e.currentTarget.value)}
          />
          <button type="button" class="action-btn action-btn-primary" disabled={busy()} onClick={() => void apply()}>
            <span>{busy() ? t('auth.saving') : t('auth.save')}</span>
          </button>
          <Show when={msg()}>
            <span class="clients-cap-row">{msg()}</span>
          </Show>
        </Show>
      </div>

      {/* Viewer access (optional read-only role) */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('auth.viewer')}</span>
          <label class="toggle-switch" for="viewer-enabled-toggle">
            <input
              id="viewer-enabled-toggle"
              type="checkbox"
              checked={viewerEnabled()}
              onChange={() => setViewerEnabled(!viewerEnabled())}
            />
            <span class="toggle-slider" />
          </label>
        </div>
        <Show when={viewerEnabled()}>
          <input
            id="viewer-username"
            type="text"
            class="field-input field-input-full"
            placeholder={t('auth.viewerUsername')}
            autocomplete="off"
            value={viewerUsername()}
            onInput={(e) => setViewerUsername(e.currentTarget.value)}
          />
          <input
            id="viewer-password"
            type="password"
            class="field-input field-input-full"
            placeholder={t('common.unchanged')}
            autocomplete="new-password"
            value={viewerPasswordDraft()}
            onInput={(e) => setViewerPasswordDraft(e.currentTarget.value)}
          />
          <button type="button" class="action-btn action-btn-primary" disabled={viewerBusy()} onClick={() => void applyViewer()}>
            <span>{viewerBusy() ? t('auth.saving') : t('auth.saveViewer')}</span>
          </button>
        </Show>
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('auth.viewerDesc')}</span>
        </div>
        <Show when={viewerMsg()}>
          <span class="clients-cap-row">{viewerMsg()}</span>
        </Show>
      </div>

      {/* API token (read-only programmatic access) */}
      <div class="field-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('auth.token')}</span>
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
          <span class="field-label">{t('auth.configured')}</span>
          <span class="field-value">{tokenConfigured() ? t('auth.yes') : t('auth.no')}</span>
        </div>
        <button type="button" class="action-btn action-btn-primary" disabled={tokenBusy()} onClick={() => void generateToken()}>
          <span>{tokenBusy() ? t('auth.saving') : t('auth.generate')}</span>
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
              <span>{t('auth.copy')}</span>
            </button>
          </div>
          <div class="status-banner status-banner-info stream-mode-hint" role="note">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{t('auth.tokenWarning')}</span>
          </div>
        </Show>
        <Show when={tokenMsg()}>
          <span class="clients-cap-row">{tokenMsg()}</span>
        </Show>
      </div>

      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('auth.sessions', { count: sessions().length })}</span>
        </div>
        <ul class="clients-list">
          <For each={sessions()} fallback={<li class="clients-empty">{t('auth.noSessions')}</li>}>
            {(session) => (
              <li class="client-row">
                <span class="client-id" title={session.tokenPrefix}>
                  {t('auth.sessionPrefix', { prefix: session.tokenPrefix })}{session.role === 'viewer' ? t('auth.viewerRole') : ''} · {t('auth.expires', { time: formatTime(session.expiresAtMs, { hour: '2-digit', minute: '2-digit', second: '2-digit' }) })}
                </span>
                <button type="button" class="client-kick-btn" onClick={() => void revoke(session.tokenPrefix)}>
                  {t('auth.revoke')}
                </button>
              </li>
            )}
          </For>
        </ul>
      </div>
    </SettingsCard>
  )
}
