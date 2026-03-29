import type { AllSettings, DeviceStatus, LensesResponse } from '../types'

function basicAuthHeader(username: string, password: string): string {
  return 'Basic ' + btoa(username + ':' + password)
}

export async function getAuthStatus(): Promise<{ required: boolean }> {
  const res = await fetch('/api/auth/status')
  if (!res.ok) throw new Error(`Failed to fetch auth status: ${res.status}`)
  return res.json()
}

export async function login(username: string, password: string): Promise<AllSettings> {
  const res = await fetch('/api/settings', {
    headers: { Authorization: basicAuthHeader(username, password) },
  })
  if (res.status === 401) throw new Error('Invalid credentials')
  if (!res.ok) throw new Error(`Login failed: ${res.status}`)
  return res.json()
}

export async function logout(): Promise<void> {
  await fetch('/api/auth/logout', { method: 'POST' })
}

export async function getSettings(): Promise<AllSettings> {
  const res = await fetch('/api/settings')
  if (res.status === 401) throw new Error('Authentication required')
  if (!res.ok) throw new Error(`Failed to fetch settings: ${res.status}`)
  return res.json()
}

export async function updateSettings(settings: Partial<AllSettings>): Promise<{ success: boolean; error?: string }> {
  const res = await fetch('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
  if (!res.ok) throw new Error(`Failed to update settings: ${res.status}`)
  return res.json()
}

export async function getStatus(): Promise<DeviceStatus> {
  const res = await fetch('/api/status')
  if (!res.ok) throw new Error(`Failed to fetch status: ${res.status}`)
  return res.json()
}

export async function capturePhoto(): Promise<{ success: boolean; fileName?: string; error?: string }> {
  const res = await fetch('/api/capture', { method: 'POST' })
  if (!res.ok) throw new Error(`Failed to capture: ${res.status}`)
  return res.json()
}

export async function getLenses(): Promise<LensesResponse> {
  const res = await fetch('/api/camera/lenses')
  if (!res.ok) throw new Error(`Failed to fetch lenses: ${res.status}`)
  return res.json()
}

export async function selectLens(index: number): Promise<{ success: boolean }> {
  const res = await fetch('/api/camera/lens', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ index }),
  })
  if (!res.ok) throw new Error(`Failed to select lens: ${res.status}`)
  return res.json()
}

export async function stopStream(): Promise<{ success: boolean; isActive?: boolean; error?: string }> {
  const res = await fetch('/api/stream/stop', { method: 'POST' })
  if (!res.ok) throw new Error(`Failed to stop stream: ${res.status}`)
  return res.json()
}

export async function startStream(): Promise<{ success: boolean; isActive?: boolean; url?: string; error?: string }> {
  const res = await fetch('/api/stream/start', { method: 'POST' })
  if (!res.ok) throw new Error(`Failed to start stream: ${res.status}`)
  return res.json()
}
