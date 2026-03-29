import { createSignal, createEffect, onCleanup } from 'solid-js'

function formatDuration(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  const mm = m.toString().padStart(2, '0')
  const ss = s.toString().padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

export function createRecordingTimer(
  isRecording: () => boolean,
  serverElapsed: () => number,
) {
  const [elapsed, setElapsed] = createSignal(0)
  let intervalId: ReturnType<typeof setInterval> | null = null
  let baseSeconds = 0
  let baseTimestamp = 0

  function start(fromSeconds: number) {
    stop()
    baseSeconds = fromSeconds
    baseTimestamp = Date.now()
    setElapsed(fromSeconds)
    intervalId = setInterval(() => {
      setElapsed(baseSeconds + Math.floor((Date.now() - baseTimestamp) / 1000))
    }, 1000)
  }

  function stop() {
    if (intervalId !== null) {
      clearInterval(intervalId)
      intervalId = null
    }
  }

  createEffect(() => {
    if (isRecording()) {
      start(serverElapsed())
    } else {
      stop()
      setElapsed(0)
    }
  })

  const onVisibilityChange = () => {
    if (document.hidden) {
      stop()
    } else if (isRecording()) {
      start(elapsed())
    }
  }

  document.addEventListener('visibilitychange', onVisibilityChange)
  onCleanup(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return { elapsed, formatElapsed: () => formatDuration(elapsed()) }
}
