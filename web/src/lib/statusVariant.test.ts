import { describe, it, expect } from 'vitest'
import { statusTone } from './statusVariant'

describe('statusTone', () => {
  it('hides the chip in the resting state', () => {
    expect(statusTone('info')).toBeNull()
  })

  it('maps the active states to their tones', () => {
    expect(statusTone('success')).toBe('success')
    expect(statusTone('warning')).toBe('warning')
    expect(statusTone('error')).toBe('danger')
  })
})
