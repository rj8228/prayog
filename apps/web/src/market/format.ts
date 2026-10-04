// Display helpers. Money stays integer paise until the moment it is shown.

export function rupees(paise: number): string {
  const sign = paise < 0 ? '-' : ''
  const abs = Math.abs(paise)
  return `${sign}${Math.floor(abs / 100).toLocaleString('en-IN')}.${String(abs % 100).padStart(2, '0')}`
}

/** Parses "1495.5" or "1,495.50" rupees into paise, refusing fractions of a paisa. Null if invalid. */
export function toPaise(text: string): number | null {
  const clean = text.replace(/,/g, '').trim()
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return null
  const [whole, fraction = ''] = clean.split('.')
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
}

/** Sim time (epoch micros) as HH:MM:SS in IST, the exchange's time zone. */
export function simClock(micros: number): string {
  const ist = new Date(micros / 1000 + 5.5 * 3600 * 1000)
  return ist.toISOString().slice(11, 19)
}

export function changePercent(last: number, reference: number): string {
  if (!last || !reference) return '0.00%'
  const change = ((last - reference) / reference) * 100
  return `${change >= 0 ? '+' : ''}${change.toFixed(2)}%`
}
