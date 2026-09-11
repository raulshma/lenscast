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
 */

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
    data: { url: '/' },
  }
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const target = (event.notification.data && event.notification.data.url) || '/'
  event.waitUntil(
    (async () => {
      const clientList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      // Focus an open dashboard window if one exists; otherwise open the root.
      for (const client of clientList) {
        if ('focus' in client) {
          await client.focus()
          return
        }
      }
      await self.clients.openWindow(target)
    })(),
  )
})
