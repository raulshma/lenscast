// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, waitFor } from '@solidjs/testing-library'
import WatchdogCard from './WatchdogCard'
import systemFixture from '../../contract/system.json'

// Fix D (spec item 7): the crash-relaunch guard's and the alarm heartbeat's
// live states ride GET /api/system but no component rendered them — the
// reliability card now carries one compact status line each. The fetch stub
// serves the CONTRACT fixture, so this test and contract.test.ts read the
// same wire shape the device publishes.
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function stubSystem(body: object) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (url.includes('/api/system')) {
        return new Response(JSON.stringify(body), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
    }),
  )
}

function renderCard() {
  return render(() => (
    <WatchdogCard
      settings={() => null}
      status={() => null}
      updateStreamingAndSave={() => {}}
      updateStreamingDebounced={() => {}}
    />
  ))
}

describe('WatchdogCard reliability telemetry lines', () => {
  it('renders the crash guard and heartbeat status lines from GET /api/system', async () => {
    stubSystem(systemFixture)
    const { container } = renderCard()

    // The fixture's device: crashCount 1 / ARM, heartbeat armed with a SOFT verdict.
    await waitFor(() => {
      expect(container.textContent).toContain('Crash Relaunch Guard')
    })
    expect(container.textContent).toContain('1 · ARM')
    expect(container.textContent).toContain('Alarm Heartbeat')
    expect(container.textContent).toContain('Armed · 0 misses · SOFT')
  })

  it('warns on GIVE_UP — the guard has stopped arming', async () => {
    stubSystem({ ...systemFixture, crashRelaunch: { crashCount: 4, state: 'GIVE_UP' } })
    const { container } = renderCard()

    await waitFor(() => {
      expect(container.textContent).toContain('4 · GIVE_UP')
    })
    const value = [...container.querySelectorAll('.field-value')].find((el) =>
      el.textContent?.includes('GIVE_UP'),
    )
    expect(value).toBeDefined()
    expect(value!.getAttribute('style')).toContain('var(--lc-warning)')
  })

  it('hides both lines when the graph keeps the telemetry off the wire', async () => {
    stubSystem({ ...systemFixture, crashRelaunch: undefined, heartbeat: undefined })
    const { container } = renderCard()

    await waitFor(() => {
      expect(vi.mocked(fetch).mock.calls.length).toBeGreaterThan(0)
    })
    // Let any state settle, then assert the lines never appeared.
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(container.textContent).not.toContain('Crash Relaunch Guard')
    expect(container.textContent).not.toContain('Alarm Heartbeat')
  })
})
