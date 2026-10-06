// Where a simulated trading day stands, for the ops page's "run a day" control. Sim time is epoch microseconds; the
// session runs from `open` to `close` in the market's own offset (IST by default).

export interface DayProgress {
  /** 'before' the open, 'during' trading hours, or 'after' the close. */
  phase: 'before' | 'during' | 'after'
  /** 0..1 through trading hours (0 before the open, 1 after the close). */
  fraction: number
  /** Sim minutes until the close (0 after it). */
  minutesToClose: number
}

const MICROS_PER_MINUTE = 60_000_000

function minutesOf(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number)
  return h * 60 + m
}

/** Minutes after local midnight for a sim time, in a fixed offset like "+05:30". */
export function localMinutes(simTime: number, offset = '+05:30'): number {
  const sign = offset.startsWith('-') ? -1 : 1
  const offsetMinutes = sign * minutesOf(offset.slice(1))
  const utcMinutes = Math.floor(simTime / MICROS_PER_MINUTE)
  return (((utcMinutes + offsetMinutes) % 1440) + 1440) % 1440
}

export function dayProgress(
  simTime: number,
  open = '09:15',
  close = '15:30',
  offset = '+05:30',
): DayProgress {
  const now = localMinutes(simTime, offset)
  const o = minutesOf(open)
  const c = minutesOf(close)
  if (now < o) return { phase: 'before', fraction: 0, minutesToClose: c - o }
  if (now >= c) return { phase: 'after', fraction: 1, minutesToClose: 0 }
  return {
    phase: 'during',
    fraction: (now - o) / (c - o),
    minutesToClose: c - now,
  }
}

/** Wall-clock seconds a full day of trading takes at `multiplier` (the clock runs `multiplier` times real time). */
export function wallSecondsForDay(
  multiplier: number,
  open = '09:15',
  close = '15:30',
): number {
  return Math.round(
    ((minutesOf(close) - minutesOf(open)) * 60) / Math.max(1, multiplier),
  )
}
