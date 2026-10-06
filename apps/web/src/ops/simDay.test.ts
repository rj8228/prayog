import { describe, expect, it } from 'vitest'
import { dayProgress, localMinutes, wallSecondsForDay } from './simDay'

// 2026-10-05 at a given IST wall time, as epoch microseconds.
const ist = (h: number, m: number) =>
  Date.UTC(2026, 9, 5, h, m) * 1000 - (5 * 60 + 30) * 60_000_000

describe('sim day progress', () => {
  it('reads local minutes in the market offset', () => {
    expect(localMinutes(ist(9, 15))).toBe(9 * 60 + 15)
    expect(localMinutes(ist(0, 5))).toBe(5)
  })

  it('is before, during and after trading hours', () => {
    expect(dayProgress(ist(8, 0)).phase).toBe('before')
    const noon = dayProgress(ist(12, 22))
    expect(noon.phase).toBe('during')
    expect(noon.fraction).toBeCloseTo(187 / 375)
    expect(noon.minutesToClose).toBe(188)
    expect(dayProgress(ist(15, 30))).toEqual({
      phase: 'after',
      fraction: 1,
      minutesToClose: 0,
    })
  })

  it('knows how long a day takes at a speed', () => {
    expect(wallSecondsForDay(1)).toBe(375 * 60)
    expect(wallSecondsForDay(300)).toBe(75)
    expect(wallSecondsForDay(0)).toBe(375 * 60) // never divides by zero
  })
})
