// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import StatusChip from './StatusChip'

// The badge slot is the only SettingsCard API the redesign added: the chip
// mounts inside the header when passed, and the header carries no trace of
// it otherwise (the classic shell depends on the DOM staying as it was).
afterEach(() => cleanup())

describe('SettingsCard badge slot', () => {
  it('renders the StatusChip in the header when passed', () => {
    render(() => (
      <SettingsCard
        icon={<span>i</span>}
        title="Module"
        badge={<StatusChip tone="danger" label="REC" pulse />}
      >
        <p>body</p>
      </SettingsCard>
    ))
    const badge = document.querySelector('.settings-card-badge')!
    expect(badge.getAttribute('data-tone')).toBe('danger')
    expect(badge.getAttribute('data-pulse')).toBe('true')
    expect(badge.querySelector('.settings-card-badge-label')!.textContent).toBe('REC')
    // Lives in the header, not the body.
    expect(badge.closest('.settings-card-header')).toBeTruthy()
  })

  it('renders no badge element when the prop is omitted', () => {
    render(() => (
      <SettingsCard icon={<span>i</span>} title="Module">
        <p>body</p>
      </SettingsCard>
    ))
    expect(document.querySelector('.settings-card-badge')).toBeNull()
  })

  it('omits the badge entirely when its Show gate closes', () => {
    render(() => (
      <SettingsCard
        icon={<span>i</span>}
        title="Module"
        badge={<Show when={false}><StatusChip tone="success" label="LIVE" /></Show>}
      >
        <p>body</p>
      </SettingsCard>
    ))
    expect(document.querySelector('.settings-card-badge')).toBeNull()
  })
})
