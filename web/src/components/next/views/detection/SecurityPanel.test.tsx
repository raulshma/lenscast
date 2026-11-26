// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@solidjs/testing-library'
import settingsFixture from '../../../../../contract/settings.json'
import { en } from '../../../../i18n/en'
import type { AllSettings, DeviceStatus } from '../../../../types'
import { InspectorSectionContext } from '../../ui/SettingItem'
import SecurityPanel from './SecurityPanel'

afterEach(() => cleanup())

function settings(): AllSettings {
  return {
    ...settingsFixture,
    streaming: {
      ...settingsFixture.streaming,
      motionDetectionEnabled: true,
      motionSensitivityPercent: 50,
    },
  } as unknown as AllSettings
}

function mount(readOnly = false, hiddenItems: readonly string[] = []) {
  const updateStreamingAndSave = vi.fn()
  const updateStreamingDebounced = vi.fn()
  const state = settings()
  render(() => (
    <InspectorSectionContext.Provider value={{ hiddenItems: () => hiddenItems, readOnly: () => readOnly }}>
      <SecurityPanel
        settings={() => state}
        status={() => null as DeviceStatus | null}
        updateStreamingAndSave={updateStreamingAndSave}
        updateStreamingDebounced={updateStreamingDebounced}
      />
    </InspectorSectionContext.Provider>
  ))
  return { updateStreamingAndSave, updateStreamingDebounced }
}

describe('SecurityPanel inspector bindings', () => {
  it('forwards toggles and debounced sliders to the shared settings handlers', () => {
    const { updateStreamingAndSave, updateStreamingDebounced } = mount()
    fireEvent.click(screen.getByRole('checkbox', { name: en['security.motion'] }))
    expect(updateStreamingAndSave).toHaveBeenCalledWith({ motionDetectionEnabled: false })

    fireEvent.input(screen.getByRole('slider', { name: en['security.sensitivity'] }), { target: { value: '72' } })
    expect(updateStreamingDebounced).toHaveBeenCalledWith({ motionSensitivityPercent: 72 })
  })

  it('disables and guards mutations in a read-only inspector', () => {
    const { updateStreamingAndSave, updateStreamingDebounced } = mount(true)
    const motion = screen.getByRole('checkbox', { name: en['security.motion'] }) as HTMLInputElement
    const sensitivity = screen.getByRole('slider', { name: en['security.sensitivity'] }) as HTMLInputElement
    expect(motion.disabled).toBe(true)
    expect(sensitivity.disabled).toBe(true)
    fireEvent.click(motion)
    fireEvent.input(sensitivity, { target: { value: '80' } })
    expect(updateStreamingAndSave).not.toHaveBeenCalled()
    expect(updateStreamingDebounced).not.toHaveBeenCalled()
  })

  it('suppresses hidden inspector items', () => {
    mount(false, ['security.motion', 'security.motionSensitivity'])
    expect(screen.queryByRole('checkbox', { name: en['security.motion'] })).toBeNull()
    expect(screen.queryByRole('slider', { name: en['security.sensitivity'] })).toBeNull()
  })
})
