// Pure Web Push support helpers for the Web Push card — the whip.ts pattern:
// every decision takes its inputs as plain values (no DOM globals), so vitest
// can pin the behavior without a browser.

/**
 * The feature gate behind the card's visibility, mirroring the browser's own
 * requirements and the standard feature-detect order: Web Push needs a secure
 * context (HTTPS — the dashboard's self-signed TLS mode qualifies once the
 * user accepts the certificate; plain http:// LAN IPs can never register a
 * service worker), a ServiceWorkerContainer on the navigator, the PushManager
 * interface on the window, and a non-denied notification permission. Each
 * piece arrives as a structural input so the check is total and testable: a
 * missing object reads as unsupported, never a crash.
 */
export interface PushSupportInput {
  /** window.isSecureContext — true on HTTPS (and localhost). */
  isSecureContext: boolean
  /** window.navigator — inspected for the service worker container. */
  navigator: {
    serviceWorker?: unknown
  }
  /** The window's PushManager interface — absent where Web Push is missing. */
  PushManager?: unknown
  /** The Notification global; its permission state gates the UI too. */
  Notification?: {
    permission: 'granted' | 'denied' | 'default' | string
  }
}

export type PushSupportReason =
  | 'insecure-context'
  | 'no-service-worker'
  | 'no-push-manager'
  | 'permission-denied'
  | 'supported'

/**
 * Why the Web Push card is (or is not) available, exactly one verdict per
 * input — the card shows the matching explanation instead of hiding silently.
 */
export function pushSupport(input: PushSupportInput): PushSupportReason {
  if (!input.isSecureContext) return 'insecure-context'
  if (!input.navigator?.serviceWorker) return 'no-service-worker'
  if (!input.PushManager) return 'no-push-manager'
  if (input.Notification?.permission === 'denied') return 'permission-denied'
  return 'supported'
}

/** The single boolean the card's Show gates read. */
export function isPushSupported(input: PushSupportInput): boolean {
  return pushSupport(input) === 'supported'
}

/**
 * The browser's `applicationServerKey` plumbing: the VAPID public key
 * (base64url, 65-byte uncompressed P-256 point) as the Uint8Array
 * `pushManager.subscribe` requires. Reads atob, so it only ever runs in the
 * browser — the vitest node env provides the same global.
 */
export function urlBase64ToUint8Array(base64Url: string): Uint8Array<ArrayBuffer> {
  const normalized = base64Url.replace(/-/g, '+').replace(/_/g, '/')
  const padding = (4 - (normalized.length % 4)) % 4
  const padded = normalized + '='.repeat(padding)
  const binary = atob(padded)
  // Backed by a plain ArrayBuffer so the result is a BufferSource for
  // pushManager.subscribe under TS 5.7's typed-array generics.
  const bytes = new Uint8Array(new ArrayBuffer(binary.length))
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

/** The Notification.permission text the card renders for the current state. */
export function permissionLabel(permission: string | undefined): string {
  switch (permission) {
    case 'granted':
      return 'Allowed'
    case 'denied':
      return 'Blocked'
    case 'default':
      return 'Not requested'
    default:
      return 'Unknown'
  }
}
