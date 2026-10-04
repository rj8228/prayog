// Keyboard shortcuts for the trading workspace. Ignored while typing in a field.

export type ShortcutAction =
  | 'buy'
  | 'sell'
  | 'previous-symbol'
  | 'next-symbol'
  | 'cancel-all'
  | 'toggle-explain'
  | 'toggle-lock'
  | 'help'

export const SHORTCUTS: {
  keys: string
  action: ShortcutAction
  text: string
}[] = [
  {
    keys: 'B',
    action: 'buy',
    text: 'Buy: switch the ticket to buy and jump to quantity',
  },
  {
    keys: 'S',
    action: 'sell',
    text: 'Sell: switch the ticket to sell and jump to quantity',
  },
  { keys: '[  ]', action: 'previous-symbol', text: 'Previous / next symbol' },
  {
    keys: 'X',
    action: 'cancel-all',
    text: 'Cancel all my open orders (asks first)',
  },
  { keys: 'E', action: 'toggle-explain', text: 'Explain mode on / off' },
  { keys: 'L', action: 'toggle-lock', text: 'Lock / unlock the layout' },
  { keys: '?', action: 'help', text: 'Show these shortcuts' },
]

const KEYS: Record<string, ShortcutAction> = {
  b: 'buy',
  s: 'sell',
  '[': 'previous-symbol',
  ']': 'next-symbol',
  x: 'cancel-all',
  e: 'toggle-explain',
  l: 'toggle-lock',
  '?': 'help',
}

export interface KeyLike {
  key: string
  ctrlKey: boolean
  metaKey: boolean
  altKey: boolean
  target: EventTarget | null
}

/** The action for a key press, or null (modifier held, typing in a field, or an unbound key). */
export function shortcutFor(e: KeyLike): ShortcutAction | null {
  if (e.ctrlKey || e.metaKey || e.altKey) return null
  const el = e.target as HTMLElement | null
  const tag = el?.tagName
  if (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    el?.isContentEditable
  )
    return null
  return KEYS[e.key.toLowerCase()] ?? null
}

/** Moves the selection by `step` through the sorted symbols, wrapping around. */
export function stepSymbol(
  symbols: string[],
  current: string,
  step: number,
): string {
  if (!symbols.length) return current
  const i = symbols.indexOf(current)
  return symbols[
    ((((i < 0 ? 0 : i) + step) % symbols.length) + symbols.length) %
      symbols.length
  ]
}
