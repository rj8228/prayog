import { describe, expect, it } from 'vitest'
import { changePercent, rupees, simClock, toPaise } from './format'

describe('format', () => {
  it('shows paise as rupees with Indian grouping', () => {
    expect(rupees(149550)).toBe('1,495.50')
    expect(rupees(5)).toBe('0.05')
    expect(rupees(-250)).toBe('-2.50')
  })

  it('parses rupees into paise exactly', () => {
    expect(toPaise('1495.5')).toBe(149550)
    expect(toPaise('1,495.50')).toBe(149550)
    expect(toPaise('1500')).toBe(150000)
    expect(toPaise('1495.505')).toBeNull()
    expect(toPaise('abc')).toBeNull()
  })

  it('shows sim time in IST', () => {
    // 2026-10-05 03:45:00 UTC = 09:15:00 IST
    expect(simClock(Date.UTC(2026, 9, 5, 3, 45, 0) * 1000)).toBe('09:15:00')
  })

  it('shows change against the reference price', () => {
    expect(changePercent(151500, 150000)).toBe('+1.00%')
    expect(changePercent(148500, 150000)).toBe('-1.00%')
  })
})
