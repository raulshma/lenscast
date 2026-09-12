/*
 * LensCast Web Push service worker — deliberately minimal.
 *
 * This worker exists ONLY for Web Push (RFC 8030 push events + notification
 * clicks). It performs no caching of the app shell on purpose: LensCast is a
 * live streaming dashboard, and a cached shell would serve stale UI or break
 * stream reconnects (see index.html's installability note). The scope is the
 * worker's root path ("/push-sw.js" at the origin root), so it controls the
 * whole origin and its notifications carry the dashboard's identity.
 *
 * The phone encrypts each detection event per RFC 8291 (aes128gcm) and signs
 * it per RFC 8292 (VAPID); the browser decrypts it before the `push` event,
 * so the payload here is already plaintext JSON:
 *   { title, body, tag, eventId, type, timestampMs, clipAvailable }
 *
 * Newer phone builds may add `url` — the in-dashboard deep link (e.g.
 * "#/events" or "#/gallery/<clipId>") the click should open. The derivation
 * below mirrors lib/push.ts's pushNotificationUrl and reads every field
 * defensively, so older payloads keep working unchanged.
 */

/**
 * The in-dashboard deep link for a push payload: an explicit `url` field
 * wins, then a clip-bearing event maps to the gallery viewer on its clip,
 * and anything unrecognized falls back to the plain dashboard root.
 */
function deepLinkUrl(payload) {
  if (!payload) return '/'
  const url = payload.url
  if (typeof url === 'string' && (url.startsWith('#') || url.startsWith('/'))) return url
  if (payload.clipAvailable === true && typeof payload.eventId === 'string' && payload.eventId) {
    return '#/gallery/' + encodeURIComponent(payload.eventId)
  }
  return '/'
}

self.addEventListener('install', () => {
  // Activate immediately — there is nothing to precache.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  let payload = {}
  try {
    payload = event.data ? event.data.json() : {}
  } catch (err) {
    // A body we cannot parse still gets a notification: userVisibleOnly
    // contracts require one visible notification per push.
    payload = { title: 'LensCast', body: 'Detection event' }
  }
  const title = typeof payload.title === 'string' && payload.title ? payload.title : 'LensCast'
  const options = {
    body: typeof payload.body === 'string' ? payload.body : '',
    tag: typeof payload.tag === 'string' && payload.tag ? payload.tag : 'lenscast',
    icon: '/logo.svg',
    badge: '/logo.svg',
    data: { url: deepLinkUrl(payload) },
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  // Resolve relative targets ("/", "#/…") against this origin. A hash-only
  // target lands on the dashboard root plus the deep-link hash, which the
  // app's hash router maps onto tab/viewer state.
  const target = new URL(deepLinkUrl(event.notification.data), self.registration.scope).toString()
  event.waitUntil(
    (async () => {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      // Focus an open dashboard window if one exists, steering it to the
      // deep link first; otherwise open one straight at the target.
      for (const client of clientList) {
        if ('focus' in client) {
          await client.focus()
          // navigate() reloads the page, so only pay that when the open
          // window is not already showing the target URL.
          if (client.url !== target) {
            try {
              await client.navigate(target)
            } catch (err) {
              // Some browsers refuse navigation of a just-focused client —
              // the focused dashboard is still the right outcome.
            }
          }
          return
        }
      }
      await self.clients.openWindow(target)
    })(),
  )
})
