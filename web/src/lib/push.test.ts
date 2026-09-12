import { describe, expect, it } from 'vitest'
import { isPushSupported, permissionLabel, pushNotificationUrl, pushSupport, urlBase64ToUint8Array } from './push'
import type { PushSupportInput } from './push'

function input(overrides: Partial<PushSupportInput> = {}): PushSupportInput {
  return {
    isSecureContext: true,
    navigator: { serviceWorker: {} },
    PushManager: function PushManagerStub() {},
    Notification: { permission: 'default' },
    ...overrides,
  }
}

describe('pushSupport', () => {
  it('reports supported on a secure context with the full API surface', () => {
    expect(pushSupport(input())).toBe('supported')
    expect(isPushSupported(input())).toBe(true)
  })

  it('rejects an insecure context first — plain http:// LAN IPs can never push', () => {
    expect(pushSupport(input({ isSecureContext: false }))).toBe('insecure-context')
    // The insecure verdict outranks missing APIs: the card explains HTTPS.
    const insecure = input({
      isSecureContext: false,
      navigator: {},
      PushManager: undefined,
      Notification: { permission: 'denied' },
    })
    expect(pushSupport(insecure)).toBe('insecure-context')
    expect(isPushSupported(insecure)).toBe(false)
  })

  it('rejects browsers without a service worker container', () => {
    expect(pushSupport(input({ navigator: {} }))).toBe('no-service-worker')
  })

  it('rejects browsers without the PushManager interface (no Web Push capability)', () => {
    expect(pushSupport(input({ PushManager: undefined }))).toBe('no-push-manager')
  })

  it('rejects a denied notification permission', () => {
    expect(pushSupport(input({ Notification: { permission: 'denied' } }))).toBe('permission-denied')
  })

  it('tolerates a missing Notification global (permission still askable)', () => {
    expect(pushSupport(input({ Notification: undefined }))).toBe('supported')
  })

  it('never crashes on bare shapes', () => {
    // Some embedded browsers expose neither the container nor the interface.
    expect(isPushSupported(input({ navigator: {}, PushManager: undefined }))).toBe(false)
  })
})

describe('urlBase64ToUint8Array', () => {
  it('decodes a base64url applicationServerKey to its exact bytes', () => {
    // 65-byte uncompressed P-256 point: 0x04 prefix, then 64 identical bytes
    // (base64url 'BAcH…Bwc', 88 chars, one restored pad char).
    const key = urlBase64ToUint8Array(
      'BAcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc',
    )
    expect(key).toBeInstanceOf(Uint8Array)
    expect(key.length).toBe(65)
    expect(key[0]).toBe(0x04)
    expect(key.slice(1).every((b) => b === 7)).toBe(true)
  })

  it('decodes the empty string and unpadded tails consistently', () => {
    // 'aw==' base64url-stripped is 'aw': one padding char restored.
    expect(Array.from(urlBase64ToUint8Array('aw'))).toEqual([107])
    expect(Array.from(urlBase64ToUint8Array(''))).toEqual([])
  })

  it('round-trips URL-safe characters that plain base64 would reject', () => {
    // Bytes 251,255,190,239: their base64 contains '+' and '/', which map to
    // '-' and '_' in base64url — decode must restore the exact bytes.
    expect(Array.from(urlBase64ToUint8Array('-_--7w'))).toEqual([251, 255, 190, 239])
  })
})

describe('permissionLabel', () => {
  it('maps each permission state to its display text', () => {
    expect(permissionLabel('granted')).toBe('Allowed')
    expect(permissionLabel('denied')).toBe('Blocked')
    expect(permissionLabel('default')).toBe('Not requested')
    expect(permissionLabel(undefined)).toBe('Unknown')
  })
})

describe('pushNotificationUrl', () => {
  it('uses the payload url verbatim when the server sends one', () => {
    expect(pushNotificationUrl({ url: '#/events' })).toBe('#/events')
    expect(pushNotificationUrl({ url: '#/gallery/abc123' })).toBe('#/gallery/abc123')
  })

  it('derives the clip deep link from the legacy payload fields', () => {
    // Today's payloads carry clipAvailable + eventId and no url at all.
    expect(pushNotificationUrl({ clipAvailable: true, eventId: 'clip 7' }))
      .toBe('#/gallery/clip%207')
  })

  it('does not derive a clip link when the clip flag or id is missing', () => {
    expect(pushNotificationUrl({ clipAvailable: true })).toBe('/')
    expect(pushNotificationUrl({ clipAvailable: false, eventId: 'x' })).toBe('/')
    expect(pushNotificationUrl({ clipAvailable: 'yes', eventId: 'x' })).toBe('/')
  })

  it('falls back to the dashboard root for unrecognized shapes', () => {
    expect(pushNotificationUrl({})).toBe('/')
    expect(pushNotificationUrl({ url: 42 })).toBe('/')
    expect(pushNotificationUrl({ url: 'javascript:alert(1)' })).toBe('/')
    expect(pushNotificationUrl(null as unknown as Record<string, unknown>)).toBe('/')
  })
})
