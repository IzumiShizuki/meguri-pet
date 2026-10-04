import { resolveStagePointerBehavior, type StagePointerBehavior, type StagePointerInput } from './stage-pointer'
import type { PresentationSurface } from '../stores/presentation-surface'

/** Gal mode is an ordinary foreground UI, never a click-through pet window. */
export function resolveSurfacePointerBehavior(
  surface: PresentationSurface,
  input: StagePointerInput,
): StagePointerBehavior {
  if (surface === 'galgame')
    return { clickThrough: false, fadePortrait: false }
  return resolveStagePointerBehavior(input)
}
