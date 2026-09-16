import { afterEach, describe, expect, it } from 'vitest'
import { getStatus } from './client'

// The 401 demotion contract: every non-2xx answer carries the HTTP status on
// the thrown Error, so callers can tell an auth failure (→ sign-in screen)
// from any other failure without sniffing message text. Regression for the
// mid-session auth bug: the server's 401 body carries an error string, which
// used to mask the status and left the dashboard polling behind a hidden
// login screen.

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

afterEach(() => {
  delete (globalThis as any).fetch
})

describe('api client error status', () => {
  it('a 401 with an error body still carries status 401', async () => {
    ;(globalThis as any).fetch = async () => jsonResponse(401, { error: 'Unauthorized' })
    const err = await getStatus().then(
      () => null,
      (e) => e,
    )
    expect(err).not.toBeNull()
    expect((err as any).status).toBe(401)
    expect((err as Error).message).toContain('Unauthorized')
  })

  it('a 401 without a body reads as authentication required', async () => {
    ;(globalThis as any).fetch = async () => jsonResponse(401, null)
    const err = await getStatus().then(
      () => null,
      (e) => e,
    )
    expect((err as Error).message).toContain('Authentication required')
    expect((err as any).status).toBe(401)
  })

  it('non-auth errors keep their own status', async () => {
    ;(globalThis as any).fetch = async () => jsonResponse(503, 'Too many viewers (max 8)')
    const err = await getStatus().then(
      () => null,
      (e) => e,
    )
    expect((err as any).status).toBe(503)
  })
})
