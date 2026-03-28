import { createSignal, createEffect, onCleanup } from 'solid-js'
import * as api from './api/client'
import type {
  AllSettings, DeviceStatus, LensInfo, CameraSettings,
  FocusMode, WhiteBalance, Resolution, HdrMode,
} from './types'
import {
  RESOLUTION_LABELS, FOCUS_MODE_LABELS, WB_LABELS, HDR_LABELS, FRAME_RATE_OPTIONS,
} from './types'
import './App.css'

function App() {
  const [settings, setSettings] = createSignal<AllSettings | null>(null)
  const [status, setStatus] = createSignal<DeviceStatus | null>(null)
  const [lenses, setLenses] = createSignal<LensInfo[]>([])
  const [error, setError] = createSignal('')
  const [captureMsg, setCaptureMsg] = createSignal('')
  const [saving, setSaving] = createSignal(false)
  const [previewVisible, setPreviewVisible] = createSignal(true)
  const [streamActionLoading, setStreamActionLoading] = createSignal(false)
  const [streamNonce, setStreamNonce] = createSignal(0)

  let saveTimer: ReturnType<typeof setTimeout> | null = null

  function debounceSave(fn: () => void, ms = 400) {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(fn, ms)
  }

  async function fetchSettings() {
    try {
      const s = await api.getSettings()
      setSettings(s)
      setError('')
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function fetchStatus() {
    try {
      const s = await api.getStatus()
      setStatus(s)
    } catch (_) { }
  }

  async function fetchLenses() {
    try {
      const r = await api.getLenses()
      setLenses(r.lenses)
    } catch (_) { }
  }

  createEffect(() => {
    fetchSettings()
    fetchStatus()
    fetchLenses()
    const settingsInterval = setInterval(fetchSettings, 5000)
    const statusInterval = setInterval(fetchStatus, 3000)
    const lensesInterval = setInterval(fetchLenses, 10000)
    onCleanup(() => {
      clearInterval(settingsInterval)
      clearInterval(statusInterval)
      clearInterval(lensesInterval)
    })
  })

  async function saveSettings(partial: Partial<AllSettings>) {
    setSaving(true)
    try {
      await api.updateSettings(partial)
      setError('')
    } catch (e: any) {
      setError(e.message)
    }
    setSaving(false)
  }

  function updateCamera(patch: Partial<CameraSettings>) {
    const current = settings()
    if (!current) return
    const newCam = { ...current.camera, ...patch }
    setSettings({ ...current, camera: newCam })
    debounceSave(() => saveSettings({ camera: newCam }))
  }

  async function handleCapture() {
    setCaptureMsg('Capturing...')
    const result = await api.capturePhoto()
    setCaptureMsg(result.success ? `Captured: ${result.fileName}` : `Failed: ${result.error}`)
    setTimeout(() => setCaptureMsg(''), 4000)
  }

  async function handleSelectLens(index: number) {
    await api.selectLens(index)
    fetchLenses()
    fetchSettings()
  }

  async function handleResetDefaults() {
    const defaults: CameraSettings = {
      exposureCompensation: 0, iso: null, exposureTime: null,
      focusMode: 'AUTO', focusDistance: null, whiteBalance: 'AUTO',
      colorTemperature: null, zoomRatio: 1.0, frameRate: 30,
      resolution: 'FHD_1080P', stabilization: true, hdrMode: 'OFF', sceneMode: null,
    }
    await saveSettings({ camera: defaults })
    fetchSettings()
  }

  async function handleStopStream() {
    if (streamActionLoading()) return
    setStreamActionLoading(true)
    try {
      const result = await api.stopStream()
      if (!result.success) {
        setError(result.error || 'Failed to stop stream')
      } else {
        setError('')
        setPreviewVisible(false)
        setStreamNonce((v) => v + 1)
        await fetchStatus()
      }
    } catch (e: any) {
      setError(e.message || 'Failed to stop stream')
    } finally {
      setStreamActionLoading(false)
    }
  }

  async function handleResumeStream() {
    if (streamActionLoading()) return
    setStreamActionLoading(true)
    try {
      const result = await api.startStream()
      if (!result.success) {
        setError(result.error || 'Failed to resume stream')
      } else {
        setError('')
        setPreviewVisible(true)
        setStreamNonce((v) => v + 1)
        await fetchStatus()
      }
    } catch (e: any) {
      setError(e.message || 'Failed to resume stream')
    } finally {
      setStreamActionLoading(false)
    }
  }

  createEffect(() => {
    if (status()?.streaming?.isActive) {
      setPreviewVisible(true)
    }
  })

  const s = () => settings()
  const st = () => status()

  function fmtFocalLength(fl: number): string {
    if (fl <= 0) return ''
    return Number.isInteger(fl) ? `${fl}mm` : `${fl.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}mm`
  }

  return (
    <div class="app" data-theme="dark">
      {/* Navbar */}
      <div class="navbar bg-base-300 border-b border-base-200 px-4 min-h-12">
        <div class="flex-1 flex items-center gap-3">
          <span class="font-bold text-primary text-lg tracking-wide">LensCast</span>
          <span class="badge badge-ghost badge-sm font-mono">{st()?.camera || '...'}</span>
        </div>
        <div class="flex-none flex items-center gap-2">
          <div
            class="badge badge-outline gap-1"
            classList={{
              'badge-success': true,
              'badge-warning': st()?.battery?.isCharging,
            }}
          >
            <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
            {st()?.battery?.level ?? '--'}%
          </div>
          <div
            class="badge badge-outline"
            classList={{
              'badge-success': !st()?.thermal || st()?.thermal === 'NONE',
              'badge-warning': st()?.thermal === 'MODERATE' || st()?.thermal === 'LIGHT',
              'badge-error': st()?.thermal === 'SEVERE' || st()?.thermal === 'CRITICAL',
            }}
          >
            {st()?.thermal || '--'}
          </div>
          <div class="badge badge-outline badge-info">
            {st()?.streaming?.clientCount ?? 0} client{(st()?.streaming?.clientCount ?? 0) !== 1 ? 's' : ''}
          </div>
          <div
            class="badge badge-outline"
            classList={{
              'badge-warning': saving(),
              'badge-ghost': !saving(),
            }}
          >
            {saving() ? 'Saving...' : 'Saved'}
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div class="main-grid">
        {/* Preview Section */}
        <section class="preview-section">
          <div class="preview-container">
            {previewVisible() && st()?.streaming?.isActive ? (
              <img
                class="preview-img"
                src={`/stream?t=${streamNonce()}`}
                alt="Live camera feed"
                onError={() => setPreviewVisible(false)}
              />
            ) : (
              <div class="preview-placeholder">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                  <circle cx="12" cy="13" r="4" />
                </svg>
                <span class="text-sm">{st()?.streaming?.isActive ? 'Stream error' : 'Stream not active'}</span>
              </div>
            )}
          </div>
          <div class="flex items-center gap-2 flex-shrink-0">
            <button
              class="btn btn-primary btn-sm"
              onClick={handleCapture}
              disabled={!st()?.streaming?.isActive}
            >
              Capture Photo
            </button>
            {st()?.streaming?.isActive ? (
              <button
                class="btn btn-warning btn-sm btn-outline"
                onClick={handleStopStream}
                disabled={streamActionLoading()}
              >
                {streamActionLoading() ? (
                  <span class="loading loading-spinner loading-xs"></span>
                ) : null}
                {streamActionLoading() ? 'Stopping...' : 'Stop Stream'}
              </button>
            ) : (
              <button
                class="btn btn-success btn-sm btn-outline"
                onClick={handleResumeStream}
                disabled={streamActionLoading()}
              >
                {streamActionLoading() ? (
                  <span class="loading loading-spinner loading-xs"></span>
                ) : null}
                {streamActionLoading() ? 'Starting...' : 'Resume Stream'}
              </button>
            )}
            <a class="btn btn-ghost btn-sm" href="/snapshot" target="_blank" download>
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
              </svg>
              Snapshot
            </a>
            {captureMsg() && (
              <span class="text-xs text-success animate-pulse">{captureMsg()}</span>
            )}
          </div>
          {st()?.streaming?.isActive && st()?.streaming?.url && (
            <div class="bg-base-300 rounded-lg px-3 py-2 border border-base-200 flex-shrink-0">
              <code class="text-xs text-primary font-mono">{st()!.streaming.url}</code>
            </div>
          )}
        </section>

        {/* Settings Panel */}
        <section class="settings-scroll">
          {error() && (
            <div class="alert alert-error alert-soft text-sm py-2">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{error()}</span>
            </div>
          )}

          {/* Lens Selection */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Lens</h3>
              <div class="lens-grid">
                {lenses().map((lens) => (
                  <button
                    class="btn btn-sm flex-col h-auto py-2"
                    classList={{
                      'btn-primary': lens.selected,
                      'btn-ghost border border-base-300': !lens.selected,
                    }}
                    onClick={() => handleSelectLens(lens.index)}
                  >
                    <span class="font-semibold text-xs">{lens.label}</span>
                    <span class="text-[10px] opacity-70">{fmtFocalLength(lens.focalLength)}</span>
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Exposure */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Exposure</h3>
              <div class="form-control">
                <label class="label py-1">
                  <span class="label-text text-xs">Exposure Compensation</span>
                  <span class="badge badge-primary badge-xs font-mono">
                    {s()?.camera?.exposureCompensation ?? 0}
                  </span>
                </label>
                <input
                  type="range"
                  class="range range-primary range-xs"
                  min={-12}
                  max={12}
                  value={s()?.camera?.exposureCompensation ?? 0}
                  onInput={(e) => updateCamera({ exposureCompensation: parseFloat(e.currentTarget.value) })}
                />
              </div>
              <div class="form-control mt-2">
                <label class="label py-1">
                  <span class="label-text text-xs">ISO</span>
                </label>
                <div class="flex gap-2">
                  <select
                    class="select select-bordered select-sm w-24"
                    value={s()?.camera?.iso == null ? 'auto' : 'manual'}
                    onChange={(e) => {
                      if (e.currentTarget.value === 'auto') {
                        updateCamera({ iso: null })
                      } else {
                        updateCamera({ iso: s()?.camera?.iso ?? 800 })
                      }
                    }}
                  >
                    <option value="auto">Auto</option>
                    <option value="manual">Manual</option>
                  </select>
                  {s()?.camera?.iso != null && (
                    <input
                      type="number"
                      class="input input-bordered input-sm flex-1"
                      value={s()?.camera?.iso ?? ''}
                      min={100}
                      max={32000}
                      onChange={(e) => updateCamera({ iso: parseInt(e.currentTarget.value) || null })}
                    />
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* Focus */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Focus</h3>
              <div class="form-control">
                <label class="label py-1">
                  <span class="label-text text-xs">Focus Mode</span>
                </label>
                <select
                  class="select select-bordered select-sm"
                  value={s()?.camera?.focusMode ?? 'AUTO'}
                  onChange={(e) => updateCamera({ focusMode: e.currentTarget.value as FocusMode })}
                >
                  {Object.entries(FOCUS_MODE_LABELS).map(([k, v]) => (
                    <option value={k}>{v}</option>
                  ))}
                </select>
              </div>
              {s()?.camera?.focusMode === 'MANUAL' && (
                <div class="form-control mt-2">
                  <label class="label py-1">
                    <span class="label-text text-xs">Focus Distance</span>
                    <span class="badge badge-primary badge-xs font-mono">
                      {(s()?.camera?.focusDistance ?? 0).toFixed(1)}
                    </span>
                  </label>
                  <input
                    type="range"
                    class="range range-primary range-xs"
                    min={0}
                    max={10}
                    step={0.1}
                    value={s()?.camera?.focusDistance ?? 0}
                    onInput={(e) => updateCamera({ focusDistance: parseFloat(e.currentTarget.value) })}
                  />
                </div>
              )}
            </div>
          </div>

          {/* White Balance */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">White Balance</h3>
              <div class="form-control">
                <label class="label py-1">
                  <span class="label-text text-xs">White Balance</span>
                </label>
                <select
                  class="select select-bordered select-sm"
                  value={s()?.camera?.whiteBalance ?? 'AUTO'}
                  onChange={(e) => updateCamera({ whiteBalance: e.currentTarget.value as WhiteBalance })}
                >
                  {Object.entries(WB_LABELS).map(([k, v]) => (
                    <option value={k}>{v}</option>
                  ))}
                </select>
              </div>
              {s()?.camera?.whiteBalance === 'MANUAL' && (
                <div class="form-control mt-2">
                  <label class="label py-1">
                    <span class="label-text text-xs">Color Temperature</span>
                    <span class="badge badge-primary badge-xs font-mono">
                      {s()?.camera?.colorTemperature ?? 5500}K
                    </span>
                  </label>
                  <input
                    type="range"
                    class="range range-primary range-xs"
                    min={2000}
                    max={9000}
                    step={100}
                    value={s()?.camera?.colorTemperature ?? 5500}
                    onInput={(e) => updateCamera({ colorTemperature: parseFloat(e.currentTarget.value) })}
                  />
                </div>
              )}
            </div>
          </div>

          {/* Zoom & Frame */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Zoom & Frame</h3>
              <div class="form-control">
                <label class="label py-1">
                  <span class="label-text text-xs">Zoom</span>
                  <span class="badge badge-primary badge-xs font-mono">
                    {(s()?.camera?.zoomRatio ?? 1).toFixed(1)}x
                  </span>
                </label>
                <input
                  type="range"
                  class="range range-primary range-xs"
                  min={1}
                  max={10}
                  step={0.1}
                  value={s()?.camera?.zoomRatio ?? 1}
                  onInput={(e) => updateCamera({ zoomRatio: parseFloat(e.currentTarget.value) })}
                />
              </div>
              <div class="form-control mt-2">
                <label class="label py-1">
                  <span class="label-text text-xs">Frame Rate</span>
                </label>
                <select
                  class="select select-bordered select-sm"
                  value={s()?.camera?.frameRate ?? 30}
                  onChange={(e) => updateCamera({ frameRate: parseInt(e.currentTarget.value) })}
                >
                  {FRAME_RATE_OPTIONS.map((r) => (
                    <option value={r}>{r} fps</option>
                  ))}
                </select>
              </div>
              <div class="form-control mt-2">
                <label class="label py-1">
                  <span class="label-text text-xs">Resolution</span>
                </label>
                <select
                  class="select select-bordered select-sm"
                  value={s()?.camera?.resolution ?? 'FHD_1080P'}
                  onChange={(e) => updateCamera({ resolution: e.currentTarget.value as Resolution })}
                >
                  {Object.entries(RESOLUTION_LABELS).map(([k, v]) => (
                    <option value={k}>{v}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          {/* Effects */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Effects</h3>
              <div class="form-control">
                <label class="label cursor-pointer py-1">
                  <span class="label-text text-xs">Stabilization</span>
                  <input
                    type="checkbox"
                    class="toggle toggle-primary toggle-sm"
                    checked={s()?.camera?.stabilization ?? true}
                    onChange={() => updateCamera({ stabilization: !(s()?.camera?.stabilization ?? true) })}
                  />
                </label>
              </div>
              <div class="form-control mt-2">
                <label class="label py-1">
                  <span class="label-text text-xs">HDR</span>
                </label>
                <select
                  class="select select-bordered select-sm"
                  value={s()?.camera?.hdrMode ?? 'OFF'}
                  onChange={(e) => updateCamera({ hdrMode: e.currentTarget.value as HdrMode })}
                >
                  {Object.entries(HDR_LABELS).map(([k, v]) => (
                    <option value={k}>{v}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          {/* Streaming */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Streaming</h3>
              <div class="form-control">
                <label class="label py-1">
                  <span class="label-text text-xs">JPEG Quality</span>
                  <span class="badge badge-primary badge-xs font-mono">
                    {s()?.streaming?.jpegQuality ?? 80}%
                  </span>
                </label>
                <input
                  type="range"
                  class="range range-primary range-xs"
                  min={10}
                  max={100}
                  step={5}
                  value={s()?.streaming?.jpegQuality ?? 80}
                  onInput={(e) => {
                    const v = parseInt(e.currentTarget.value)
                    const current = settings()
                    if (current) {
                      setSettings({ ...current, streaming: { ...current.streaming, jpegQuality: v } })
                    }
                    debounceSave(() => saveSettings({ streaming: { jpegQuality: v } }))
                  }}
                />
              </div>
              <div class="form-control mt-2">
                <label class="label cursor-pointer py-1">
                  <span class="label-text text-xs">Show Preview on Device</span>
                  <input
                    type="checkbox"
                    class="toggle toggle-primary toggle-sm"
                    checked={s()?.streaming?.showPreview ?? true}
                    onChange={() => {
                      const newVal = !(s()?.streaming?.showPreview ?? true)
                      const current = settings()
                      if (current) {
                        setSettings({ ...current, streaming: { ...current.streaming, showPreview: newVal } })
                      }
                      saveSettings({ streaming: { showPreview: newVal } })
                    }}
                  />
                </label>
              </div>
            </div>
          </div>

          {/* Authentication */}
          <div class="card bg-base-200 shadow-sm">
            <div class="card-body p-4">
              <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Authentication</h3>
              <div class="form-control">
                <label class="label cursor-pointer py-1">
                  <span class="label-text text-xs">Enable Auth</span>
                  <input
                    type="checkbox"
                    class="toggle toggle-primary toggle-sm"
                    checked={s()?.auth?.enabled ?? false}
                    onChange={() => {
                      const newVal = !(s()?.auth?.enabled ?? false)
                      const current = settings()
                      if (current) {
                        setSettings({ ...current, auth: { ...current.auth, enabled: newVal } })
                      }
                      saveSettings({ auth: { enabled: newVal } })
                    }}
                  />
                </label>
              </div>
              {s()?.auth?.enabled && (
                <>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Username</span>
                    </label>
                    <input
                      type="text"
                      class="input input-bordered input-sm"
                      value={s()?.auth?.username ?? ''}
                      onChange={(e) => {
                        const v = e.currentTarget.value
                        debounceSave(() => saveSettings({ auth: { username: v } }))
                      }}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Password</span>
                    </label>
                    <input
                      type="password"
                      class="input input-bordered input-sm"
                      placeholder="Enter new password"
                      onChange={(e) => {
                        const v = e.currentTarget.value
                        if (v) {
                          saveSettings({ auth: { password: v } })
                        }
                      }}
                    />
                  </div>
                </>
              )}
            </div>
          </div>

          {/* Actions */}
          <div class="flex justify-end">
            <button class="btn btn-error btn-outline btn-sm" onClick={handleResetDefaults}>
              Reset to Defaults
            </button>
          </div>
        </section>
      </div>
    </div>
  )
}

export default App