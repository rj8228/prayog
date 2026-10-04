import type { Layout } from 'react-grid-layout'

/**
 * Swaps two panels' places: each takes the other's position and size (kept within its minimum size). Dragging pushes
 * other panels aside; this is the exact "interchange" for two panels that should trade places.
 */
export function swapPanels(layout: Layout, a: string, b: string): Layout {
  const first = layout.find((item) => item.i === a)
  const second = layout.find((item) => item.i === b)
  if (!first || !second || a === b) return layout
  return layout.map((item) => {
    const other = item.i === a ? second : item.i === b ? first : null
    if (!other) return item
    return {
      ...item,
      x: other.x,
      y: other.y,
      w: Math.max(other.w, item.minW ?? 1),
      h: Math.max(other.h, item.minH ?? 1),
    }
  })
}

export function removePanel(layout: Layout, id: string): Layout {
  return layout.filter((item) => item.i !== id)
}
