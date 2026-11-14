import { createSignal, For, Show } from 'solid-js'
import { createVisiblePoll } from '../../../../../hooks/visiblePoll'
import {
  getAuthConfig,
  getAuthSessions,
  getSettings,
  login,
  revokeAuthSession,
  saveStreamingPatch,
  updateAuthConfig,
  getVapidPublicKey,
} from '../../../../../api/client'
import type { AuthSessionInfo } from '../../../../../api/client'
import { formatTime, t } from '../../../../../lib/i18n'
import { Panel, FieldRow, ToggleField, TextInput, Button, Empty } from '../../../ui'

/**
 * Remote credential rotation + session management, ported from the classic
 * AuthCard onto the kit. Talks to /api/auth/config and /api/auth/sessions;
 * an empty password keeps the stored secret, and a rotated password revokes
 * every session (this browser included — you re-login immediately with the
 * new one).
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
export default function AuthPanel() {
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
  const [pairingJson, setPairingJson] = createSignal('')
  const [tokenBusy, setTokenBusy] = createSignal(false)
  const [tokenMsg, setTokenMsg] = createSignal('')
  // The config mirror's two-phase machine per editable group (admin /
  // viewer): Synced-with-server vs Locally-dirty-unsaved. The 10 s poll
  // overwrites a group only while it is synced — the dirty bit is what
  // stops the poll from reverting mid-keystroke typing or snapping an
  // unsaved toggle back. Save clears it (the refresh that follows re-bases
  // the mirror on server truth); the password fields need no bit — they are
  // write-only drafts the poll never touches.
  const [adminDirty, setAdminDirty] = createSignal(false)
  const [viewerDirty, setViewerDirty] = createSignal(false)

  async function refresh() {
    try {
      const sessionsBody = await getAuthSessions()
      setSessions(sessionsBody.sessions ?? [])
      const config = await getAuthConfig()
      if (!adminDirty()) {
        setEnabled(config.enabled ?? false)
        setUsername(config.username ?? '')
      }
      if (!viewerDirty()) {
        setViewerEnabled(config.viewerEnabled ?? false)
        setViewerUsername(config.viewerUsername ?? '')
      }
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
    setPairingJson('')
    try {
      const token = generateApiToken()
      await saveStreamingPatch({ apiToken: token, apiTokenEnabled: true })
      // Shown exactly once — only the hash left the wire afterwards.
      setGeneratedToken(token)
      setTokenEnabled(true)
      setTokenConfigured(true)
      // The pairing bridge: a UnifiedPush receiver needs the camera base URL,
      // the token, and the VAPID key, and this is the only moment the
      // plaintext token exists client-side. A failed VAPID read must not cost
      // the token, so the JSON is best-effort. A plain-HTTP dashboard omits it
      // entirely: the pairing document is a credential bundle, and the docs
      // require the HTTPS dashboard mode before it leaves the browser.
      if (!window.isSecureContext) return
      try {
        const { publicKey } = await getVapidPublicKey()
        setPairingJson(
          JSON.stringify(
            {
              type: 'lenscast-pairing',
              version: 1,
              camera: location.host,
              baseUrl: location.origin,
              apiToken: token,
              vapidPublicKey: publicKey,
            },
            null,
            2,
          ),
        )
      } catch {
        // The token is still valid; the receiver can read the key from
        // GET /api/push/unifiedpush instead.
      }
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

  async function copyPairing() {
    try {
      await navigator.clipboard.writeText(pairingJson())
      setTokenMsg(t('auth.pairingCopied'))
    } catch {
      setTokenMsg(t('auth.pairingCopyFailed'))
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
      // The edits are server truth now: back to synced, and the refresh
      // re-bases the mirror.
      setAdminDirty(false)
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
      // The viewer group is server truth now: back to synced.
      setViewerDirty(false)
      await refresh()
    } catch (e: any) {
      setViewerMsg(e?.message || t('auth.updateFailed'))
    } finally {
      setViewerBusy(false)
    }
  }

  createVisiblePoll(refresh, 10_000)

  return (
    <Panel title={t('auth.title')} class="nx-panel-danger">
      <ToggleField
        label={t('auth.require')}
        checked={enabled()}
        onChange={() => {
          setEnabled(!enabled())
          setAdminDirty(true)
        }}
      />
      <Show when={enabled()}>
        <TextInput
          type="text"
          placeholder={t('auth.usernamePlaceholder')}
          value={username()}
          onInput={(v) => {
            setUsername(v)
            setAdminDirty(true)
          }}
          ariaLabel={t('auth.usernamePlaceholder')}
        />
        <TextInput
          type="password"
          placeholder={t('auth.passwordPlaceholder')}
          value={password()}
          onInput={setPassword}
          ariaLabel={t('auth.passwordPlaceholder')}
        />
      </Show>
      {/* The save control must survive the toggle: hiding it with the
          credentials form made "disable authentication" unsavable — the
          flip stayed local and a reload reverted it to the server's
          enabled=true. apply() sends enabled() as-is, blank password
          included, so this button also persists the OFF state. */}
      <Button size="sm" variant="primary" disabled={busy()} onClick={() => void apply()}>
        {busy() ? t('auth.saving') : t('auth.save')}
      </Button>
      <Show when={msg()}>
        <span class="nx-field-hint">{msg()}</span>
      </Show>

      {/* Viewer access (optional read-only role) */}
      <ToggleField
        label={t('auth.viewer')}
        checked={viewerEnabled()}
        onChange={() => {
          setViewerEnabled(!viewerEnabled())
          setViewerDirty(true)
        }}
      />
      <Show when={viewerEnabled()}>
        <TextInput
          type="text"
          placeholder={t('auth.viewerUsername')}
          value={viewerUsername()}
          onInput={(v) => {
            setViewerUsername(v)
            setViewerDirty(true)
          }}
          ariaLabel={t('auth.viewerUsername')}
        />
        <TextInput
          type="password"
          placeholder={t('common.unchanged')}
          value={viewerPasswordDraft()}
          onInput={setViewerPasswordDraft}
          ariaLabel={t('common.unchanged')}
        />
        <Button size="sm" variant="primary" disabled={viewerBusy()} onClick={() => void applyViewer()}>
          {viewerBusy() ? t('auth.saving') : t('auth.saveViewer')}
        </Button>
      </Show>
      <div class="nx-banner nx-banner-info" role="note">
        <span>{t('auth.viewerDesc')}</span>
      </div>
      <Show when={viewerMsg()}>
        <span class="nx-field-hint">{viewerMsg()}</span>
      </Show>

      {/* API token (read-only programmatic access) */}
      <ToggleField
        label={t('auth.token')}
        checked={tokenEnabled()}
        onChange={() => void toggleTokenEnabled()}
      />
      <FieldRow label={t('auth.configured')}>
        <span class="nx-mono nx-info-value">{tokenConfigured() ? t('auth.yes') : t('auth.no')}</span>
      </FieldRow>
      <Button size="sm" variant="primary" disabled={tokenBusy()} onClick={() => void generateToken()}>
        {tokenBusy() ? t('auth.saving') : t('auth.generate')}
      </Button>
      <Show when={generatedToken()}>
        {/* Reveal-once token: a raw input (click selects all) — the kit
            TextInput has no readonly/select-on-focus surface. */}
        <input
          id="api-token-reveal"
          type="text"
          class="nx-input nx-mono"
          readonly
          value={generatedToken()}
          onClick={(e) => e.currentTarget.select()}
          aria-label={t('auth.generate')}
        />
        <div class="nx-deterrence">
          <Button size="sm" variant="ghost" onClick={() => void copyToken()}>
            {t('auth.copy')}
          </Button>
        </div>
        <div class="nx-banner nx-banner-info" role="note">
          <span>{t('auth.tokenWarning')}</span>
        </div>
        <Show when={!window.isSecureContext}>
          <div class="nx-banner nx-banner-warning" role="alert">
            <span>{t('auth.pairingInsecure')}</span>
          </div>
        </Show>
        <Show when={pairingJson()}>
          <span class="nx-subhead">{t('auth.pairing')}</span>
          <textarea
            class="nx-textarea"
            readonly
            rows={8}
            value={pairingJson()}
            onClick={(e) => e.currentTarget.select()}
            aria-label={t('auth.pairing')}
          />
          <div class="nx-deterrence">
            <Button size="sm" variant="ghost" onClick={() => void copyPairing()}>
              {t('auth.copyPairing')}
            </Button>
          </div>
          <div class="nx-banner nx-banner-info" role="note">
            <span>{t('auth.pairingHint')}</span>
          </div>
        </Show>
      </Show>
      <Show when={tokenMsg()}>
        <span class="nx-field-hint">{tokenMsg()}</span>
      </Show>

      <FieldRow label={t('auth.sessions', { count: sessions().length })} />
      <Show when={sessions().length === 0} fallback={
        <ul class="nx-clients">
          <For each={sessions()}>
            {(session) => (
              <li class="nx-clients-row">
                <span class="nx-clients-id nx-mono" title={session.tokenPrefix}>
                  {t('auth.sessionPrefix', { prefix: session.tokenPrefix })}
                  {session.role === 'viewer' ? ` — ${t('auth.viewerRole')}` : ''}
                  {` — ${t('auth.expires', { time: formatTime(session.expiresAtMs, { hour: '2-digit', minute: '2-digit', second: '2-digit' }) })}`}
                </span>
                <Button
                  size="sm"
                  variant="danger-soft"
                  onClick={() => void revoke(session.tokenPrefix)}
                  ariaLabel={t('auth.revoke')}
                >
                  {t('auth.revoke')}
                </Button>
              </li>
            )}
          </For>
        </ul>
      }>
        <Empty title={t('auth.noSessions')} />
      </Show>
    </Panel>
  )
}
