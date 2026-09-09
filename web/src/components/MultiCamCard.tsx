import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import SettingsCard from './SettingsCard'

/**
 * Known limitation: browsers cannot browse mDNS (.local) names, so remote
 * camera URLs are manual entries — e.g. http://192.168.1.55:8080 (the URL
 * shown on each phone's Connect sheet).
 */

interface SavedCamera {
  id: string
  name: string
  baseUrl: string
}

// Remote LensCast routes (server-side: StreamingServer.kt). Plain <img> needs
// no CORS, but both routes sit behind the camera's auth when enabled — see
// the tile error state.
const SNAPSHOT_PATH = '/snapshot' // image/jpeg, latest MJPEG frame
const MJPEG_PATH = '/stream' // multipart/x-mixed-replace, rendered natively by <img>

const STORAGE_KEY = 'lenscast.multicam.cameras'
const SNAPSHOT_REFRESH_MS = 5000

type TileMode = 'snapshot' | 'live'
type TileStatus = 'loading' | 'online' | 'offline'

function makeId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `cam-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function loadCameras(): SavedCamera[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((c): c is SavedCamera =>
      c !== null && typeof c === 'object' &&
      typeof (c as SavedCamera).id === 'string' &&
      typeof (c as SavedCamera).name === 'string' &&
      typeof (c as SavedCamera).baseUrl === 'string',
    )
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
 * One camera tile: name/online badge over a 5 s auto-refreshing snapshot,
 * with a live toggle that swaps the <img> to the MJPEG stream (browsers
 * render multipart streams natively). Errors mark the tile offline and hint
 * at auth: a remote with login enabled rejects credential-less image
 * requests — sign in on its dashboard once, or open it from here.
 */
function CameraTile(props: {
  camera: SavedCamera
  tick: () => number
  onRemove: (id: string) => void
  onUpdate: (id: string, patch: { name?: string; baseUrl?: string }) => void
}) {
  const [mode, setMode] = createSignal<TileMode>('snapshot')
  const [status, setStatus] = createSignal<TileStatus>('loading')
  const [editing, setEditing] = createSignal(false)
  const [editName, setEditName] = createSignal(props.camera.name)
  const [editUrl, setEditUrl] = createSignal(props.camera.baseUrl)
  const [editError, setEditError] = createSignal('')

  const imageUrl = () => mode() === 'live'
    ? `${props.camera.baseUrl}${MJPEG_PATH}`
    : `${props.camera.baseUrl}${SNAPSHOT_PATH}?t=${props.tick()}`

  function startEdit() {
    setEditName(props.camera.name)
    setEditUrl(props.camera.baseUrl)
    setEditError('')
    setEditing(true)
  }

  function saveEdit() {
    const normalized = normalizeBaseUrl(editUrl())
    if (!normalized) {
      setEditError('Enter a valid http(s) URL, e.g. http://192.168.1.55:8080')
      return
    }
    props.onUpdate(props.camera.id, {
      name: editName().trim() || hostLabel(normalized),
      baseUrl: normalized,
    })
    setEditing(false)
  }

  return (
    <div class="multicam-tile" classList={{ 'multicam-tile-offline': status() === 'offline' }}>
      <div class="multicam-tile-media">
        <img
          class="multicam-tile-img"
          alt={`${props.camera.name} preview`}
          src={imageUrl()}
          loading="lazy"
          onError={() => setStatus('offline')}
          onLoad={() => setStatus('online')}
        />
        <span
          class={`multicam-badge multicam-badge-${status() === 'online' ? (mode() === 'live' ? 'live' : 'online') : 'offline'}`}
        >
          {status() === 'online'
            ? (mode() === 'live' ? 'Live' : 'Online')
            : status() === 'offline' ? 'Offline' : '…'}
        </span>
      </div>
      <div class="multicam-tile-bar">
        <Show when={!editing()} fallback={
          <div class="multicam-edit-form">
            <input
              class="field-input multicam-edit-input"
              type="text"
              placeholder="Name"
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
            <Show when={editError()}>
              <span class="multicam-form-error">{editError()}</span>
            </Show>
            <div class="multicam-edit-actions">
              <button type="button" class="action-btn action-btn-ghost" onClick={saveEdit}>
                <span>Save</span>
              </button>
              <button type="button" class="action-btn action-btn-ghost" onClick={() => setEditing(false)}>
                <span>Cancel</span>
              </button>
            </div>
          </div>
        }>
          <span class="multicam-tile-name" title={`${props.camera.name} — ${props.camera.baseUrl}`}>{props.camera.name}</span>
          <div class="multicam-tile-actions">
            <button
              type="button"
              class="multicam-mini-btn"
              title={mode() === 'snapshot' ? 'Switch to the live MJPEG stream' : 'Back to 5 s snapshots'}
              onClick={() => {
                setStatus('loading')
                setMode(mode() === 'snapshot' ? 'live' : 'snapshot')
              }}
            >
              {mode() === 'snapshot' ? 'Live' : 'Snapshots'}
            </button>
            <a
              class="multicam-mini-btn"
              href={props.camera.baseUrl}
              target="_blank"
              rel="noopener noreferrer"
              title="Open this camera's dashboard"
            >
              Dashboard
            </a>
            <button type="button" class="multicam-mini-btn" title="Edit name / URL" onClick={startEdit}>
              Edit
            </button>
            <button
              type="button"
              class="multicam-mini-btn multicam-mini-btn-danger"
              title="Remove camera"
              onClick={() => props.onRemove(props.camera.id)}
            >
              Remove
            </button>
          </div>
        </Show>
      </div>
      <Show when={status() === 'offline'}>
        <div class="multicam-offline-hint">
          No image from this camera. It may be unreachable — or it requires login
          (open its dashboard and sign in once, or disable stream auth).
        </div>
      </Show>
    </div>
  )
}

/**
 * Lightweight multi-camera MVP for several LensCast phones: user-added
 * {id, name, baseUrl} entries persisted in localStorage, rendered as a tile
 * grid of snapshots with a live toggle. Only <img> requests are made toward
 * the remote cameras (no cross-origin JSON fetches, no credential access).
 */
export default function MultiCamCard() {
  const [cameras, setCameras] = createSignal<SavedCamera[]>([])
  const [hydrated, setHydrated] = createSignal(false)
  const [tick, setTick] = createSignal(0)
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
      setFormError('Enter a valid http(s) URL, e.g. http://192.168.1.55:8080')
      return
    }
    if (cameras().some((c) => c.baseUrl === normalized)) {
      setFormError('That camera URL is already in the list')
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

  onMount(() => {
    setCameras(loadCameras())
    setHydrated(true)
    const timer = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return
      setTick((t) => t + 1)
    }, SNAPSHOT_REFRESH_MS)
    onCleanup(() => clearInterval(timer))
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
      title="Cameras"
    >
      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{cameras().length} camera{cameras().length === 1 ? '' : 's'}</span>
        </div>
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>Add other LensCast phones by their dashboard URL — mDNS names can't be browsed from a browser, so copy the http://IP:port shown on each phone.</span>
        </div>
      </div>

      <Show when={hydrated() && cameras().length > 0}>
        <div class="multicam-grid">
          <For each={cameras()}>
            {(camera) => (
              <CameraTile
                camera={camera}
                tick={tick}
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
          placeholder="Name (optional)"
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
          <span>Add camera</span>
        </button>
      </form>
      <Show when={formError()}>
        <div class="multicam-form-error" role="alert">{formError()}</div>
      </Show>
    </SettingsCard>
  )
}
