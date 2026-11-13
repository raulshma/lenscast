import { Show } from 'solid-js'
import type { NextState } from '../NextState'
import StatsPanel from './detection/StatsPanel'
import EventLog from './detection/EventLog'
import SecurityPanel from './detection/SecurityPanel'
import AutomationPanel from './detection/AutomationPanel'

/**
 * Detection view — the ops dashboard. A full-width statistics header
 * (window toggle + counters + seven-day bars), then the arming panel in
 * the main column with the live event log and automation rules packed in
 * a side column ≥1100px so toggle rows don't stretch the full width.
 */
export default function DetectionView(props: { state: NextState }) {
  const state = props.state

  return (
    <div class="nx-view">
      <div class="nx-view-inner">
        <div class="nx-detect-head">
          <StatsPanel />
        </div>
        <div class="nx-detection-grid">
          <Show when={state.settings()?.streaming}>
            <SecurityPanel
              settings={state.settings}
              status={state.status}
              updateStreamingAndSave={state.updateStreamingAndSave}
              updateStreamingDebounced={state.updateStreamingDebounced}
            />
          </Show>
          <div class="nx-detection-side">
            <EventLog readOnly={state.isViewer} />
            <AutomationPanel readOnly={state.isViewer} />
          </div>
        </div>
      </div>
    </div>
  )
}
