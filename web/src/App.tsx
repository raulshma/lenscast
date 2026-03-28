import { createSignal, createEffect, onCleanup, JSX } from 'solid-js'
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

  const Select = (props: {
    label: string
    value: string
    options: Record<string, string>
    onChange: (v: string) => void
  }) => (
    <div class="field">
      <label>{props.label}</label>
      <select
        value={props.value}
        onChange={(e) => props.onChange(e.currentTarget.value)}
      >
        {Object.entries(props.options).map(([k, v]) => (
          <option value={k}>{v}</option>
        ))}
      </select>
    </div>
  )

  const Range = (props: {
    label: string
    value: number
    min: number
    max: number
    step?: number
    unit?: string
    onChange: (v: number) => void
  }) => (
    <div class="field">
      <label>
        {props.label}
        <span class="value-badge">
          {props.value}{props.unit || ''}
        </span>
      </label>
      <input
        type="range"
        min={props.min}
        max={props.max}
        step={props.step || 1}
        value={props.value}
        onInput={(e) => props.onChange(parseFloat(e.currentTarget.value))}
      />
    </div>
  )

  const s = () => settings()
  const st = () => status()

  return (
    <div class="app">
      <header class="header">
        <div class="header-left">
          <h1>LensCast</h1>
          <span class="camera-state">{st()?.camera || '...'}</span>
        </div>
        <div class="header-right">
          <div class="status-pill battery" classList={{ charging: st()?.battery?.isCharging }}>
            {st()?.battery?.level ?? '--'}%
          </div>
          <div class="status-pill thermal" classList={{
            warning: st()?.thermal === 'MODERATE' || st()?.thermal === 'LIGHT',
            danger: st()?.thermal === 'SEVERE' || st()?.thermal === 'CRITICAL',
          }}>
            {st()?.thermal || '--'}
          </div>
          <div class="status-pill clients">
            {st()?.streaming?.clientCount ?? 0} client{(st()?.streaming?.clientCount ?? 0) !== 1 ? 's' : ''}
          </div>
          <div class="status-pill saving" classList={{ active: saving() }}>
            {saving() ? 'Saving...' : 'Saved'}
          </div>
        </div>
      </header>

      <main class="main">
        <section class="preview-section">
          <div class="preview-container">
            {previewVisible() && st()?.streaming?.isActive ? (
              <img
                class="preview-img"
                src="/stream"
                alt="Live camera feed"
                onError={() => setPreviewVisible(false)}
              />
            ) : (
              <div class="preview-placeholder">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                  <circle cx="12" cy="13" r="4" />
                </svg>
                <span>{st()?.streaming?.isActive ? 'Stream error' : 'Stream not active'}</span>
              </div>
            )}
          </div>
          <div class="preview-actions">
            <button class="btn btn-primary" onClick={handleCapture} disabled={!st()?.streaming?.isActive}>
              Capture Photo
            </button>
            <a class="btn btn-secondary" href="/snapshot" target="_blank" download>
              Snapshot
            </a>
            {captureMsg() && <span class="capture-msg">{captureMsg()}</span>}
          </div>
          {st()?.streaming?.isActive && st()?.streaming?.url && (
            <div class="stream-url">
              <code>{st()!.streaming.url}</code>
            </div>
          )}
        </section>

        <section class="settings-section">
          {error() && <div class="error-banner">{error()}</div>}

          <div class="card">
            <h2>Lens</h2>
            <div class="lens-grid">
              {lenses().map((lens) => (
                <button
                  class="lens-btn"
                  classList={{ selected: lens.selected }}
                  onClick={() => handleSelectLens(lens.index)}
                >
                  <span class="lens-label">{lens.label}</span>
                  <span class="lens-fl">{lens.focalLength > 0 ? `${lens.focalLength}mm` : ''}</span>
                </button>
              ))}
            </div>
          </div>

          <div class="card">
            <h2>Exposure</h2>
            <Range
              label="Exposure Compensation"
              value={s()?.camera?.exposureCompensation ?? 0}
              min={-12}
              max={12}
              onChange={(v) => updateCamera({ exposureCompensation: v })}
            />
            <div class="field">
              <label>ISO</label>
              <div class="iso-row">
                <select
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
                    class="iso-input"
                    value={s()?.camera?.iso ?? ''}
                    min={100}
                    max={32000}
                    onChange={(e) => updateCamera({ iso: parseInt(e.currentTarget.value) || null })}
                  />
                )}
              </div>
            </div>
          </div>

          <div class="card">
            <h2>Focus</h2>
            <Select
              label="Focus Mode"
              value={s()?.camera?.focusMode ?? 'AUTO'}
              options={FOCUS_MODE_LABELS}
              onChange={(v) => updateCamera({ focusMode: v as FocusMode })}
            />
            {s()?.camera?.focusMode === 'MANUAL' && (
              <Range
                label="Focus Distance"
                value={s()?.camera?.focusDistance ?? 0}
                min={0}
                max={10}
                step={0.1}
                onChange={(v) => updateCamera({ focusDistance: v })}
              />
            )}
          </div>

          <div class="card">
            <h2>White Balance</h2>
            <Select
              label="White Balance"
              value={s()?.camera?.whiteBalance ?? 'AUTO'}
              options={WB_LABELS}
              onChange={(v) => updateCamera({ whiteBalance: v as WhiteBalance })}
            />
            {s()?.camera?.whiteBalance === 'MANUAL' && (
              <Range
                label="Color Temperature"
                value={s()?.camera?.colorTemperature ?? 5500}
                min={2000}
                max={9000}
                step={100}
                unit="K"
                onChange={(v) => updateCamera({ colorTemperature: v })}
              />
            )}
          </div>

          <div class="card">
            <h2>Zoom & Frame</h2>
            <Range
              label="Zoom"
              value={parseFloat((s()?.camera?.zoomRatio ?? 1).toFixed(1))}
              min={1}
              max={10}
              step={0.1}
              unit="x"
              onChange={(v) => updateCamera({ zoomRatio: v })}
            />
            <div class="field">
              <label>Frame Rate</label>
              <select
                value={s()?.camera?.frameRate ?? 30}
                onChange={(e) => updateCamera({ frameRate: parseInt(e.currentTarget.value) })}
              >
                {FRAME_RATE_OPTIONS.map((r) => (
                  <option value={r}>{r} fps</option>
                ))}
              </select>
            </div>
            <Select
              label="Resolution"
              value={s()?.camera?.resolution ?? 'FHD_1080P'}
              options={RESOLUTION_LABELS}
              onChange={(v) => updateCamera({ resolution: v as Resolution })}
            />
          </div>

          <div class="card">
            <h2>Effects</h2>
            <div class="field">
              <label class="toggle-row">
                <span>Stabilization</span>
                <button
                  class="toggle"
                  classList={{ on: s()?.camera?.stabilization ?? true }}
                  onClick={() => updateCamera({ stabilization: !(s()?.camera?.stabilization ?? true) })}
                >
                  <span class="toggle-knob" />
                </button>
              </label>
            </div>
            <Select
              label="HDR"
              value={s()?.camera?.hdrMode ?? 'OFF'}
              options={HDR_LABELS}
              onChange={(v) => updateCamera({ hdrMode: v as HdrMode })}
            />
          </div>

          <div class="card">
            <h2>Streaming</h2>
            <div class="field">
              <label>JPEG Quality</label>
              <div class="quality-row">
                <input
                  type="range"
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
                <span class="quality-value">{s()?.streaming?.jpegQuality ?? 80}%</span>
              </div>
            </div>
            <div class="field">
              <label class="toggle-row">
                <span>Show Preview on Device</span>
                <button
                  class="toggle"
                  classList={{ on: s()?.streaming?.showPreview ?? true }}
                  onClick={() => {
                    const newVal = !(s()?.streaming?.showPreview ?? true)
                    const current = settings()
                    if (current) {
                      setSettings({ ...current, streaming: { ...current.streaming, showPreview: newVal } })
                    }
                    saveSettings({ streaming: { showPreview: newVal } })
                  }}
                >
                  <span class="toggle-knob" />
                </button>
              </label>
            </div>
          </div>

          <div class="card">
            <h2>Authentication</h2>
            <div class="field">
              <label class="toggle-row">
                <span>Enable Auth</span>
                <button
                  class="toggle"
                  classList={{ on: s()?.auth?.enabled ?? false }}
                  onClick={() => {
                    const newVal = !(s()?.auth?.enabled ?? false)
                    const current = settings()
                    if (current) {
                      setSettings({ ...current, auth: { ...current.auth, enabled: newVal } })
                    }
                    saveSettings({ auth: { enabled: newVal } })
                  }}
                >
                  <span class="toggle-knob" />
                </button>
              </label>
            </div>
            {s()?.auth?.enabled && (
              <>
                <div class="field">
                  <label>Username</label>
                  <input
                    type="text"
                    value={s()?.auth?.username ?? ''}
                    onChange={(e) => {
                      const v = e.currentTarget.value
                      debounceSave(() => saveSettings({ auth: { username: v } }))
                    }}
                  />
                </div>
                <div class="field">
                  <label>Password</label>
                  <input
                    type="password"
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

          <div class="card actions">
            <button class="btn btn-danger" onClick={handleResetDefaults}>
              Reset to Defaults
            </button>
          </div>
        </section>
      </main>
    </div>
  )
}

export default App
