import { createSignal, onCleanup, Show } from 'solid-js'
import type { NextState } from '../NextState'
import { isDayArmed } from '../../../armDays'
import StatsPanel from './detection/StatsPanel'
import EventLog from './detection/EventLog'
import SecurityPanel from './detection/SecurityPanel'
import AutomationPanel from './detection/AutomationPanel'

/** How often the armed strip re-checks the clock (the window boundary). */
const ARM_TICK_MS = 30_000

/** Minute of day 0..1439 from the wall clock. */
function minuteOfDay(now: Date): number {
  return now.getHours() * 60 + now.getMinutes()
}

/**
 * Detection view — the surface leads with state. A signal-magenta tick
 * strip runs across the stats head while armed motion detection is
 * inside its live window (schedule-aware: motion on, and when the arm
 * schedule gates it, today armed between the hours); the surface is
 * quiet when idle. Below, the stats header spans the full width and
 * the arming panel takes the main column with the event log and
 * automation rules packed in a side column ≥1100px so toggle rows
 * don't stretch the full width.
 */
export default function DetectionView(props: { state: NextState }) {
  const state = props.state

  // The armed verdict rides the settings poll plus a minute ticker, so
  // the strip opens and closes with the clock, not just the poll.
  const [nowMinute, setNowMinute] = createSignal(minuteOfDay(new Date()))
  const armTick = setInterval(() => setNowMinute(minuteOfDay(new Date())), ARM_TICK_MS)
  onCleanup(() => clearInterval(armTick))

  const armedNow = () => {
    const stream = state.settings()?.streaming
    if (!stream?.motionDetectionEnabled) return false
    if (!stream.motionArmScheduleEnabled) return true
    const now = new Date()
    // Bit 0 = Monday … bit 6 = Sunday (the arm mask's ISO day order).
    if (!isDayArmed(stream.motionArmDaysMask, (now.getDay() + 6) % 7)) return false
    const minute = nowMinute()
    const start = stream.motionArmStartMinute
    const end = stream.motionArmEndMinute
    if (start === end) return true
    // A window may wrap midnight.
    return start < end ? minute >= start && minute < end : minute >= start || minute < end
  }

  return (
    <div class="nx-view">
      <div class="nx-view-inner">
        <div class="nx-detect-head" classList={{ 'nx-detect-armed': armedNow() }}>
          <div class="nx-arm-strip" aria-hidden="true" />
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
