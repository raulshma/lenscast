import type { AllSettings, AuthConfig, DetectionEventType, DeviceStatus, LensesResponse, SessionRole } from '../types'

type JsonValue = Record<string, unknown> | unknown[] | string | number | boolean | null

const pendingRequests = new Map<string, Promise<JsonValue>>()

function dedupeKey(input: string, init: RequestInit = {}): string {
  return `${init.method || 'GET'}:${input}`
}

async function requestJson<T>(input: string, init: RequestInit = {}): Promise<T> {
  const key = dedupeKey(input, init)
  const existing = pendingRequests.get(key)
  if (existing) {
    return existing as Promise<T>
  }

  const promise = (async () => {
    try {
      const response = await fetch(input, {
        cache: 'no-store',
        credentials: 'same-origin',
        keepalive: true,
        ...init,
        headers: {
          'X-Requested-With': 'XMLHttpRequest',
          ...(init.headers ?? {}),
        },
      })

      const rawBody = await response.text()
      const body = rawBody ? safeParseJson(rawBody) : null

      if (!response.ok) {
        const message = extractErrorMessage(body) ?? `Request failed: ${response.status}`
        if (response.status === 401 && message === `Request failed: ${response.status}`) {
          throw new Error('Authentication required')
        }
        throw new Error(message)
      }

      return body as T
    } finally {
      pendingRequests.delete(key)
    }
  })()

  pendingRequests.set(key, promise as Promise<JsonValue>)
  return promise
}

function safeParseJson(value: string): JsonValue {
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    return value
  }
}

function extractErrorMessage(body: JsonValue): string | null {
  if (typeof body === 'string' && body.trim()) return body
  if (body && typeof body === 'object' && !Array.isArray(body)) {
    const candidate = body.error ?? body.message
    if (typeof candidate === 'string' && candidate.trim()) {
      return candidate
    }
  }
  return null
}

export async function getAuthStatus(): Promise<{ required: boolean }> {
  return requestJson('/api/auth/status')
}

// The success answer carries the session's role ("admin" | "viewer") so the
// dashboard can render its read-only state immediately after sign-in.
export async function login(username: string, password: string): Promise<{ success: boolean; required?: boolean; role?: SessionRole }> {
  return requestJson('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
}

export async function getSessionStatus(): Promise<{ authenticated: boolean; role?: SessionRole }> {
  return requestJson('/api/auth/session')
}

export async function logout(): Promise<void> {
  await requestJson('/api/auth/logout', { method: 'POST' })
}

export async function getSettings(): Promise<AllSettings> {
  return requestJson('/api/settings')
}

export async function downloadDetectionModel(): Promise<{ success: boolean }> {
  return requestJson('/api/settings/ml-model/download', { method: 'POST' })
}

export async function updateSettings(settings: Partial<AllSettings>): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
}

// Settings PUTs replace the whole streaming block, so every streaming save
// merges the patch into a freshly fetched snapshot — a partial object would
// reset the other fields to their defaults server-side. Two overlapping
// saves must not interleave their GET-merge-PUT sequences (the later save's
// snapshot would predate the earlier save's PUT and silently revert it), so
// every patch rides one shared chain.
let streamingSaveChain: Promise<unknown> = Promise.resolve()

export function saveStreamingPatch(patch: Partial<AllSettings['streaming']>): Promise<void> {
  const save = streamingSaveChain.then(async () => {
    const current = await getSettings()
    await updateSettings({ streaming: { ...current.streaming, ...patch } })
  })
  // A failed save must not poison the chain for later patches; the caller
  // still sees the rejection through the returned promise.
  streamingSaveChain = save.catch(() => undefined)
  return save
}

export interface AuthSessionInfo {
  tokenPrefix: string
  expiresAtMs: number
  /** `"admin"` | `"viewer"`; absent in pre-role responses — treated as admin. */
  role?: SessionRole
}

export async function getAuthSessions(): Promise<{ sessions: AuthSessionInfo[] }> {
  return requestJson('/api/auth/sessions')
}

export async function getAuthConfig(): Promise<AuthConfig> {
  return requestJson('/api/auth/config')
}

