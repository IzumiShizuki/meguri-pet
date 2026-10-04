const minimumVisiblePixels = 72

export const defaultStageSize = Object.freeze({ width: 440, height: 720 })

/**
 * Returns persisted bounds only when a meaningful area remains reachable on a
 * current display. Electron screen coordinates can be negative on multi-monitor
 * layouts, so validation must use rectangle intersection instead of zero clamps.
 */
export function resolveStageBounds(savedBounds, workAreas, preferredWorkArea) {
  const normalized = normalizeBounds(savedBounds)
  if (normalized && workAreas.some(area => visibleArea(normalized, area) >= minimumVisiblePixels ** 2))
    return normalized

  const area = normalizeBounds(preferredWorkArea) ?? normalizeBounds(workAreas[0]) ?? {
    x: 0,
    y: 0,
    width: 1920,
    height: 1080,
  }
  const width = Math.min(defaultStageSize.width, area.width)
  const height = Math.min(defaultStageSize.height, area.height)
  return {
    width,
    height,
    x: area.x + area.width - width - Math.min(28, Math.max(0, area.width - width)),
    y: area.y + Math.min(28, Math.max(0, area.height - height)),
  }
}

export function normalizeBounds(value) {
  if (!value || typeof value !== 'object')
    return undefined
  const bounds = {
    x: Number(value.x),
    y: Number(value.y),
    width: Number(value.width),
    height: Number(value.height),
  }
  if (!Object.values(bounds).every(Number.isFinite))
    return undefined
  if (bounds.width < 320 || bounds.height < 480 || bounds.width > 2400 || bounds.height > 2400)
    return undefined
  return Object.fromEntries(Object.entries(bounds).map(([key, number]) => [key, Math.round(number)]))
}

function visibleArea(left, right) {
  const width = Math.max(0, Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x))
  const height = Math.max(0, Math.min(left.y + left.height, right.y + right.height) - Math.max(left.y, right.y))
  return width * height
}
