import { createRoot } from 'solid-js'
import { describe, expect, it } from 'vitest'
import { pushSample, useSignalHistory } from './useSignalHistory'

describe('pushSample', () => {
  it('appends while under capacity', () => {
    expect(pushSample([1, 2], 3, 60)).toEqual([1, 2, 3])
  })

  it('drops the oldest samples beyond capacity', () => {
    let samples: number[] = []
    for (let i = 0; i < 5; i++) samples = pushSample(samples, i, 3)
    expect(samples).toEqual([2, 3, 4])
  })

  it('keeps exactly the newest sample at capacity 1', () => {
    expect(pushSample(pushSample([7], 8, 1), 9, 1)).toEqual([9])
  })

  it('does not mutate the input buffer', () => {
    const samples = [1, 2]
    pushSample(samples, 3, 10)
    expect(samples).toEqual([1, 2])
  })
})

describe('useSignalHistory', () => {
  it('keeps the last N samples oldest-first and resets', () => {
    createRoot((dispose) => {
      const history = useSignalHistory({ capacity: 3 })
      expect(history.values()).toEqual([])
      history.push(1)
      history.push(2)
      history.push(3)
      history.push(4)
      expect(history.values()).toEqual([2, 3, 4])
      history.reset()
      expect(history.values()).toEqual([])
      dispose()
    })
  })

  it('defaults to a capacity of 60', () => {
    createRoot((dispose) => {
      const history = useSignalHistory()
      for (let i = 0; i < 70; i++) history.push(i)
      expect(history.values()).toHaveLength(60)
      expect(history.values()[0]).toBe(10)
      expect(history.values()[59]).toBe(69)
      dispose()
    })
  })
})
