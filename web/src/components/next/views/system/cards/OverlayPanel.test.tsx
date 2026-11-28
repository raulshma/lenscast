// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render } from '@solidjs/testing-library'
import { createSignal, type Accessor } from 'solid-js'
import settingsFixture from '../../../../../../contract/settings.json'
import { en } from '../../../../../i18n/en'
import type { DeviceStatus, StreamingSettings } from '../../../../../types'
import { InspectorSectionContext } from '../../../ui/SettingItem'
import OverlayPanel from './OverlayPanel'

function mountPanel(initialStatus: Partial<DeviceStatus> | null = {
  illuminanceLux: 180,
  lightSensorAvailable: true,
}, readOnly: Accessor<boolean> = () => false) {
  const [streaming] = createSignal<StreamingSettings>({
    ...settingsFixture.streaming,
    overlayEnabled: true,
    showIlluminance: false,
  } as unknown as StreamingSettings)
  const [status, setStatus] = createSignal<Partial<DeviceStatus> | null>(initialStatus)
  const onUpdate = vi.fn((_patch: Partial<StreamingSettings>) => {})
  const view = render(() => (
    <InspectorSectionContext.Provider value={{ hiddenItems: () => [], readOnly }}>
      <OverlayPanel
        streaming={streaming}
        status={() => status() as DeviceStatus | null}
        onUpdate={onUpdate}
      />
    </InspectorSectionContext.Provider>
  ))
  return {
    view,
    onUpdate,
    setStatus,
    toggle: () => view.getByLabelText(en['overlay.illuminance']) as HTMLInputElement,
  }
}

afterEach(cleanup)

describe('OverlayPanel illuminance', () => {
  it('writes showIlluminance and updates the live reading reactively', () => {
    const panel = mountPanel({ illuminanceLux: 180, lightSensorAvailable: true })
    expect(panel.toggle().checked).toBe(false)
    fireEvent.click(panel.toggle())
    expect(panel.onUpdate).toHaveBeenCalledExactlyOnceWith({ showIlluminance: true })
    expect(panel.view.getByRole('status').textContent).toBe(en['overlay.illuminanceValue'].replace('{value}', '180'))

    panel.setStatus({ illuminanceLux: null, lightSensorAvailable: true })
    expect(panel.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])
  })

  it('waits on missing or older status and claims "no sensor" only on a known false', () => {
    // Silence is silence: no status yet, or a device too old for the pair,
    // renders "waiting" — never the absence claim.
    const panel = mountPanel(null)
    expect(panel.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])

    panel.setStatus({})
    expect(panel.view.getByRole('status').textContent).toBe(en['overlay.illuminanceWaiting'])

    // The known-false verdict is the one "unavailable" claim.
    panel.setStatus({ illuminanceLux: null, lightSensorAvailable: false })
    expect(panel.view.getByRole('note').textContent).toBe(en['overlay.illuminanceUnavailable'])

    // A measured reading outranks even a nonsense false verdict beside it.
    panel.setStatus({ illuminanceLux: 180, lightSensorAvailable: false })
    expect(panel.view.getByRole('status').textContent).toBe(en['overlay.illuminanceValue'].replace('{value}', '180'))
  })

  it('keeps the toggle disabled and emits no update in a read-only inspector', () => {
    const panel = mountPanel({ illuminanceLux: 180, lightSensorAvailable: true }, () => true)
    expect(panel.toggle().disabled).toBe(true)
    fireEvent.click(panel.toggle())
    expect(panel.onUpdate).not.toHaveBeenCalled()
  })
})
