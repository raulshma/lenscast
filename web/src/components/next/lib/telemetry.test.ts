import { describe, it, expect } from 'vitest'
import { channelLed, formatKbps, linkLed, batteryTone, thermalTone, vitalToneClass } from './telemetry'

describe('channelLed', () => {
  it('reads on-air over everything else', () => {
    expect(channelLed({ code: 'RTMP', enabled: true, active: true, status: 'error' })).toBe('ok')
  })

  it('maps the handshake and fault states', () => {
    expect(channelLed({ code: 'RTMP', enabled: true, active: false, status: 'connecting' })).toBe('warn')
    expect(channelLed({ code: 'RTMP', enabled: true, active: false, status: 'error' })).toBe('danger')
    expect(channelLed({ code: 'RTMP', enabled: true, active: false, status: 'idle' })).toBe('off')
  })
})

describe('formatKbps', () => {
  it('stays in K below 1000 and switches to M above', () => {
    expect(formatKbps(980)).toBe('980K')
    expect(formatKbps(14_200)).toBe('14.2M')
  })
})

describe('linkLed', () => {
  it('maps the quality ladder', () => {
    expect(linkLed('EXCELLENT')).toBe('ok')
    expect(linkLed('FAIR')).toBe('warn')
    expect(linkLed('CRITICAL')).toBe('danger')
    expect(linkLed(undefined)).toBe('off')
  })
})

describe('batteryTone', () => {
  it('keeps healthy levels neutral and flags the boundaries', () => {
    expect(batteryTone(undefined)).toBe('default')
    expect(batteryTone(36)).toBe('default')
    expect(batteryTone(35)).toBe('warn')
    expect(batteryTone(16)).toBe('warn')
    expect(batteryTone(15)).toBe('danger')
  })
})

describe('thermalTone', () => {
  it('maps the severity ladder', () => {
    expect(thermalTone('NORMAL')).toBe('default')
    expect(thermalTone('LIGHT')).toBe('warn')
    expect(thermalTone('SEVERE')).toBe('danger')
    expect(thermalTone(undefined)).toBe('default')
  })
})

describe('vitalToneClass', () => {
  it('colors the active tones and leaves neutrals classless', () => {
    expect(vitalToneClass('ok')).toBe('nx-vital-ok')
    expect(vitalToneClass('warn')).toBe('nx-vital-warn')
    expect(vitalToneClass('danger')).toBe('nx-vital-danger')
    expect(vitalToneClass('default')).toBe('')
    expect(vitalToneClass('off')).toBe('')
  })
})
