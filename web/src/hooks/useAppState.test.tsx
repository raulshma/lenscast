// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, renderHook } from '@solidjs/testing-library'
import { useAppState } from './useAppState'
import ExposureCard from '../components/ExposureCard'
import type { SettingsPatch } from '../api/client'
import type { AllSettings, CameraSettings } from '../types'

// The REAL useAppState is mounted — no module is mocked; only fetch is
// stubbed, behind a scriptable fake server below. The poll lanes are the
// hook's own createPollLadder under fake timers, so every scenario walks to
// the real tick that fires the lane it names.
//
// Issue #25: updateCamera debounces a camera edit by 400 ms, and every
// settings GET (the 30 s poll, the 3 s model-download lane, the visibility
// refresh) replaces the settings mirror wholesale. The flush used to read the
// values to PUT from that mirror at flush time, so a GET response landing
// inside the window sent the pre-edit value — or an empty body, where the
// server omits null keys. These tests pin the wire bodies instead: the flush
// sends the values the user set.

const DEBOUNCE_MS = 400 // createSectionedDebouncer's default

// ── wire fixtures ──
// Key set of a real device GET /api/settings while iso / exposureTime /
// focusDistance / colorTemperature / sceneMode are unset. The server
// serializes with Moshi (SettingsWebHandler.get → responseAdapter.toJson),
// which drops null fields: those keys are ABSENT on the wire, not null.
function wireCamera(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    exposureCompensation: 0, focusMode: 'AUTO', whiteBalance: 'AUTO', zoomRatio: 1.0,
    frameRate: 30, resolution: 'FHD_1080P', stabilization: true, hdrMode: 'OFF',
    nightVisionMode: 'OFF', outputRotation: 0, orientationLocked: false,
    ...extra,
  }
}

function doc(camera: Record<string, unknown>, mlModelState = 'not_downloaded'): AllSettings {
  return { camera, streaming: { mlModelState, recordingAudioEnabled: true } } as unknown as AllSettings
}

const BASE = () => doc(wireCamera())
const WITH_NULL_ISO = () => doc(wireCamera({ iso: null }))

/** The camera document handleResetDefaults writes — whole, explicit nulls included. */
const RESET_CAMERA: CameraSettings = {
  exposureCompensation: 0, iso: null, exposureTime: null,
  focusMode: 'AUTO', focusDistance: null, whiteBalance: 'AUTO',
  colorTemperature: null, zoomRatio: 1.0, frameRate: 30,
  resolution: 'FHD_1080P', stabilization: true, hdrMode: 'OFF', sceneMode: null,
  nightVisionMode: 'OFF', outputRotation: 0, orientationLocked: false,
}

// ── fake server + fetch ──
function jsonResponse(body: unknown): Response {
  const text = JSON.stringify(body)
  return { ok: true, status: 200, text: async () => text } as unknown as Response
}

const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v))

interface Held {
  startedAt: number
  released: boolean
  /** Answers the held request; a GET may answer with a substitute body. */
  release: (body?: unknown) => void
}

let server: AllSettings
let holdSettingsGets = false
let holdSettingsPuts = false
let heldGets: Held[] = []
let heldPuts: Held[] = []
let puts: Array<{ at: number; body: SettingsPatch }> = []
let calls: Array<{ at: number; method: string; url: string }> = []
let unexpected: string[] = []

/** A response the test holds back until `release()`, so it can land at a chosen moment. */
function holdResponse(held: Held[], answer: (body?: unknown) => Response): Promise<Response> {
  return new Promise<Response>((resolve) => {
    const entry: Held = {
      startedAt: Date.now(), released: false,
      release: (body) => { entry.released = true; resolve(answer(body)) },
    }
    held.push(entry)
  })
}