export async function updateAuthConfig(body: {
  enabled: boolean
  username: string
  password?: string
  viewerEnabled?: boolean
  viewerUsername?: string | null
  viewerPassword?: string
}): Promise<unknown> {
  return requestJson('/api/auth/config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function revokeAuthSession(prefix: string): Promise<void> {
  await requestJson(`/api/auth/sessions/${encodeURIComponent(prefix)}`, { method: 'DELETE' })
}

export async function getStatus(): Promise<DeviceStatus> {
  return requestJson('/api/status')
}

export async function capturePhoto(): Promise<{ success: boolean; fileName?: string; error?: string }> {
  return requestJson('/api/capture', { method: 'POST' })
}

export async function getLenses(): Promise<LensesResponse> {
  return requestJson('/api/camera/lenses')
}

export async function selectLens(index: number): Promise<{ success: boolean }> {
  return requestJson('/api/camera/lens', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ index }),
  })
}

export async function startWebStream(): Promise<{ success: boolean; isActive?: boolean; url?: string; error?: string }> {
  return requestJson('/api/stream/web/start', { method: 'POST' })
}

export async function stopWebStream(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/web/stop', { method: 'POST' })
}

export async function startRtspStream(): Promise<{ success: boolean; isActive?: boolean; url?: string; error?: string }> {
  return requestJson('/api/stream/rtsp/start', { method: 'POST' })
}

export async function stopRtspStream(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/rtsp/stop', { method: 'POST' })
}

// WHIP push (WebRTC-HTTP egress): the RTSP pair's shape minus `url` — the
// endpoint is the device's configured publish resource, not something a
// viewer opens, so the start response carries no URL.
export async function startWhip(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/whip/start', { method: 'POST' })
}

export async function stopWhip(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/whip/stop', { method: 'POST' })
}

// RTMP push: the WHIP pair's shape — the start response carries no URL since
// the push target embeds the stream key, and a refused start leaves the
// readable reason on the RTMP status rather than this payload.
export async function startRtmp(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/rtmp/start', { method: 'POST' })
}

export async function stopRtmp(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/rtmp/stop', { method: 'POST' })
}

export async function getIntervalCaptureStatus(): Promise<import('../types').IntervalCaptureStatus> {
  return requestJson('/api/capture/interval/status')
}

export async function startIntervalCapture(config: import('../types').IntervalCaptureConfig): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/capture/interval/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
}

export async function stopIntervalCapture(): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/capture/interval/stop', { method: 'POST' })
}

export async function getRecordingStatus(): Promise<import('../types').RecordingStatus> {
  return requestJson('/api/recording/status')
}

export async function startRecording(config: import('../types').RecordingConfig): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/recording/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
}

export async function stopRecording(): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/recording/stop', { method: 'POST' })
}

export async function getGallery(type?: string, page: number = 0, pageSize: number = 0): Promise<import('../types').GalleryResponse> {
  const params = new URLSearchParams()
  if (type) params.set('type', encodeURIComponent(type))
  if (page > 0) params.set('page', String(page))
  if (pageSize > 0) params.set('pageSize', String(pageSize))
  const url = params.toString() ? `/api/gallery?${params.toString()}` : '/api/gallery'
  return requestJson(url)
}

export async function deleteMedia(id: string): Promise<{ success: boolean; error?: string }> {
  return requestJson(`/api/media/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function deleteMediaBatch(ids: string[]): Promise<{ success: boolean; error?: string; deleted?: string[] }> {
  return requestJson('/api/media/batch-delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids }),
  })
}

export async function tapToFocus(x: number, y: number): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/camera/focus', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ x, y }),
  })
}

export async function setZoom(zoomRatio: number): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/camera/zoom', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ zoomRatio }),
  })
}

export async function setTorch(enabled: boolean): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/camera/torch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
}

export async function listStreamClients(): Promise<{ httpClients: string[]; httpCount: number; rtspCount: number; maxHttp: number }> {
  return requestJson('/api/stream/clients')
}

export async function kickStreamClient(id: string): Promise<{ success: boolean }> {
  return requestJson(`/api/stream/clients/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function pushTalkback(pcm16: ArrayBuffer): Promise<void> {
  await fetch('/api/audio/uplink', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/octet-stream' },
    body: pcm16,
  })
}

export async function setSiren(on: boolean): Promise<{ success: boolean }> {
  return requestJson('/api/deterrence/siren', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ siren: on }),
  })
}

