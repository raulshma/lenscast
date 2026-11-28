// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../contract/settings.json'
import { en } from '../i18n/en'
import type { DeviceStatus, StreamingSettings } from '../types'
import OverlayCard from './OverlayCard'

function mountCard(initialStatus: Partial<DeviceStatus> | null = {
  illuminanceLux: 180,
  lightSensorAvailable: true,
}) {
  const [streaming] = createSignal<StreamingSettings>({
    ...settingsFixture.streaming,
    overlayEnabled: true,
    showIlluminance: false,
  } as unknown as StreamingSettings)
  const [status, setStatus] = createSignal<Partial<DeviceStatus> | null>(initialStatus)
  const onUpdate = vi.fn((_patch: Partial<StreamingSettings>) => {})
  const view = render(() => (
    <OverlayCard
      streaming={streaming}
      status={() => status() as DeviceStatus | null}
      onUpdate={onUpdate}
    />
  ))
  return {
    view,
    onUpdate,
    setStatus,
    toggle: () => view.getByLabelText(en['overlay.illuminance']) as HTMLInputElement,
  }
}

afterEach(cleanup)

describe('OverlayCard illuminance', () => {
  it('defaults the overlay toggle off and writes showIlluminance', () => {
    const card = mountCard()
    expect(card.toggle().checked).toBe(false)
    fireEvent.click(card.toggle())
    expect(card.onUpdate).toHaveBeenCalledExactlyOnceWith({ showIlluminance: true })
  })

  it('shows a live lux reading and honestly waits when it becomes unknown', () => {
    const card = mountCard({ illuminanceLux: 180, lightSensorAvailable: true })
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceValue'].replace('{value}', '180'))

    card.setStatus({ illuminanceLux: null, lightSensorAvailable: true })
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])

    card.setStatus({ illuminanceLux: 0, lightSensorAvailable: true })
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceValue'].replace('{value}', '0'))

    for (const invalid of [-1, Number.NaN, Number.POSITIVE_INFINITY]) {
      card.setStatus({ illuminanceLux: invalid, lightSensorAvailable: true })
      expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])
    }
  })

  it('waits on missing or older status and claims "no sensor" only on a known false', () => {
    // Silence is silence: no status yet, or a device too old for the pair,
    // renders "waiting" — never the absence claim.
    const card = mountCard(null)
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])

    card.setStatus({})
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])

    card.setStatus({ illuminanceLux: null, lightSensorAvailable: undefined })
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])

    // The known-false verdict is the one "unavailable" claim.
    card.setStatus({ illuminanceLux: null, lightSensorAvailable: false })
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceUnavailable'])

    // A measured reading outranks even a nonsense false verdict beside it.
    card.setStatus({ illuminanceLux: 180, lightSensorAvailable: false })
    expect(card.view.getByRole('status').textContent).toBe(en['overlay.illuminanceValue'].replace('{value}', '180'))
  })
})