const fetchMock = async (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
  const url = typeof input === 'string' ? input : String(input)
  const method = (init.method ?? 'GET').toUpperCase()
  calls.push({ at: Date.now(), method, url })
  if (url === '/api/settings') {
    if (method === 'GET') {
      // A response can only carry what the server holds when the request is
      // handled — snapshotted at request time.
      const snapshot = clone(server)
      if (!holdSettingsGets) return jsonResponse(snapshot)
      return holdResponse(heldGets, (body) => jsonResponse(body ?? snapshot))
    }
    if (method === 'PUT') {
      const body = JSON.parse(String(init.body)) as SettingsPatch
      puts.push({ at: Date.now(), body })
      // SettingsWebHandler.put → mergeSection: sent keys overlay the stored
      // section, omitted keys keep their stored value. The write lands when
      // the request is handled; holding a PUT holds only its answer.
      for (const section of ['camera', 'streaming'] as const) {
        if (body[section] && typeof body[section] === 'object') {
          Object.assign(server[section] as object, body[section])
        }
      }
      if (!holdSettingsPuts) return jsonResponse({ success: true })
      return holdResponse(heldPuts, () => jsonResponse({ success: true }))
    }
  }
  switch (`${method} ${url}`) {
    case 'GET /api/auth/status': return jsonResponse({ required: false })
    case 'POST /api/auth/login': return jsonResponse({ success: true, role: 'admin' })
    case 'POST /api/auth/logout': return jsonResponse({ success: true })
    case 'GET /api/status':
      return jsonResponse({
        streaming: {
          isActive: false, url: '', webStreamingEnabled: false, webStreamingActive: false,
          clientCount: 0, audioEnabled: false, audioUrl: '', rtspEnabled: false,
          rtspStreamingActive: false, rtspUrl: '',
        },
      })
    case 'GET /api/camera/lenses': return jsonResponse({ lenses: [] })
    case 'GET /api/capture/interval/status': return jsonResponse({ isRunning: false, completedCaptures: 0 })
    case 'GET /api/recording/status':
      return jsonResponse({ isRecording: false, elapsedSeconds: 0, isScheduled: false, scheduledStartTimeMs: null })
    default:
      unexpected.push(`${method} ${url}`)
      return jsonResponse({ success: true })
  }
}

// jsdom ships no EventSource; the hook's sseHealthChecker dereferences
// EventSource.OPEN on the status lane's gate. The stub is never OPEN, which
// only means the status lane polls too — it does not touch the settings
// mirror. Every lane below that CAN touch it runs for real.
class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readonly readyState = 0
  onopen: ((ev: Event) => void) | null = null
  onerror: ((ev: Event) => void) | null = null
  addEventListener(): void {}
  close(): void {}
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.stubGlobal('fetch', fetchMock)
  server = BASE()
  holdSettingsGets = false
  holdSettingsPuts = false
  heldGets = []
  heldPuts = []
  puts = []
  calls = []
  unexpected = []
})

