// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@solidjs/testing-library'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defaultInspectorLayout } from '../../../lib/inspectorLayout'
import { en } from '../../../i18n/en'
import Panel from './Panel'
import InspectorPanelFrame from './InspectorPanelFrame'
import { SettingItem } from './SettingItem'

afterEach(cleanup)

describe('InspectorPanelFrame', () => {
  it('preserves the panel surface while applying title, collapse and item context', () => {
    const section = {
      ...defaultInspectorLayout().sections[2],
      collapsed: true,
      customTitle: 'Live channels',
      hiddenItems: ['storage.summary'],
    }
    const onToggleCollapsed = vi.fn()
    const onHide = vi.fn()
    const { container } = render(() => (
      <InspectorPanelFrame
        section={() => section}
        readOnly={() => false}
        onToggleCollapsed={onToggleCollapsed}
        onHide={onHide}
      >
        <Panel title="Original title">
          <SettingItem id="storage.summary"><span>Hidden status</span></SettingItem>
          <SettingItem id="storage.quota"><span>Visible row</span></SettingItem>
        </Panel>
      </InspectorPanelFrame>
    ))

    expect(screen.getByText('Live channels')).toBeTruthy()
    expect(screen.queryByText('Original title')).toBeNull()
    expect(screen.queryByText('Hidden status')).toBeNull()
    expect(screen.getByText('Visible row')).toBeTruthy()
    expect(container.querySelector('.nx-panel > .nx-panel-body')).not.toBeNull()
    expect(container.querySelector('.nx-inspector-frame-collapsed')).not.toBeNull()

    fireEvent.click(screen.getByRole('button', {
      name: en['next.inspector.section.expand'].replace('{title}', 'Live channels'),
    }))
    fireEvent.click(screen.getByRole('button', {
      name: en['next.inspector.section.hide'].replace('{title}', 'Live channels'),
    }))
    expect(onToggleCollapsed).toHaveBeenCalledTimes(1)
    expect(onHide).toHaveBeenCalledTimes(1)
  })

  it('shows the empty state when every registered item is hidden', () => {
    const section = {
      ...defaultInspectorLayout().sections[0],
      hiddenItems: ['channels.status'],
    }
    render(() => (
      <InspectorPanelFrame
        section={() => section}
        readOnly={() => false}
        emptyState={<span>Nothing pinned here</span>}
      >
        <Panel title="Channels"><span>Body</span></Panel>
      </InspectorPanelFrame>
    ))
    expect(screen.getByText('Nothing pinned here')).toBeTruthy()
    expect(screen.queryByText('Body')).toBeNull()
  })

  it('disables viewer actions and labels an empty custom panel', () => {
    const section = {
      ...defaultInspectorLayout().sections[0],
      id: 'custom_1',
      kind: 'custom' as const,
      sectionId: 'custom-custom_1',
      customTitle: '',
      items: [],
    }
    const { container } = render(() => (
      <InspectorPanelFrame section={() => section} readOnly={() => true} onToggleCollapsed={vi.fn()} onHide={vi.fn()}>
        <Panel title="Custom"><span>Body</span></Panel>
      </InspectorPanelFrame>
    ))
    expect(screen.getByText(en['next.inspector.configurator.pinnedControls'])).toBeTruthy()
    expect((screen.getByRole('button', {
      name: en['next.inspector.section.collapse'].replace('{title}', en['next.inspector.configurator.pinnedControls']),
    }) as HTMLButtonElement).disabled).toBe(true)
    expect(container.querySelector('.nx-panel-body')).toBeNull()
  })
})