/** `limit` omitted or non-positive means the server's default page size; `type` narrows to one wire-name kind. */
export async function getDetectionEvents(
  limit?: number,
  type?: DetectionEventType,
): Promise<import('../types').DetectionEventsResponse> {
  const params = new URLSearchParams()
  if (limit != null && limit > 0) params.set('limit', String(limit))
  if (type) params.set('type', type)
  const url = params.toString() ? `/api/detection/events?${params.toString()}` : '/api/detection/events'
  return requestJson(url)
}

export async function clearDetectionEvents(): Promise<{ success: boolean }> {
  return requestJson('/api/detection/events', { method: 'DELETE' })
}

/** GET /api/detection/stats — aggregate counts (windows, per-day series, top zones/labels) over the persisted event log. */
export async function getDetectionStats(): Promise<import('../types').DetectionStats> {
  return requestJson('/api/detection/stats')
}

/**
 * GET /api/recordings/sessions?day=YYYY-MM-DD — the NVR day timeline. The
 * Android endpoint lands separately, so callers must tolerate failures
 * (404 today) and degrade to an empty "no session data" state.
 */
export async function getRecordingSessions(day: string): Promise<import('../types').RecordingSessionsResponse> {
  const params = new URLSearchParams({ day })
  return requestJson(`/api/recordings/sessions?${params.toString()}`)
}

/** The event-log export URL: CSV by default, JSON on demand, snapshots omitted. */
export function detectionEventsExportUrl(format: 'csv' | 'json' = 'csv', type?: DetectionEventType): string {
  const params = new URLSearchParams({ format })
  if (type) params.set('type', type)
  return `/api/detection/events/export?${params.toString()}`
}

/** The read-only diagnostics snapshot: build, device, uptimes, battery, storage. */
export async function getSystemInfo(): Promise<import('../types').SystemInfo> {
  return requestJson('/api/system')
}

/** Fires one synthetic test alert through the real alert sinks (webhook, MQTT, notification). */
export async function sendTestAlert(): Promise<import('../types').DetectionTestResponse> {
  return requestJson('/api/detection/test', { method: 'POST' })
}

/** Downloads the versioned settings export envelope (secrets already blanked server-side). */
export async function exportSettings(): Promise<import('../types').SettingsExport> {
  return requestJson('/api/settings/export')
}

/**
 * Uploads a previously exported settings file. The export envelope is the
 * lossless path; a bare settings document gets full PUT semantics (omitted
 * fields of a present section take their defaults).
 */
export async function importSettings(exported: unknown): Promise<{ success: boolean }> {
  return requestJson('/api/settings/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(exported),
  })
}

/** `limit` omitted or non-positive means the server's full trail. */
export async function getAuditLog(limit?: number): Promise<import('../types').AuditLogResponse> {
  const params = new URLSearchParams()
  if (limit != null && limit > 0) params.set('limit', String(limit))
  const url = params.toString() ? `/api/audit?${params.toString()}` : '/api/audit'
  return requestJson(url)
}

export async function clearAuditLog(): Promise<{ success: boolean }> {
  return requestJson('/api/audit', { method: 'DELETE' })
}

// ── Web Push (browser subscriptions; the phone publishes to them) ──

/** The VAPID server key: base64url, 65-byte uncompressed P-256 point — the subscription's applicationServerKey. */
export async function getVapidPublicKey(): Promise<{ publicKey: string }> {
  return requestJson('/api/push/vapid-public')
}

export interface PushSubscriptionInfo {
  endpoint: string
  createdAtMs: number
}

/** GET /api/push/subscriptions — stored endpoints, key material redacted server-side. */
export async function listPushSubscriptions(): Promise<{ subscriptions: PushSubscriptionInfo[]; count: number }> {
  return requestJson('/api/push/subscriptions')
}

/**
 * POST /api/push/subscriptions — this browser's PushSubscription, flattened
 * to its endpoint and key material. Session-only by server design.
 */
export async function subscribePush(body: { endpoint: string; p256dh: string; auth: string }): Promise<{ success: boolean }> {
  return requestJson('/api/push/subscriptions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** DELETE /api/push/subscriptions?endpoint=… — unsubscribe after (or without) an unsubscribe() on the browser side. */
export async function deletePushSubscription(endpoint: string): Promise<{ success: boolean }> {
  const params = new URLSearchParams({ endpoint })
  return requestJson(`/api/push/subscriptions?${params.toString()}`, { method: 'DELETE' })
}
