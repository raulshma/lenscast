import { createEffect, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import {
  type CameraCredentials,
  type SavedCamera,
  type TileMode,
  authHeaders,
  captureRequest,
  mediaUrl,
  nextGlobalMode,
  normalizeMode,
  parseCameras,
  tileRefreshMs,
  withMode,
} from '../lib/multicamStorage'
import { t, tCount } from '../lib/i18n'

/**
 * Known limitation: browsers cannot browse mDNS (.local) names, so remote
 * camera URLs are manual entries — e.g. http://192.168.1.55:8080 (the URL
 * shown on each phone's Connect sheet).
 */

// Remote LensCast routes (server-side: StreamingServer.kt). Plain <img> needs
// no CORS, but both routes sit behind the camera's auth when enabled —
// credential-carrying tiles fetch their frames instead (see CameraTile).
const SNAPSHOT_PATH = '/snapshot' // image/jpeg, latest MJPEG frame
const MJPEG_PATH = '/stream' // multipart/x-mixed-replace, rendered natively by <img>

const STORAGE_KEY = 'lenscast.multicam.cameras'

type TileStatus = 'loading' | 'online' | 'offline'
/** Per-tile outcome of the last "Capture all" volley. */
type CaptureStatus = 'sending' | 'sent' | 'failed'

/** The snapshot auto-refresh choices, in seconds (the 5 s rung notes "default"). */
const REFRESH_CHOICES = [2000, 5000, 10000, 30000]

function makeId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `cam-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function loadCameras(): SavedCamera[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    return parseCameras(JSON.parse(raw))
  } catch {
    return []
  }
}

function persistCameras(cameras: SavedCamera[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(cameras))
  } catch {
    // Private-mode storage failures keep the in-memory list working.
  }
}

/** Validated origin (http/https only), or null when the URL is unusable. */
function normalizeBaseUrl(raw: string): string | null {
  try {
    const url = new URL(raw.trim())
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
    return url.origin
  } catch {
    return null
  }
}

function hostLabel(baseUrl: string): string {
  try {
    return new URL(baseUrl).host
  } catch {
    return baseUrl
  }
}

/**
 * One camera tile: name/status badge over an auto-refreshing snapshot (or
 * the live MJPEG stream), with the refresh period configurable per tile.
 * Credential-free tiles render a plain <img> (no CORS involved — works
 * against today's servers); tiles with credentials fetch their frames with
 * auth headers and render the blob, because <img> tags cannot send headers.
 * A credential-holding MJPEG stream never completes, so those tiles are
 * snapshot-only. Errors mark the tile offline and hint at auth.
 */
function CameraTile(props: {
  camera: SavedCamera
  onRemove: (id: string) => void
  onUpdate: (id: string, patch: Partial<Pick<SavedCamera, 'name' | 'baseUrl' | 'mode' | 'auth' | 'refreshMs'>>) => void
  captureStatus: () => CaptureStatus | undefined
}) {
  // Tiles with credentials are snapshot-only (see the component comment).
  const [mode, setMode] = createSignal<TileMode>(props.camera.auth ? 'snapshot' : normalizeMode(props.camera.mode))
  const [liveBroken, setLiveBroken] = createSignal(false)
  const [status, setStatus] = createSignal<TileStatus>('loading')
  const [editing, setEditing] = createSignal(false)
  const [editName, setEditName] = createSignal(props.camera.name)
  const [editUrl, setEditUrl] = createSignal(props.camera.baseUrl)
  const [editAuthKind, setEditAuthKind] = createSignal<'none' | 'token' | 'basic'>(props.camera.auth?.kind ?? 'none')
  const [editToken, setEditToken] = createSignal(props.camera.auth?.kind === 'token' ? props.camera.auth.token : '')
  const [editUser, setEditUser] = createSignal(props.camera.auth?.kind === 'basic' ? props.camera.auth.username : '')
  const [editPass, setEditPass] = createSignal(props.camera.auth?.kind === 'basic' ? props.camera.auth.password : '')
  const [editRefreshMs, setEditRefreshMs] = createSignal(tileRefreshMs(props.camera))
  const [editError, setEditError] = createSignal('')

  // Credential-free snapshot rung: the plain <img> cache-buster tick.
  const [tick, setTick] = createSignal(0)
  // The credential-carrying rung's rendered frame (an object URL).
  const [frameUrl, setFrameUrl] = createSignal<string | null>(null)

  // The tile's own auto-refresh, at its configured period — only the
  // snapshot rung needs a tick. Paused like every poll lane while hidden.
  createEffect(() => {
    if (mode() !== 'snapshot') return
    const period = tileRefreshMs(props.camera)
    const timer = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return
      setTick((t) => t + 1)
    }, period)
    onCleanup(() => clearInterval(timer))
  })

  // Follow persisted-mode changes arriving from outside the tile ("Live
  // all"); an effect with the previous value keeps a transient snapshot
  // fallback (liveBroken) from being clobbered by unrelated re-renders.
  // Credential tiles always resolve to snapshots.
  createEffect((prev: TileMode | undefined) => {
    const persisted: TileMode = props.camera.auth ? 'snapshot' : normalizeMode(props.camera.mode)
    if (prev !== undefined && persisted !== prev) {
      setMode(persisted)
      setLiveBroken(false)
    }
    return persisted
  })

  // The credential-carrying rung: fetch the snapshot with auth headers and
  // render the blob. On failure the frame is dropped and the plain <img>
  // rung gets a try (the remote may have auth off after all).
  createEffect(() => {
    const auth = props.camera.auth
    if (!auth || mode() !== 'snapshot') return
    const path = `${SNAPSHOT_PATH}?t=${tick()}`
    setStatus('loading')
    void (async () => {
      try {
        const res = await fetch(mediaUrl(props.camera.baseUrl, path, props.camera), {
          headers: authHeaders(auth),
          mode: 'cors',
        })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const blob = await res.blob()
        const next = URL.createObjectURL(blob)
        setFrameUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev)
          return next
        })
        setStatus('online')
      } catch {
        setFrameUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev)
          return null
        })
      }
    })()
  })
  onCleanup(() => {
    setFrameUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev)
      return null
    })
  })

  const imageUrl = () => frameUrl() ?? (mode() === 'live'
    ? mediaUrl(props.camera.baseUrl, MJPEG_PATH, props.camera)
    : mediaUrl(props.camera.baseUrl, `${SNAPSHOT_PATH}?t=${tick()}`, props.camera))

  function startEdit() {
    setEditName(props.camera.name)
    setEditUrl(props.camera.baseUrl)
    setEditAuthKind(props.camera.auth?.kind ?? 'none')
    setEditToken(props.camera.auth?.kind === 'token' ? props.camera.auth.token : '')
    setEditUser(props.camera.auth?.kind === 'basic' ? props.camera.auth.username : '')
    setEditPass(props.camera.auth?.kind === 'basic' ? props.camera.auth.password : '')
    setEditRefreshMs(tileRefreshMs(props.camera))
    setEditError('')
    setEditing(true)
  }

  function editedCredentials(): CameraCredentials | undefined {
    if (editAuthKind() === 'token') {
      const token = editToken().trim()
      return token ? { kind: 'token', token } : undefined
    }
    if (editAuthKind() === 'basic') {
      const username = editUser().trim()
      return username ? { kind: 'basic', username, password: editPass() } : undefined
    }
    return undefined
  }

  function saveEdit() {
    const normalized = normalizeBaseUrl(editUrl())
    if (!normalized) {
      setEditError(t('multicam.invalidUrl'))
      return
    }
    props.onUpdate(props.camera.id, {
      name: editName().trim() || hostLabel(normalized),
      baseUrl: normalized,
      auth: editedCredentials(),
      refreshMs: editRefreshMs(),
    })
    setEditing(false)
  }

  function toggleMode() {
    if (props.camera.auth) return // snapshot-only, see above
    const next: TileMode = mode() === 'snapshot' ? 'live' : 'snapshot'
    setStatus('loading')
    setLiveBroken(false)
    setFrameUrl(null)
    setMode(next)
    props.onUpdate(props.camera.id, { mode: next })
  }

  const captureLabel = () => {
    switch (props.captureStatus()) {
      case 'sending': return t('multicam.capturing')
      case 'sent': return t('multicam.capture.sent')
      case 'failed': return t('multicam.capture.failed')
      default: return null
    }
  }

  return (
    <div class="multicam-tile" classList={{ 'multicam-tile-offline': status() === 'offline' }}>
      <div class="multicam-tile-media">
        <img
          class="multicam-tile-img"
          alt={t('multicam.previewAlt', { name: props.camera.name })}
          src={imageUrl()}
          loading="lazy"
          onError={() => {
            // A failed object URL just re-runs the next fetch tick.
            if (frameUrl()) {
              setFrameUrl(null)
              return
            }
            // The MJPEG rung is best-effort: when it fails, retry via the
            // snapshot rung this session; only a failing snapshot marks the
            // tile offline.
            if (mode() === 'live' && !liveBroken()) {
              setLiveBroken(true)
              setMode('snapshot')
              return
            }
            setStatus('offline')
          }}
          onLoad={() => setStatus('online')}
        />
        <span
          class={`multicam-badge multicam-badge-${status() === 'online' ? (mode() === 'live' ? 'live' : 'online') : 'offline'}`}
        >
          {status() === 'online'
            ? (mode() === 'live' ? t('multicam.tile.live') : t('multicam.tile.online'))
            : status() === 'offline' ? t('multicam.tile.offline') : '…'}
        </span>
        <Show when={captureLabel()}>
          <span class={`multicam-badge multicam-capture-badge multicam-capture-${props.captureStatus()}`}>
            {captureLabel()}
          </span>
        </Show>
      </div>
      <div class="multicam-tile-bar">
        <Show when={!editing()} fallback={
          <div class="multicam-edit-form">
            <input
              class="field-input multicam-edit-input"
              type="text"
              placeholder={t('multicam.edit.name')}
              value={editName()}
              onInput={(e) => setEditName(e.currentTarget.value)}
            />
            <input
              class="field-input multicam-edit-input"
              type="text"
              placeholder="http://192.168.1.55:8080"
              value={editUrl()}
              onInput={(e) => setEditUrl(e.currentTarget.value)}
            />
            <div class="multicam-edit-row">
              <label class="multicam-edit-label" for={`multicam-auth-${props.camera.id}`}>{t('multicam.edit.auth')}</label>
              <select
                id={`multicam-auth-${props.camera.id}`}
                class="field-select multicam-edit-select"
                value={editAuthKind()}
                onChange={(e) => setEditAuthKind(e.currentTarget.value as 'none' | 'token' | 'basic')}
              >
                <option value="none">{t('multicam.edit.authNone')}</option>
                <option value="token">{t('multicam.edit.authToken')}</option>
                <option value="basic">{t('multicam.edit.authBasic')}</option>
              </select>
            </div>
            <Show when={editAuthKind() === 'token'}>
              <input
                class="field-input multicam-edit-input"
                type="password"
                placeholder={t('multicam.edit.tokenPlaceholder')}
                autocomplete="off"
                value={editToken()}
                onInput={(e) => setEditToken(e.currentTarget.value)}
              />
            </Show>
            <Show when={editAuthKind() === 'basic'}>
              <input
                class="field-input multicam-edit-input"
                type="text"
                placeholder={t('multicam.edit.userPlaceholder')}
                autocomplete="off"
                value={editUser()}
                onInput={(e) => setEditUser(e.currentTarget.value)}
              />
              <input
                class="field-input multicam-edit-input"
                type="password"
                placeholder={t('multicam.edit.passPlaceholder')}
                autocomplete="off"
                value={editPass()}
                onInput={(e) => setEditPass(e.currentTarget.value)}
              />
            </Show>
            <div class="multicam-edit-row">
              <label class="multicam-edit-label" for={`multicam-refresh-${props.camera.id}`}>{t('multicam.edit.refresh')}</label>
              <select
                id={`multicam-refresh-${props.camera.id}`}
                class="field-select multicam-edit-select"
                value={String(editRefreshMs())}
                onChange={(e) => setEditRefreshMs(Number(e.currentTarget.value))}
              >
                <For each={REFRESH_CHOICES}>
                  {(ms) => <option value={String(ms)}>{ms === 5000 ? t('multicam.refresh.default') : `${ms / 1000} s`}</option>}
                </For>
              </select>
            </div>
            <Show when={editAuthKind() !== 'none'}>
              <div class="multicam-auth-warning" role="note">
                {t('multicam.edit.credsWarning')}
              </div>
            </Show>
            <Show when={editError()}>
              <span class="multicam-form-error">{editError()}</span>
            </Show>
            <div class="multicam-edit-actions">
              <button type="button" class="action-btn action-btn-ghost" onClick={saveEdit}>
                <span>{t('common.save')}</span>
              </button>
              <button type="button" class="action-btn action-btn-ghost" onClick={() => setEditing(false)}>
                <span>{t('common.cancel')}</span>
              </button>
            </div>
          </div>
        }>
          <span class="multicam-tile-name" title={`${props.camera.name} — ${props.camera.baseUrl}`}>{props.camera.name}</span>
          <div class="multicam-tile-actions">
            <button
              type="button"
              class="multicam-mini-btn"
              disabled={!!props.camera.auth}
              title={props.camera.auth
                ? t('multicam.tile.authLive')
                : mode() === 'snapshot' ? t('multicam.tile.toLive') : t('multicam.tile.toSnapshots')}
              onClick={toggleMode}
            >
              {mode() === 'snapshot' ? t('multicam.tile.live') : t('multicam.tile.snapshots')}
            </button>
            <a
              class="multicam-mini-btn"
              href={props.camera.baseUrl}
              target="_blank"
              rel="noopener noreferrer"
              title={t('multicam.tile.dashboardTitle')}
            >
              {t('multicam.tile.dashboard')}
            </a>
            <button type="button" class="multicam-mini-btn" title={t('multicam.tile.editTitle')} onClick={startEdit}>
              {t('multicam.tile.edit')}
            </button>
            <button
              type="button"
              class="multicam-mini-btn multicam-mini-btn-danger"
              title={t('multicam.tile.removeTitle')}
              onClick={() => props.onRemove(props.camera.id)}
            >
              {t('multicam.tile.remove')}
            </button>
          </div>
        </Show>
      </div>
      <Show when={status() === 'offline'}>
        <div class="multicam-offline-hint">
          {t('multicam.offlineHint')}
        </div>
      </Show>
    </div>
  )
}

/**
 * Multi-camera view of several LensCast phones: {id, name, baseUrl, mode?,
 * auth?, refreshMs?} entries persisted in localStorage (shape kept additive
 * — older saves keep loading, parsed by lib/multicamStorage), rendered as a
 * tile grid of snapshots or live MJPEG streams with per-tile toggles, a
 * global "Live all" switch, per-camera optional credentials (stored in plain
 * text — the edit form warns), and a "Capture all" volley that POSTs the
 * capture route on every camera with its credentials.
 */
export default function MultiCamCard() {
  const [cameras, setCameras] = createSignal<SavedCamera[]>([])
  const [hydrated, setHydrated] = createSignal(false)
  const [capturing, setCapturing] = createSignal(false)
  const [captureResults, setCaptureResults] = createSignal<Record<string, CaptureStatus>>({})
  const [name, setName] = createSignal('')
  const [url, setUrl] = createSignal('')
  const [formError, setFormError] = createSignal('')

  function update(patch: (current: SavedCamera[]) => SavedCamera[]) {
    setCameras((current) => {
      const next = patch(current)
      persistCameras(next)
      return next
    })
  }

  function addCamera(e: Event) {
    e.preventDefault()
    const normalized = normalizeBaseUrl(url())
    if (!normalized) {
      setFormError(t('multicam.invalidUrl'))
      return
    }
    if (cameras().some((c) => c.baseUrl === normalized)) {
      setFormError(t('multicam.dupUrl'))
      return
    }
    setFormError('')
    update((current) => [
      ...current,
      { id: makeId(), name: name().trim() || hostLabel(normalized), baseUrl: normalized },
    ])
    setName('')
    setUrl('')
  }

  /**
   * POST /api/camera/capture on every camera with its credentials. The
   * readable rung (cors mode) only succeeds against servers that send CORS
   * headers — today's builds don't, so the rejection falls back to an
   * opaque no-cors fire (the request still reaches the camera and triggers
   * the capture; the outcome is just unverifiable from the browser).
   */
  async function captureAll() {
    if (capturing()) return
    const targets = [...cameras()]
    if (targets.length === 0) return
    setCapturing(true)
    setCaptureResults(Object.fromEntries(targets.map((c) => [c.id, 'sending' as CaptureStatus])))
    await Promise.all(targets.map(async (cam) => {
      const req = captureRequest(cam)
      let outcome: CaptureStatus
      try {
        const res = await fetch(req.url, { method: 'POST', headers: req.headers })
        outcome = res.ok ? 'sent' : 'failed'
      } catch {
        try {
          await fetch(req.url, { method: 'POST', mode: 'no-cors' })
          outcome = 'sent'
        } catch {
          outcome = 'failed'
        }
      }
      setCaptureResults((current) => ({ ...current, [cam.id]: outcome }))
    }))
    setCapturing(false)
    setTimeout(() => setCaptureResults({}), 4000)
  }

  onMount(() => {
    setCameras(loadCameras())
    setHydrated(true)
  })

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="2" y="3" width="20" height="14" rx="2" />
          <rect x="4" y="19" width="6" height="2" rx="1" />
          <rect x="14" y="19" width="6" height="2" rx="1" />
          <path d="M12 3v14" />
        </svg>
      }
      title={t('multicam.title')}
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{tCount('multicam.count', cameras().length)}</span>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="action-btn action-btn-ghost"
              disabled={cameras().length === 0 || capturing()}
              title={t('multicam.captureAllTitle')}
              onClick={() => void captureAll()}
            >
              <span>{capturing() ? t('multicam.capturing') : t('multicam.captureAll')}</span>
            </button>
            <button
              type="button"
              class="action-btn action-btn-ghost"
              disabled={cameras().length === 0}
              title={t('multicam.toggleAllTitle')}
              onClick={() => update((current) => withMode(current, nextGlobalMode(current)))}
            >
              <span>{nextGlobalMode(cameras()) === 'live' ? t('multicam.liveAll') : t('multicam.snapshotsAll')}</span>
            </button>
          </div>
        </div>
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('multicam.desc')}</span>
        </div>
      </div>

      <Show when={hydrated() && cameras().length > 0}>
        <div class="multicam-grid">
          <For each={cameras()}>
            {(camera) => (
              <CameraTile
                camera={camera}
                captureStatus={() => captureResults()[camera.id]}
                onRemove={(id) => update((current) => current.filter((c) => c.id !== id))}
                onUpdate={(id, patch) => update((current) => current.map((c) => (c.id === id ? { ...c, ...patch } : c)))}
              />
            )}
          </For>
        </div>
      </Show>

      <form class="multicam-add-form" onSubmit={addCamera}>
        <input
          class="field-input multicam-add-name"
          type="text"
          placeholder={t('multicam.namePlaceholder')}
          value={name()}
          onInput={(e) => setName(e.currentTarget.value)}
        />
        <input
          class="field-input multicam-add-url"
          type="text"
          placeholder="http://192.168.1.55:8080"
          value={url()}
          onInput={(e) => setUrl(e.currentTarget.value)}
        />
        <button type="submit" class="action-btn">
          <span>{t('multicam.add')}</span>
        </button>
      </form>
      <Show when={formError()}>
        <div class="multicam-form-error" role="alert">{formError()}</div>
      </Show>
    </SettingsCard>
  )
}
