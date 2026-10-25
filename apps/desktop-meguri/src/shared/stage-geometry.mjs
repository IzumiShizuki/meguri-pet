export const ambientPortrait = Object.freeze({ width: 350, height: 540, top: 8, bottom: 10 })
export const compactStageSize = Object.freeze({ width: 390, height: 560 })
export const expandedStageSize = Object.freeze({ width: 800, height: 560 })
export const galgameStageSize = Object.freeze({ width: 860, height: 720 })
export const stageGeometryVersion = 2
export const ambientSideBubbleMinWidth = 740

export function ambientPortraitTop(height) {
  return Math.max(ambientPortrait.top, height - ambientPortrait.height - ambientPortrait.bottom)
}

export function ambientPortraitCenter(width, expanded = false) {
  return expanded && width >= ambientSideBubbleMinWidth ? compactStageSize.width / 2 : width / 2
}
