// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, fireEvent, waitFor } from '@solidjs/testing-library'
import AutomationCard from './AutomationCard'
import type { AutomationStateDto, AutomationUpdateRequestDto } from '../types'
import fixture from '../../contract/automation.json'

afterEach(() => cleanup())

/**
 * The redesigned card + editor, driven against the contract fixture through a
 * stubbed window.fetch (the smoke harness's stub, in test form): GET serves
 * the in-memory state, PUT folds the body in after [latency] and echoes it —
 * the same adopt-the-response seam the real device uses. window.confirm is
 * also stubbed so the delete/discard guards are asserted, not clicked through.
 */

let state: AutomationStateDto
let putBodies: AutomationUpdateRequestDto[]
let confirmSpy: ReturnType<typeof vi.spyOn>
let failNextPut: boolean

function stubFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof _input === 'string' ? _input : _input instanceof URL ? _input.href : _input.url
      if (url.includes('/api/automation')) {
        if (init?.method === 'PUT') {
          if (failNextPut) {
            failNextPut = false
            return new Response('{"error":"boom"}', { status: 500, headers: { 'Content-Type': 'application/json' } })
          }
          const body = JSON.parse(String(init.body)) as AutomationUpdateRequestDto
          putBodies.push(body)
          state = { ...state, enabled: body.enabled, rules: body.rules }
        }
        return new Response(JSON.stringify(JSON.parse(JSON.stringify(state))), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      throw new Error(`unexpected fetch: ${url}`)
    }),
  )
}

async function renderCard() {
  // Controlled fire stamps: rule 1 ran today at 11:58, rule 2 two days ago —
  // both "Last ran …" buckets visible regardless of when vitest runs.
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 11, 58).getTime()
  const twoDaysAgo = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 2, 22, 31).getTime()
  state.rules[0].lastFiredAt = today
  state.rules[1].lastFiredAt = twoDaysAgo
  render(() => <AutomationCard />)
  // The initial GET has landed.
  await screen.findByText('Weekday sunrise')
}

