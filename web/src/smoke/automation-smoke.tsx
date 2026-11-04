/**
 * Dev-only smoke harness for the redesigned Automation card: mounts the card
 * standalone with a stubbed window.fetch that serves the contract fixture for
 * /api/automation and folds PUT bodies into the in-memory state. Open it on
 * the vite dev server at /src/smoke/automation.html — no device needed.
 * Not part of the build (only index.html is an entry) or the test run.
 */
import { render } from 'solid-js/web'
import type { AutomationStateDto } from '../types'
import fixture from '../../contract/automation.json'
import '../App.css'
import AutomationCard from '../components/AutomationCard'

const state = fixture as unknown as AutomationStateDto

// Controlled fire stamps: rule 1 ran earlier today, rule 2 two days ago, so
// the card's Today / Earlier "Last ran …" buckets are both visible.
const now = new Date()
const today = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 11, 58).getTime()
const twoDaysAgo = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 2, 22, 31).getTime()
state.rules[0].lastFiredAt = today
state.rules[1].lastFiredAt = twoDaysAgo

const realFetch = window.fetch.bind(window)
window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  if (url.includes('/api/automation')) {
    if (init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Pick<AutomationStateDto, 'enabled' | 'rules'>
      state.enabled = body.enabled
      state.rules = body.rules
      // A small latency so the saving banner and the save-keeps-editor-open
      // path are observable.
      await new Promise((resolve) => setTimeout(resolve, 300))
    }
    return new Response(JSON.stringify(JSON.parse(JSON.stringify(state))), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  return realFetch(input, init)
}

render(() => <AutomationCard />, document.getElementById('root')!)
