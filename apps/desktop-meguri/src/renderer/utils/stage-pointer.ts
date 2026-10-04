export interface StageBounds {
  left: number
  top: number
  right: number
  bottom: number
}

export interface StagePointerInput {
  enabled: boolean
  x: number
  y: number
  viewportWidth: number
  viewportHeight: number
  portraitBounds?: StageBounds
  interactive: boolean
  resizeThreshold?: number
}

export interface StagePointerBehavior {
  clickThrough: boolean
  fadePortrait: boolean
}

function pointInside(bounds: StageBounds | undefined, x: number, y: number) {
  return Boolean(bounds
    && x >= bounds.left
    && x <= bounds.right
    && y >= bounds.top
    && y <= bounds.bottom)
}

export function resolveStagePointerBehavior(input: StagePointerInput): StagePointerBehavior {
  if (!input.enabled)
    return { clickThrough: false, fadePortrait: false }

  const edge = input.resizeThreshold ?? 9
  const nearResizeEdge = input.x <= edge
    || input.y <= edge
    || input.x >= input.viewportWidth - edge
    || input.y >= input.viewportHeight - edge

  if (input.interactive || nearResizeEdge)
    return { clickThrough: false, fadePortrait: false }

  return {
    clickThrough: true,
    fadePortrait: pointInside(input.portraitBounds, input.x, input.y),
  }
}