afterEach(async () => {
  cleanup()
  // Never leave a held request behind: its promise stays in the client's
  // request-dedupe map, and the next mount's request would coalesce onto it.
  for (const h of [...heldGets, ...heldPuts]) h.release()
  await settle()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

/** Lets the pending promise continuations and Solid effects run out. */
async function settle(): Promise<void> {
  for (let i = 0; i < 40; i++) await Promise.resolve()
}

type App = ReturnType<typeof useAppState>

async function mount(serverDoc: AllSettings = BASE()): Promise<App> {
  server = clone(serverDoc)
  const { result } = renderHook(() => useAppState())
  await settle()
  expect(result.authenticated()).toBe(true)
  expect(result.settings()).not.toBeNull()
  expect(heldGets).toEqual([])
  expect(puts).toEqual([])
  return result
}

const settingsGets = () => calls.filter((c) => c.method === 'GET' && c.url === '/api/settings').length
const putBodies = () => puts.map((p) => p.body)
const serverCamera = () => server.camera as unknown as Record<string, unknown>

/**
 * What the user is entitled to: the edit reaches the device AND the mirror
 * keeps showing it once the flush is done. `mirrorIso` is required — a
 * mirror left WITHOUT the key (the stale GET's real wire shape) is exactly
 * the state this must catch, so it can never be mistaken for "not checked".
 */
function expectEditSurvives(putList: SettingsPatch[], expectedIso: unknown, mirrorIso: unknown) {
  expect(putList).toEqual([{ camera: { iso: expectedIso } }])
  expect(serverCamera().iso).toBe(expectedIso)
  expect(mirrorIso, 'the settings mirror after the flush').toBe(expectedIso)
  expect(unexpected, 'unexpected fetch').toEqual([])
}

// ── the race, lane by lane ──
type Lane = 'none' | '30s' | '3s' | 'visibility'
type Ordering = 'get-first' | 'edit-first'

interface Drive {
  serverDoc?: AllSettings
  lane: Lane
  ordering?: Ordering
  /** No hold: fake ms of real ladder time to walk before the edit. */
  prewaitMs?: number
  /** edit-first: fake ms from the GET starting until its response lands. */
  rttMs?: number
  /** What the held GET answers (default: the server's document at request time). */
  response?: AllSettings
  /** get-first: a second edit applied after the GET response landed, same debounce window. */
  afterGet?: Partial<CameraSettings>
}

interface DriveResult {
  putList: SettingsPatch[]
  mirrorAfterFlush: unknown
}

/**
 * Where each lane sits on the ladder's fake clock, in ms after the mount:
 * the tick its GET fires on, and for edit-first runs the point 200 ms
 * earlier where the edit goes. The visibility refresh fires on demand.
 */
const LANE_CLOCK: Record<Lane, { editAtMs: number; firesAtMs: number | null }> = {
  'none': { editAtMs: 0, firesAtMs: null },
  '30s': { editAtMs: 29_800, firesAtMs: 30_000 },
  '3s': { editAtMs: 2_800, firesAtMs: 3_000 },
  'visibility': { editAtMs: 0, firesAtMs: null },
}

/**
 * Mounts the hook, sets iso to 800, and lands a stale settings GET inside
 * the 400 ms window through the lane named by `drive` — then walks to the
 * flush. `get-first` starts and holds the GET before the edit; `edit-first`
 * edits first and starts the GET 200 ms later. Either way the response
 * lands `rttMs` (default 50) after the GET started.
 */
async function drive(d: Drive): Promise<DriveResult> {
  const app = await mount(d.serverDoc ?? BASE())
  const clock = LANE_CLOCK[d.lane]
  const t0 = Date.now()
  let lastEditAt = 0
  const edit = (patch: Partial<CameraSettings>) => { app.updateCamera(patch); lastEditAt = Date.now() }
  const onlyTheMountGetSoFar = () => expect(settingsGets(), 'only the mount GET so far').toBe(1)
  /** Walks the real ladder to `ms` after the mount. */
  const walkTo = (ms: number) => vi.advanceTimersByTimeAsync(Math.max(0, t0 + ms - Date.now()))
  /** Fires the lane for real and leaves its GET held. */
  const startHeldGet = async () => {
    holdSettingsGets = true
    if (clock.firesAtMs === null) document.dispatchEvent(new Event('visibilitychange'))
    else await walkTo(clock.firesAtMs)
    expect(heldGets, 'the lane really started a settings GET').toHaveLength(1)
  }
  /** Lands the held GET and checks the premise: inside the window, nothing flushed yet. */
  const landStaleGet = async (rttMs: number) => {
    await vi.advanceTimersByTimeAsync(rttMs)
    heldGets[0].release(d.response)
    await settle()
    expect(Date.now(), 'the stale response landed inside the debounce window').toBeLessThan(lastEditAt + DEBOUNCE_MS)
    expect(puts, 'nothing was flushed before the stale response landed').toEqual([])
  }

  if (d.lane === 'none' || d.prewaitMs != null) {
    await walkTo(d.prewaitMs ?? 0)
    edit({ iso: 800 })
  } else if (d.ordering === 'get-first') {
    // Walk the real ladder to one tick before the lane fires.
    await walkTo((clock.firesAtMs ?? 0) - 1_000)
    onlyTheMountGetSoFar()
    await startHeldGet()
    edit({ iso: 800 })
    await landStaleGet(0)
    if (d.afterGet) edit(d.afterGet)
  } else {
    await walkTo(clock.editAtMs)
    onlyTheMountGetSoFar()
    edit({ iso: 800 })
    await startHeldGet()
    await landStaleGet(d.rttMs ?? 50)
  }

  await vi.advanceTimersByTimeAsync(Math.max(0, lastEditAt + DEBOUNCE_MS - Date.now()))
  await settle()
  return { putList: putBodies(), mirrorAfterFlush: app.settings()?.camera?.iso }
}

describe('useAppState.updateCamera — a settings GET response landing inside the debounce window', () => {
  it('C1 control: no settings GET after load — the flush PUTs the edited key', async () => {
    const r = await drive({ lane: 'none', prewaitMs: 0 })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('C2 control: the 30 s lane GET answered 200 ms before the edit — untouched', async () => {
    const r = await drive({ lane: 'none', prewaitMs: 30_200 })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('R1: GET first and held (30 s lane), edit, then the old response lands carrying iso:null', async () => {
    const r = await drive({ lane: '30s', ordering: 'get-first', response: WITH_NULL_ISO() })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('R2: GET first and held (30 s lane), edit, then the old response lands in the real wire shape (iso key absent)', async () => {
    const r = await drive({ lane: '30s', ordering: 'get-first' })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('R3: edit first, the 30 s lane GET starts 200 ms later and lands 50 ms after that', async () => {
    const r = await drive({ lane: '30s', ordering: 'edit-first' })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('R4: edit first, the 3 s model-download lane (mlModelState=downloading) GET lands inside the window', async () => {
    const r = await drive({
      lane: '3s', ordering: 'edit-first', serverDoc: doc(wireCamera(), 'downloading'),
    })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('R5: edit first, the visibilitychange refresh GET lands inside the window', async () => {
    const r = await drive({ lane: 'visibility', ordering: 'edit-first' })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('R6: the server already had iso 1600, the user sets 800, the 30 s lane GET lands inside the window', async () => {
    const r = await drive({ lane: '30s', ordering: 'edit-first', serverDoc: doc(wireCamera({ iso: 1600 })) })
    expectEditSurvives(r.putList, 800, r.mirrorAfterFlush)
  })

  it('Q1: iso, then the GET lands, then exposureCompensation in the same window — one PUT carries both keys', async () => {
    const r = await drive({
      lane: '30s', ordering: 'get-first', afterGet: { exposureCompensation: 2 },
    })
    expect(r.putList).toEqual([{ camera: { iso: 800, exposureCompensation: 2 } }])
    expect(r.mirrorAfterFlush).toBe(800)
    expect(serverCamera()).toMatchObject({ iso: 800, exposureCompensation: 2 })
    expect(unexpected, 'unexpected fetch').toEqual([])
  })

  it('V1: the classic ISO control — the real ExposureCard on the real hook, real 30 s lane', async () => {
    const app = await mount(BASE())
    render(() => <ExposureCard settings={app.settings} updateCamera={app.updateCamera} />)
    await settle()
    const select = () => document.getElementById('iso-mode-select') as HTMLSelectElement
    const input = () => document.getElementById('iso-value-input') as HTMLInputElement | null
    expect(select().value).toBe('auto')
    expect(input()).toBeNull()

    // 29.9 s: the user picks Manual (the number field opens on the manual
    // default), then types 1600 — distinct from that default.
    await vi.advanceTimersByTimeAsync(29_900)
    fireEvent.change(select(), { target: { value: 'manual' } })
    await vi.advanceTimersByTimeAsync(50)
    expect(select().value).toBe('manual')
    expect(input()!.value).toBe('800')
    fireEvent.change(input()!, { target: { value: '1600' } })
    await settle()
    const editAt = Date.now()

    // Tick 30: the settings lane's GET was answered before the edit existed
    // and lands inside the window.
    holdSettingsGets = true
    await vi.advanceTimersByTimeAsync(50)
    expect(heldGets).toHaveLength(1)
    heldGets[0].release()
    holdSettingsGets = false // later GETs (the next poll) are answered at once
    await vi.advanceTimersByTimeAsync(50)
    await settle()
    expect(Date.now()).toBeLessThan(editAt + DEBOUNCE_MS)
    expect(puts).toEqual([])

    await vi.advanceTimersByTimeAsync(Math.max(0, editAt + DEBOUNCE_MS - Date.now()))
    await settle()
    expectEditSurvives(putBodies(), 1600, app.settings()?.camera?.iso)
    expect(select().value, 'ISO mode after the flush').toBe('manual')
    expect(input()?.value, 'ISO value after the flush').toBe('1600')

    const getsBeforePoll = settingsGets()
    await vi.advanceTimersByTimeAsync(30_000) // the next 30 s-lane poll answers with the server's document
    await settle()
    // The poll must really have happened, otherwise this reading is meaningless.
    expect(settingsGets()).toBe(getsBeforePoll + 1)
    expect(select().value, 'ISO mode after the next poll').toBe('manual')
    expect(input()?.value, 'ISO value after the next poll').toBe('1600')
  })
})

/** Signs the just-signed-out hook back in and waits out the new session's first settings GET. */
async function signInAgain(app: App) {
  app.setLoginUser('admin')
  app.setLoginPass('secret')
  await app.handleLogin(new Event('submit'))
  await settle()
  expect(app.authenticated()).toBe(true)
  expect(app.settings()).not.toBeNull()
}

describe('useAppState camera save — what the staged patch must and must not carry', () => {
  it('I1: a key touched while a camera PUT is in flight is sent by the next flush, and the late answer of the older PUT changes nothing', async () => {
    const app = await mount(BASE())
    holdSettingsPuts = true
    app.updateCamera({ iso: 800 })
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await settle()
    expect(putBodies()).toEqual([{ camera: { iso: 800 } }])
    expect(heldPuts, 'the first PUT is still in flight').toHaveLength(1)

    // The same key again, plus a new one, while that PUT hangs.
    app.updateCamera({ iso: 1600 })
    app.updateCamera({ frameRate: 15 })
    expect(puts, 'nothing leaves before the window closes').toHaveLength(1)
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await settle()
    expect(putBodies()).toEqual([
      { camera: { iso: 800 } },
      { camera: { iso: 1600, frameRate: 15 } },
    ])

    // The older PUT answers last: its values are stale and must not pull the
    // control back off the newer edit.
    heldPuts[1].release()
    await settle()
    expect(app.settings()?.camera?.iso).toBe(1600)
    heldPuts[0].release()
    await settle()
    expect(app.settings()?.camera?.iso).toBe(1600)
    expect(app.settings()?.camera?.frameRate).toBe(15)
    expect(serverCamera()).toMatchObject({ iso: 1600, frameRate: 15 })
    expect(unexpected, 'unexpected fetch').toEqual([])
  })

  it('S1: a flush that finds the session gone sends nothing — its keys never reach the next session\'s first PUT', async () => {
    const app = await mount(BASE())
    app.updateCamera({ iso: 800, exposureCompensation: 3 })
    expect(app.settings()?.camera?.iso).toBe(800)
    await app.handleLogout()
    await settle()
    expect(app.authenticated()).toBe(false)
    // The window closes while signed out: this is the abandoned flush.
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await settle()
    expect(puts, 'the abandoned flush sent nothing').toEqual([])

    await signInAgain(app)
    app.updateCamera({ frameRate: 24 })
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await settle()
    expect(putBodies()).toEqual([{ camera: { frameRate: 24 } }])
    expect(unexpected, 'unexpected fetch').toEqual([])
  })

  it('S2: the staged patch dies with the session even when the next session edits before the abandoned window closes', async () => {
    const app = await mount(BASE())
    app.updateCamera({ iso: 800, exposureCompensation: 3 })
    await app.handleLogout()
    await settle()
    expect(app.authenticated()).toBe(false)
    // No timer has run: the abandoned window is still open, and the next
    // session's edit re-arms that very timer instead of opening a new one.
    await signInAgain(app)
    app.updateCamera({ frameRate: 24 })
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await settle()
    expect(putBodies()).toEqual([{ camera: { frameRate: 24 } }])
    expect(unexpected, 'unexpected fetch').toEqual([])
  })

  it('D1: Reset defaults supersedes an edit still inside its window — the reset stays the one whole-document PUT', async () => {
    const app = await mount(BASE())
    app.updateCamera({ iso: 800 })
    await app.handleResetDefaults()
    await settle()
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await settle()
    expect(putBodies()).toEqual([{ camera: RESET_CAMERA }])
    expect(app.settings()?.camera?.iso).toBeNull()
    expect(unexpected, 'unexpected fetch').toEqual([])
  })
})