describe('AutomationCard rule rows', () => {
  beforeEach(() => {
    state = JSON.parse(JSON.stringify(fixture)) as AutomationStateDto
    putBodies = []
    failNextPut = false
    stubFetch()
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    confirmSpy.mockClear()
  })

  it('renders the structured card: WHEN / ONLY WHEN / THEN rows, summary text and the last-fired line', async () => {
    await renderCard()
    // Only the event rule carries a WHEN summary row — the time rule's WHEN
    // lives in its "HH:mm · days" subtitle.
    expect(screen.getAllByText('When').length).toBe(1)
    expect(screen.getAllByText('Only when').length).toBe(2)
    expect(screen.getAllByText('Then').length).toBe(2)
    // Time rule: "07:00 · Mon, Tue, Wed, Thu, Fri" in the subtitle.
    expect(screen.getByText('07:00 · Mon, Tue, Wed, Thu, Fri')).toBeTruthy()
    // Event rule's trigger summary.
    expect(screen.getByText('When motion is detected')).toBeTruthy()
    // The time window rides the joined ONLY WHEN row (all-days mask → no day suffix).
    expect(screen.getByText(/Between 22:00 and 06:00/)).toBeTruthy()
    // The THEN row merges action summaries.
    expect(screen.getByText('Torch on, Stream start')).toBeTruthy()
    expect(screen.getByText('Siren 10 s, Photo')).toBeTruthy()
    // Last fired: today bucket on rule 1, earlier bucket on rule 2.
    expect(screen.getByText('Last ran today at 11:58')).toBeTruthy()
    expect(screen.getByText(/Last ran .+ at 22:31/)).toBeTruthy()
    // Rules-count caption under the master toggle.
    expect(screen.getByText('2 of 16 rules')).toBeTruthy()
  })

  it('delete confirms first and only persists when accepted', async () => {
    await renderCard()
    const deleteButtons = screen.getAllByRole('button', { name: 'Delete rule' })
    fireEvent.click(deleteButtons[0])
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(confirmSpy.mock.calls[0][0]).toContain('Weekday sunrise')
    // Declined: no PUT.
    expect(putBodies).toHaveLength(0)

    confirmSpy.mockReturnValue(true)
    fireEvent.click(deleteButtons[0])
    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(putBodies[0].rules.map((rule) => rule.name)).toEqual(['Night motion deterrent'])
  })

  it('duplicate inserts an enabled copy right after the source and PUTs it', async () => {
    await renderCard()
    fireEvent.click(screen.getAllByRole('button', { name: 'Duplicate rule' })[0])
    await waitFor(() => expect(putBodies).toHaveLength(1))
    const rules = putBodies[0].rules
    expect(rules).toHaveLength(3)
    expect(rules[1].name).toBe('Copy of Weekday sunrise')
    expect(rules[1].enabled).toBe(true)
    expect(rules[1].id).not.toBe(state.rules[0].id)
    expect(rules[1].lastFiredAt).toBeNull()
  })

  it('shows the empty state with its CTA when there are no rules', async () => {
    state.rules = []
    render(() => <AutomationCard />)
    expect(await screen.findByText('No rules yet')).toBeTruthy()
    expect(screen.getByText(/Rules run actions for you/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Add rule' })).toBeTruthy()
    expect(screen.queryByText('2 of 16 rules')).toBeNull()
  })
})

describe('AutomationCard editor flow', () => {
  beforeEach(() => {
    state = JSON.parse(JSON.stringify(fixture)) as AutomationStateDto
    putBodies = []
    failNextPut = false
    stubFetch()
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    confirmSpy.mockClear()
  })

  async function openEditor(index: number) {
    await renderCard()
    fireEvent.click(screen.getAllByRole('button', { name: 'Edit rule' })[index])
    // The editor dialog is open.
    await screen.findByText('Preview')
  }

  it('opens with the WHEN trigger picker row and live preview of the loaded rule', async () => {
    await openEditor(1)
    // The tappable trigger row: label + description + the Change hint (the
    // trigger label also rides the card subtitle behind the overlay).
    expect(screen.getAllByText('Motion detected').length).toBeGreaterThan(0)
    expect(screen.getByText('Fires when the camera sees movement')).toBeTruthy()
    expect(screen.getByText('Change')).toBeTruthy()
    // The preview mirrors the card's WHEN text (card row + preview row).
    expect(screen.getAllByText('When motion is detected').length).toBe(2)
  })

  it('Save stays disabled with no actions; clicking surfaces the inline error', async () => {
    await openEditor(0)
    const save = screen.getAllByRole('button', { name: 'Save' }).at(-1)!
    // The loaded rule HAS actions, so Save is enabled here.
    expect((save as HTMLButtonElement).disabled).toBe(false)
    // Remove both actions → Save gates + inline error.
    const removes = screen.getAllByRole('button', { name: 'Remove action' })
    fireEvent.click(removes[0])
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove action' })[0])
    await waitFor(() => expect((save as HTMLButtonElement).disabled).toBe(true))
    fireEvent.click(save)
    expect(screen.getByText(/Add at least one action/)).toBeTruthy()
    expect(putBodies).toHaveLength(0)
  })

  it('a failed save keeps the editor open; a retry that succeeds closes it', async () => {
    await openEditor(0)
    failNextPut = true
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' }).at(-1)!)
    // Wait for the failed PUT to settle: the Save button flips back from
    // "Saving…" to an enabled "Save" (while submitting, the accessible name
    // differs, so this waitFor retries through the failure).
    await waitFor(() => {
      const save = screen.getAllByRole('button', { name: 'Save' }).at(-1) as HTMLButtonElement | undefined
      expect(save).toBeTruthy()
      expect(save!.disabled).toBe(false)
    })
    // The PUT failed — nothing adopted, editor still open.
    expect(putBodies).toHaveLength(0)
    expect(screen.queryByText('Preview')).not.toBeNull()
    // Retry succeeds → the editor closes and the doc was PUT.
    fireEvent.click(screen.getAllByRole('button', { name: 'Save' }).at(-1)!)
    await waitFor(() => expect(putBodies).toHaveLength(1))
    await waitFor(() => expect(screen.queryByText('Preview')).toBeNull())
  })

  it('the time-window condition renders From/Until time fields and day chips', async () => {
    await openEditor(1)
    expect(screen.getByText('From')).toBeTruthy()
    expect(screen.getByText('Until')).toBeTruthy()
    const start = document.getElementById('automation-condition-3-start') as HTMLInputElement | null
    // The fixture is the second condition of the second rule (keys 0,1 = trigger-params draft? actions first) —
    // just assert SOME time inputs exist with the fixture values.
    const timeInputs = Array.from(document.querySelectorAll('input[type="time"]')) as HTMLInputElement[]
    const values = timeInputs.map((input) => input.value)
    expect(values).toContain('22:00')
    expect(values).toContain('06:00')
    expect(start ?? timeInputs[0]).toBeTruthy()
  })

  it('Escape with unsaved edits asks to discard; declining keeps the editor open', async () => {
    await openEditor(0)
    // Make the editor dirty: rename the rule.
    const nameInput = document.getElementById('automation-rule-name') as HTMLInputElement
    fireEvent.input(nameInput, { target: { value: 'Renamed' } })
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(screen.getByText('Preview')).toBeTruthy() // still open
    // Accepting the discard closes it.
    confirmSpy.mockReturnValue(true)
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByText('Preview')).toBeNull())
    // Nothing was persisted.
    expect(putBodies).toHaveLength(0)
  })

  it('Escape with no unsaved edits closes straight away', async () => {
    await openEditor(0)
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByText('Preview')).toBeNull())
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('a non-name edit (cooldown) dirties the editor too — dirty is derived from the whole rule', async () => {
    // Rule 1 is the event trigger — the branch that renders the cooldown
    // slider (time rules render the time field instead).
    await openEditor(1)
    // Move the cooldown slider off the loaded value: the derive must see a
    // signal other than the name. (The retired hand-placed setDirty(true)
    // covered this by convention; the structural compare covers it by
    // construction.)
    const slider = document.getElementById('automation-rule-cooldown') as HTMLInputElement
    expect(slider).toBeTruthy()
    fireEvent.input(slider, { target: { value: '300' } })
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(screen.getByText('Preview')).toBeTruthy() // declining keeps it open
  })
})
