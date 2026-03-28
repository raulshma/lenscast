import type { AllSettings, DeviceStatus, LensesResponse } from '../types'

export async function getSettings(): Promise<AllSettings> {
  const res = await fetch('/api/settings')
  if (!res.ok) throw new Error(`Failed to fetch settings: ${res.status}`)
  return res.json()
}

export async function updateSettings(settings: Partial<AllSettings>): Promise<{ success: boolean; error?: string }> {
  const res = await fetch('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
  return res.json()
}

export async function getStatus(): Promise<DeviceStatus> {
  const res = await fetch('/api/status')
  if (!res.ok) throw new Error(`Failed to fetch status: ${res.status}`)
  return res.json()
}

export async function capturePhoto(): Promise<{ success: boolean; fileName?: string; error?: string }> {
  const res = await fetch('/api/capture', { method: 'POST' })
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
  return res.json()
}
