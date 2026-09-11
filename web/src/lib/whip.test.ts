import { describe, expect, it } from 'vitest'
import { whipStatusView, whipStunField } from './whip'

const DEFAULT_STUN = 'stun.l.google.com:19302'

describe('whipStatusView', () => {
  it('maps each wire status onto its banner variant', () => {
    expect(whipStatusView({ status: 'idle', active: false })).toEqual({
      label: 'Idle', detail: '', variant: 'info',
    })
    expect(whipStatusView({ status: 'connecting', active: true })).toEqual({
      label: 'Connecting…', detail: '', variant: 'warning',
    })
    expect(whipStatusView({ status: 'connected', active: true })).toEqual({
      label: 'Connected', detail: '', variant: 'success',
    })
    expect(whipStatusView({ status: 'error', active: false, error: 'ICE gathering timed out' })).toEqual({
      label: 'Error', detail: 'ICE gathering timed out', variant: 'error',
    })
  })

  it('keeps connecting ahead of active — the connect is still in flight', () => {
    // The server flips whipActive on start; connecting is the precise state.
    expect(whipStatusView({ status: 'connecting', active: true }).label).toBe('Connecting…')
  })

  it('treats a bare active flag as connected (older snapshots without whipStatus)', () => {
    expect(whipStatusView({ status: undefined, active: true })).toEqual({
      label: 'Connected', detail: '', variant: 'success',
    })
  })

  it('reads absent fields (pre-WHIP firmware) as idle', () => {
    expect(whipStatusView({ status: undefined, active: undefined })).toEqual({
      label: 'Idle', detail: '', variant: 'info',
    })
  })

  it('falls back to a generic message when the error reason is blank', () => {
    expect(whipStatusView({ status: 'error', active: false, error: null }).detail).toBe('WHIP push failed')
    expect(whipStatusView({ status: 'error', active: false, error: '   ' }).detail).toBe('WHIP push failed')
  })
})

describe('whipStunField', () => {
  it('shows the default server as the placeholder', () => {
    expect(whipStunField('', DEFAULT_STUN).placeholder).toBe(DEFAULT_STUN)
  })

  it('explains that empty means LAN-only host candidates', () => {
    const view = whipStunField('', DEFAULT_STUN)
    expect(view.invalid).toBe(false)
    expect(view.hint).toContain('host candidates only')
    expect(view.hint).toContain(DEFAULT_STUN)
  })

  it('accepts bare host[:port] values', () => {
    for (const value of ['stun.example.com', 'stun.example.com:3478', '192.168.1.10:3478', '  stun.example.com  ']) {
      expect(whipStunField(value, DEFAULT_STUN).invalid).toBe(false)
    }
  })

  it('flags scheme prefixes the server would reject', () => {
    for (const value of ['stun:stun.example.com', 'stuns:stun.example.com:3478', 'http://stun.example.com', 'https://stun.example.com:3478']) {
      const view = whipStunField(value, DEFAULT_STUN)
      expect(view.invalid).toBe(true)
      expect(view.hint).toContain('bare host[:port]')
    }
  })
})
