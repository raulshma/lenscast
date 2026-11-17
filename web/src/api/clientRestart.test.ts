import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetCamera, restartApp } from './client'
import type { SystemRestartResponse } from '../types'

// The typed restart contract (blueprint item 8): restartApp must hand the
// parsed SystemRestartResponse through untouched — the success shape stays
// the bare `{"success":true}` and the typed refusal (`success:false` +
// reason/hint, answered BEFORE the device quits) reaches the caller so the
// button can render the failure instead of assuming a restart. The old
// client erased this distinction by typing the result as `{ success:
// boolean }` and letting both components read any 200 as "restarting".
//
// Wave 4 (blueprint item 9) adds the soft mode: the request rides the
// ?mode=soft query, and because the ladder recreates the listener socket
// mid-flight the response can be dropped even on success — so a dropped
// connection switches to polling GET /api/system until the re-armed server
// answers (the deep restart's recover-by-polling pattern, made explicit).
// Wave 4 (item 10) adds resetCamera, the typed camera-only lever.

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

afterEach(() => {
  delete (globalThis as any).fetch
  vi.useRealTimers()
})

describe('restartApp typed result', () => {
  it('the success answer parses to success:true without reason or hint', async () => {
    ;(globalThis as any).fetch = async () => jsonResponse({ success: true })
    const result: SystemRestartResponse = await restartApp()
    expect(result).toEqual({ success: true })
    expect(result.reason).toBeUndefined()
    expect(result.hint).toBeUndefined()
  })

  it('the typed refusal parses with its machine-readable reason and device hint', async () => {
    ;(globalThis as any).fetch = async () =>
      jsonResponse({
        success: false,
        reason: 'exact_alarm_not_granted',
        hint: 'Grant LensCast the Alarms & reminders permission in the device settings, then retry the restart.',
      })
    const result = await restartApp()
    expect(result.success).toBe(false)
    expect(result.reason).toBe('exact_alarm_not_granted')
    expect(result.hint).toContain('Alarms & reminders')
  })
})

describe('restartApp soft mode (blueprint item 9)', () => {
  it('rides the ?mode=soft query and hands the answer through', async () => {
    const urls: string[] = []
    ;(globalThis as any).fetch = async (input: string) => {
      urls.push(input)
      return jsonResponse({ success: true, mode: 'soft' })
    }
    const result = await restartApp('soft')
    expect(urls).toEqual(['/api/system/restart?mode=soft'])
    expect(result).toEqual({ success: true, mode: 'soft' })
  })

  it('the deep mode keeps the bare route and lets a dropped connection propagate', async () => {
    const urls: string[] = []
    ;(globalThis as any).fetch = async (input: string) => {
      urls.push(input)
      throw new Error('connection reset')
    }
    await expect(restartApp('process')).rejects.toThrow('connection reset')
    expect(urls).toEqual(['/api/system/restart?mode=process'])
  })

  it('a dropped soft response recovers by polling /api/system until the server answers', async () => {
    vi.useFakeTimers()
    const seen: string[] = []
    let systemCalls = 0
    ;(globalThis as any).fetch = async (input: string) => {
      seen.push(input)
      if (input === '/api/system/restart?mode=soft') throw new Error('connection reset')
      // First poll: the server is still re-arming. Second poll: it answers.
      if (input === '/api/system') {
        systemCalls++
        if (systemCalls === 1) throw new Error('still down')
        return jsonResponse({ appVersion: 'x' })
      }
      throw new Error(`unexpected fetch ${input}`)
    }

    const promise = restartApp('soft')
    await vi.advanceTimersByTimeAsync(2_000)
    const result = await promise

    expect(seen).toContain('/api/system/restart?mode=soft')
    expect(systemCalls).toBe(2)
    expect(result).toEqual({ success: true, mode: 'soft' })
  })

  it('a device that never answers inside the window yields the typed soft failure', async () => {
    vi.useFakeTimers()
    ;(globalThis as any).fetch = async (input: string) => {
      throw new Error(`unreachable (${input})`)
    }

    const promise = restartApp('soft')
    await vi.advanceTimersByTimeAsync(31_000)
    const result = await promise

    expect(result.success).toBe(false)
    expect(result.mode).toBe('soft')
    expect(result.reason).toBe('soft_restart_failed')
    expect(result.step).toBe('startServer')
  })
})

describe('resetCamera (blueprint item 10)', () => {
  it('posts the reset route and hands the typed verdict through', async () => {
    const urls: string[] = []
    ;(globalThis as any).fetch = async (input: string) => {
      urls.push(input)
      return jsonResponse({ success: true, verdict: 'REBIND' })
    }
    const result = await resetCamera()
    expect(urls).toEqual(['/api/system/reset-camera'])
    expect(result).toEqual({ success: true, verdict: 'REBIND' })
  })

  it('surfaces the policy refusal verbatim (REBOOT_REQUIRED stays REBOOT_REQUIRED)', async () => {
    ;(globalThis as any).fetch = async () =>
      jsonResponse({
        success: false,
        verdict: 'REBOOT_REQUIRED',
        reason: 'reboot_required',
        hint: 'Camera fatal error — reboot the device.',
      })
    const result = await resetCamera()
    expect(result.success).toBe(false)
    expect(result.verdict).toBe('REBOOT_REQUIRED')
    expect(result.reason).toBe('reboot_required')
  })
})
