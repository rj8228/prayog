import { describe, expect, it } from 'vitest'
import { addPanel, panelsOf, PRESETS } from './layouts'
import { shortcutFor, stepSymbol } from './shortcuts'
import { removePanel, swapPanels } from './swap'

const key = (
  k: string,
  target: Partial<HTMLElement> | null = null,
  mods = {},
) => ({
  key: k,
  ctrlKey: false,
  metaKey: false,
  altKey: false,
  target: target as EventTarget | null,
  ...mods,
})

describe('workspace layouts', () => {
  it('swaps two panels position and size, keeping minimums', () => {
    const lg = PRESETS.trader.layouts.lg!
    const swapped = swapPanels(lg, 'chart', 'ticket')
    const chart = swapped.find((i) => i.i === 'chart')!
    const ticket = swapped.find((i) => i.i === 'ticket')!
    expect([chart.x, chart.y, chart.w]).toEqual([0, 14, 3])
    expect(chart.h).toBe(12)
    expect([ticket.x, ticket.y, ticket.w, ticket.h]).toEqual([0, 0, 7, 14])
    expect(swapped.filter((i) => i.i !== 'chart' && i.i !== 'ticket')).toEqual(
      lg.filter((i) => i.i !== 'chart' && i.i !== 'ticket'),
    )
  })

  it('adds a panel below everything and removes it again', () => {
    const layouts = addPanel(PRESETS.watcher.layouts, 'positions')
    const added = layouts.lg!.find((i) => i.i === 'positions')!
    expect(added.y).toBe(24)
    expect(panelsOf(layouts)).toContain('positions')
    expect(
      panelsOf({ lg: removePanel(layouts.lg!, 'positions') }),
    ).not.toContain('positions')
  })

  it('every preset fits the 12-column grid without overlaps', () => {
    for (const preset of Object.values(PRESETS)) {
      const lg = preset.layouts.lg!
      for (const a of lg) {
        expect(a.x + a.w).toBeLessThanOrEqual(12)
        for (const b of lg) {
          if (a === b) continue
          const overlap =
            a.x < b.x + b.w &&
            b.x < a.x + a.w &&
            a.y < b.y + b.h &&
            b.y < a.y + a.h
          expect(overlap, `${preset.name}: ${a.i} overlaps ${b.i}`).toBe(false)
        }
      }
    }
  })
})

describe('keyboard shortcuts', () => {
  it('maps keys, case-insensitively', () => {
    expect(shortcutFor(key('b'))).toBe('buy')
    expect(shortcutFor(key('S'))).toBe('sell')
    expect(shortcutFor(key(']'))).toBe('next-symbol')
    expect(shortcutFor(key('q'))).toBeNull()
  })

  it('ignores keys while typing or with modifiers', () => {
    expect(shortcutFor(key('b', { tagName: 'INPUT' }))).toBeNull()
    expect(shortcutFor(key('b', null, { metaKey: true }))).toBeNull()
  })

  it('steps through symbols with wrap-around', () => {
    const s = ['HDFCBANK', 'INFY', 'TCS']
    expect(stepSymbol(s, 'TCS', 1)).toBe('HDFCBANK')
    expect(stepSymbol(s, 'HDFCBANK', -1)).toBe('TCS')
    expect(stepSymbol([], 'INFY', 1)).toBe('INFY')
  })
})
