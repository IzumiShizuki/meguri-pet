import { ambientPortraitCenter, ambientPortraitTop, compactStageSize, expandedStageSize, galgameStageSize, stageGeometryVersion } from '../shared/stage-geometry.mjs'
import { normalizeBounds, resolveStageBounds } from './window-bounds.mjs'

const modes = new Set(['compact', 'expanded', 'galgame'])

export function stageLayoutMode(preferences, surface = 'ambient') {
  return surface === 'galgame' ? 'galgame'
    : surface === 'ambient-panel' ? 'expanded' : 'compact'
}

function validSize(value, fallback) {
  const normalized = normalizeBounds({ x: 0, y: 0, width: value?.width, height: value?.height })
  return normalized ? { width: Math.max(360, normalized.width), height: Math.max(560, normalized.height) } : { ...fallback }
}

/** All coordinates are Electron DIP / CSS pixels; display scale factors are not applied twice. */
export function fitStageLayoutBounds(previous, { from, to, size, workArea }) {
  const target = validSize(to === 'compact' ? compactStageSize : size, to === 'galgame' ? galgameStageSize : expandedStageSize)
  const width = Math.min(target.width, Math.max(360, workArea.width))
  const height = Math.min(target.height, Math.max(560, workArea.height))
  const oldTop = from === 'galgame' ? 8 : ambientPortraitTop(previous.height, from === 'expanded')
  const newTop = to === 'galgame' ? 8 : ambientPortraitTop(height, to === 'expanded')
  const oldCenter = from === 'galgame' ? previous.width / 2 : ambientPortraitCenter(previous.width, from === 'expanded')
  const newCenter = to === 'galgame' ? width / 2 : ambientPortraitCenter(width, to === 'expanded')
  const x = previous.x + oldCenter - newCenter
  const y = previous.y + oldTop - newTop
  return {
    x: Math.round(Math.min(Math.max(x, workArea.x), workArea.x + Math.max(0, workArea.width - width))),
    y: Math.round(Math.min(Math.max(y, workArea.y), workArea.y + Math.max(0, workArea.height - height))),
    width,
    height,
  }
}

export function resolveInitialStageLayout(state, preferences, workAreas, preferredWorkArea, matchingWorkArea) {
  const mode = stageLayoutMode(preferences)
  const returningFromGalgame = state.stageLayoutMode === 'galgame' && normalizeBounds(state.stageAmbientBounds)
  const saved = returningFromGalgame ? state.stageAmbientBounds : state.stageBounds
  let previous = resolveStageBounds(saved, workAreas, preferredWorkArea)
  if (!normalizeBounds(saved) && mode === 'compact') {
    previous = { ...previous, x: previous.x + previous.width - compactStageSize.width, ...compactStageSize }
  }
  const from = returningFromGalgame ? (state.stageAmbientMode === 'expanded' ? 'expanded' : 'compact')
    : modes.has(state.stageLayoutMode) ? state.stageLayoutMode : mode
  // Tall pre-bubble windows are migrated once; new user-resized panel bounds remain remembered.
  const expandedSize = state.stageGeometryVersion === stageGeometryVersion
    ? validSize(state.stageExpandedSize ?? (from === 'expanded' ? previous : undefined), expandedStageSize)
    : { ...expandedStageSize }
  const galgameSize = validSize(state.stageGalgameSize, galgameStageSize)
  const bounds = fitStageLayoutBounds(previous, { from, to: mode, size: expandedSize, workArea: matchingWorkArea(previous) })
  return { bounds, mode, expandedSize, galgameSize }
}

/** Owns fitting and its persistence; the renderer can select named modes, never arbitrary bounds. */
export function createStageLayoutController({ window, initial, matchingWorkArea, writeState }) {
  let mode = initial.mode
  let expandedSize = initial.expandedSize
  let galgameSize = initial.galgameSize
  let ambientReturn
  let compactReturn

  function rememberSize(bounds) {
    if (mode === 'expanded') expandedSize = validSize(bounds, expandedStageSize)
    if (mode === 'galgame') galgameSize = validSize(bounds, galgameStageSize)
  }

  function persist() {
    if (window.isDestroyed()) return
    const bounds = window.getBounds()
    rememberSize(bounds)
    writeState({
      stageBounds: bounds,
      stageLayoutMode: mode,
      stageGeometryVersion,
      stageExpandedSize: expandedSize,
      stageGalgameSize: galgameSize,
      stageAmbientBounds: ambientReturn?.bounds,
      stageAmbientMode: ambientReturn?.mode,
    })
  }

  function fit(preferences, surface = 'ambient') {
    if (window.isDestroyed()) return
    const nextMode = stageLayoutMode(preferences, surface)
    let previous = window.getBounds()
    // Windows rounds native bounds to physical pixels on fractional-DPI displays.
    // Refitting an already-compact window for that rounding can cause position drift.
    if (nextMode === mode && (mode !== 'compact'
      || (Math.abs(previous.width - compactStageSize.width) <= 2 && Math.abs(previous.height - compactStageSize.height) <= 2))) return
    let from = mode
    rememberSize(previous)
    if (nextMode === 'galgame') ambientReturn = { bounds: previous, mode }
    else if (mode === 'galgame' && ambientReturn) {
      previous = ambientReturn.bounds
      from = ambientReturn.mode
      ambientReturn = undefined
    }
    const openingPanel = from === 'compact' && nextMode === 'expanded'
    if (nextMode === 'compact' && from === 'expanded' && compactReturn) {
      // Undo automatic fitting while retaining deliberate portrait movement or resize.
      const fitted = compactReturn.fitted
      previous = {
        // Position is already in the compact coordinate system. Normalizing the
        // size here avoids applying fractional-DPI size rounding as movement.
        ...compactStageSize,
        x: compactReturn.bounds.x + previous.x + ambientPortraitCenter(previous.width, true)
          - fitted.x - ambientPortraitCenter(fitted.width, true),
        y: compactReturn.bounds.y + previous.y + ambientPortraitTop(previous.height)
          - fitted.y - ambientPortraitTop(fitted.height),
      }
      from = 'compact'
      compactReturn = undefined
    }
    const bounds = fitStageLayoutBounds(previous, {
      from, to: nextMode, size: nextMode === 'galgame' ? galgameSize : expandedSize,
      workArea: matchingWorkArea(previous),
    })
    mode = nextMode
    window.setIgnoreMouseEvents(false)
    window.setBounds(bounds)
    if (openingPanel)
      compactReturn = { bounds: previous, fitted: window.getBounds() }
    persist()
  }

  return { fit, persist }
}
