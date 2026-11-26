// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import { InspectorSectionContext, SettingItem } from './SettingItem'

afterEach(cleanup)

describe('SettingItem', () => {
  it('renders children directly outside the configurable inspector', () => {
    const { container, getByText } = render(() => (
      <SettingItem id="exposure.iso"><span class="original-row">ISO</span></SettingItem>
    ))
    expect(getByText('ISO')).toBeTruthy()
    expect(container.firstElementChild?.className).toBe('original-row')
  })

  it('suppresses a hidden item without leaving a wrapper behind', () => {
    const { container, queryByText } = render(() => (
      <InspectorSectionContext.Provider value={{ hiddenItems: () => ['exposure.iso'], readOnly: () => false }}>
        <SettingItem id="exposure.iso"><span class="original-row">ISO</span></SettingItem>
      </InspectorSectionContext.Provider>
    ))
    expect(queryByText('ISO')).toBeNull()
    expect(container.childElementCount).toBe(0)
  })

  it('reacts when the placement hidden-item list changes', () => {
    const [hidden, setHidden] = createSignal<string[]>([])
    const { queryByText } = render(() => (
      <InspectorSectionContext.Provider value={{ hiddenItems: hidden, readOnly: () => false }}>
        <SettingItem id="exposure.iso"><span>ISO</span></SettingItem>
      </InspectorSectionContext.Provider>
    ))
    expect(queryByText('ISO')).toBeTruthy()
    setHidden(['exposure.iso'])
    expect(queryByText('ISO')).toBeNull()
    setHidden([])
    expect(queryByText('ISO')).toBeTruthy()
  })
})
