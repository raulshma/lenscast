import type { DeviceStatus } from '../../../types'
import type { LedTone } from '../ui'
import { t } from '../../../lib/i18n'

/**
 * Shared telemetry mapping for the console: channel descriptors for the
 * strip and the Live ChannelMatrix, plus tone/label vocabulary for LEDs.
 * Pure functions over `DeviceStatus` — no signals, easy to reason about.
 */

export interface ChannelState {
  code: 'WEB' | 'RTSP' | 'RTMP' | 'WHIP'
  enabled: boolean
  active: boolean
  status?: 'idle' | 'connecting' | 'connected' | 'error' | 'blocked'
  error?: string | null
}

/**
 * The locked-mount status for a channel row: 'blocked' only while the row is
 * genuinely active *and* the device says the camera bind is denied under
 * lock. The flag is absent on older firmware, so absence reads unblocked.
 */
function lockBlockStatus(blockedByLock: boolean, active: boolean): ChannelState['status'] {
  return blockedByLock && active ? 'blocked' : undefined
}

/** The four output channels, in strip order. */
export function channelStates(status: DeviceStatus | null | undefined): ChannelState[] {
  const s = status?.streaming
  if (!s) return []
  const blockedByLock = status?.cameraBlockedByLock === true
  return [
    {
      code: 'WEB',
      enabled: s.webStreamingEnabled,
      active: s.webStreamingActive,
      status: lockBlockStatus(blockedByLock, s.webStreamingActive),
    },
    {
      code: 'RTSP',
      enabled: s.rtspEnabled,
      active: s.rtspStreamingActive,
      status: lockBlockStatus(blockedByLock, s.rtspStreamingActive),
    },
    { code: 'RTMP', enabled: s.rtmpEnabled === true, active: s.rtmpActive === true, status: s.rtmpStatus, error: s.rtmpError },
    { code: 'WHIP', enabled: s.whipEnabled === true, active: s.whipActive === true, status: s.whipStatus, error: s.whipError },
  ]
}

/** LED tone for a channel: on-air green, handshake/blocked amber, fault red. */
export function channelLed(c: ChannelState): LedTone {
  // Blocked outranks active: the row is nominally on air but the camera is
  // denied — amber, not green.
  if (c.status === 'blocked') return 'warn'
  if (c.active) return 'ok'
  if (c.status === 'connecting') return 'warn'
  if (c.status === 'error') return 'danger'
  return 'off'
}

/** Compact throughput label: 980K / 14.2M (kbps in, console-speak out). */
export function formatKbps(kbps: number): string {
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(1)}M` : `${Math.round(kbps)}K`
}

const LINK_TONES: Record<string, LedTone> = {
  EXCELLENT: 'ok',
  GOOD: 'ok',
  FAIR: 'warn',
  POOR: 'warn',
  CRITICAL: 'danger',
}

const LINK_LABELS: Record<string, string> = {
  EXCELLENT: 'EXC',
  GOOD: 'GOOD',
  FAIR: 'FAIR',
  POOR: 'POOR',
  CRITICAL: 'CRIT',
}

export function linkLed(qualityLevel: string | undefined): LedTone {
  return (qualityLevel && LINK_TONES[qualityLevel]) || 'off'
}

export function linkLabel(qualityLevel: string | undefined): string {
  return (qualityLevel && LINK_LABELS[qualityLevel]) || '—'
}

export type VitalTone = 'default' | 'ok' | 'warn' | 'danger'

export function batteryTone(level: number | undefined): VitalTone {
  if (level == null) return 'default'
  if (level <= 15) return 'danger'
  if (level <= 35) return 'warn'
  return 'default'
}

export function thermalTone(state: string | undefined): VitalTone {
  switch (state) {
    case 'SEVERE':
    case 'CRITICAL':
      return 'danger'
    case 'LIGHT':
    case 'MODERATE':
      return 'warn'
    default:
      return 'default'
  }
}

export function thermalLabel(state: string | undefined): string {
  switch (state) {
    case 'NORMAL':
      return t('next.thermal.normal')
    case 'LIGHT':
      return t('next.thermal.light')
    case 'MODERATE':
      return t('next.thermal.moderate')
    case 'SEVERE':
      return t('next.thermal.severe')
    case 'CRITICAL':
      return t('next.thermal.critical')
    default:
      return '—'
  }
}

/** The vitals row's color class for a tone — the one variant→class map for
 * battery, thermal, and link alike (three identical cascades used to live in
 * NextShell). Neutral tones render classless. */
export function vitalToneClass(tone: VitalTone | LedTone): string {
  switch (tone) {
    case 'ok':
      return 'nx-vital-ok'
    case 'warn':
      return 'nx-vital-warn'
    case 'danger':
      return 'nx-vital-danger'
    default:
      return ''
  }
}
