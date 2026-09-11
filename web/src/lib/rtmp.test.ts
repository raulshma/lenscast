import { describe, expect, it } from 'vitest'
import { rtmpStatusView, rtmpUrlField } from './rtmp'

describe('rtmpStatusView', () => {
  it('maps each wire status onto its banner variant', () => {
    expect(rtmpStatusView({ status: 'idle', active: false })).toEqual({
      label: 'Idle', detail: '', variant: 'info',
    })
    expect(rtmpStatusView({ status: 'connecting', active: true })).toEqual({
      label: 'Connecting…', detail: '', variant: 'warning',
    })
    expect(rtmpStatusView({ status: 'connected', active: true })).toEqual({
      label: 'Connected', detail: '', variant: 'success',
    })
    expect(rtmpStatusView({ status: 'error', active: false, error: 'H.265 is not supported over RTMP' })).toEqual({
      label: 'Error', detail: 'H.265 is not supported over RTMP', variant: 'error',
    })
  })

  it('keeps connecting ahead of active — the handshake is still in flight', () => {
    // The server flips rtmpActive on start; connecting is the precise state.
    expect(rtmpStatusView({ status: 'connecting', active: true }).label).toBe('Connecting…')
  })

  it('treats a bare active flag as connected (older snapshots without rtmpStatus)', () => {
    expect(rtmpStatusView({ status: undefined, active: true })).toEqual({
      label: 'Connected', detail: '', variant: 'success',
    })
  })

  it('reads absent fields (pre-RTMP firmware) as idle', () => {
    expect(rtmpStatusView({ status: undefined, active: undefined })).toEqual({
      label: 'Idle', detail: '', variant: 'info',
    })
  })

  it('falls back to a generic message when the error reason is blank', () => {
    expect(rtmpStatusView({ status: 'error', active: false, error: null }).detail).toBe('RTMP push failed')
    expect(rtmpStatusView({ status: 'error', active: false, error: '   ' }).detail).toBe('RTMP push failed')
  })
})

describe('rtmpUrlField', () => {
  it('accepts rtmp:// and rtmps:// targets with a host', () => {
    for (const value of [
      'rtmp://ingest.example.com/live/key',
      'rtmps://ingest.example.com:1935/live/key',
      'rtmp://192.168.1.10:1935/live',
      'rtmp://user:pass@ingest.example.com/live/key',
      '  rtmp://ingest.example.com/live  ',
    ]) {
      const view = rtmpUrlField(value)
      expect(view.valid).toBe(true)
      expect(view.invalid).toBe(false)
    }
  })

  it('keeps Start disabled on a blank field without flagging it as an error', () => {
    const view = rtmpUrlField('   ')
    expect(view.valid).toBe(false)
    expect(view.invalid).toBe(false)
    expect(view.hint).toContain('rtmp://')
  })

  it('flags schemes the server would refuse', () => {
    for (const value of [
      'https://ingest.example.com/live',
      'rtsp://ingest.example.com/live',
      'ingest.example.com/live/key',
      'RTMP //missing-colon',
    ]) {
      const view = rtmpUrlField(value)
      expect(view.valid).toBe(false)
      expect(view.invalid).toBe(true)
      expect(view.hint).toContain('rtmp://')
    }
  })

  it('flags a scheme with no host (rtmp:///path)', () => {
    const view = rtmpUrlField('rtmp:///live/stream-key')
    expect(view.valid).toBe(false)
    expect(view.invalid).toBe(true)
    expect(view.hint).toContain('host')
  })
})
