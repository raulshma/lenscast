import type { AllSettings, DeviceStatus, LensesResponse } from '../types'

type JsonValue = Record<string, unknown> | unknown[] | string | number | boolean | null

async function requestJson<T>(input: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(input, {
    cache: 'no-store',
    credentials: 'same-origin',
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

export async function login(username: string, password: string): Promise<{ success: boolean; required?: boolean }> {
  return requestJson('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
}

export async function getSessionStatus(): Promise<{ authenticated: boolean }> {
  return requestJson('/api/auth/session')
}

export async function logout(): Promise<void> {
  await requestJson('/api/auth/logout', { method: 'POST' })
}

export async function getSettings(): Promise<AllSettings> {
  return requestJson('/api/settings')
}

export async function updateSettings(settings: Partial<AllSettings>): Promise<{ success: boolean; error?: string }> {
  return requestJson('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
}

export async function getStatus(): Promise<DeviceStatus> {
  return requestJson('/api/status')
}

export async function capturePhoto(): Promise<{ success: boolean; fileName?: string; error?: string }> {
  return requestJson('/api/capture', { method: 'POST' })
}

export async function downloadHighResSnapshot(saveToDisk: boolean = false): Promise<string> {
  const params = new URLSearchParams()
  params.set('highres', '1')
  if (saveToDisk) params.set('save', '1')
  return `/snapshot?${params.toString()}`
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

export async function stopStream(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  return requestJson('/api/stream/stop', { method: 'POST' })
}

export async function startStream(): Promise<{ success: boolean; isActive?: boolean; url?: string; error?: string }> {
  return requestJson('/api/stream/start', { method: 'POST' })
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

export async function getGallery(type?: string): Promise<import('../types').GalleryResponse> {
  const url = type ? `/api/gallery?type=${encodeURIComponent(type)}` : '/api/gallery'
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
