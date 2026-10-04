import { describe, expect, it } from 'vitest'

import { resolveStagePointerBehavior } from './stage-pointer'

const portraitBounds = { left: 120, top: 80, right: 300, bottom: 440 }

describe('pinned stage pointer behavior', () => {
  it('fades the portrait and passes clicks through when hovered', () => {
    expect(resolveStagePointerBehavior({
      enabled: true,
      x: 180,
      y: 200,
      viewportWidth: 420,
      viewportHeight: 540,
      portraitBounds,
      interactive: false,
    })).toEqual({ clickThrough: true, fadePortrait: true })
  })

  it('passes transparent background through without fading unrelated UI', () => {
    expect(resolveStagePointerBehavior({
      enabled: true,
      x: 40,
      y: 260,
      viewportWidth: 420,
      viewportHeight: 540,
      portraitBounds,
      interactive: false,
    })).toEqual({ clickThrough: true, fadePortrait: false })
  })

  it.each([
    { label: 'interactive UI', x: 180, y: 200, interactive: true },
    { label: 'resize border', x: 5, y: 200, interactive: false },
  ])('keeps $label clickable', ({ x, y, interactive }) => {
    expect(resolveStagePointerBehavior({
      enabled: true,
      x,
      y,
      viewportWidth: 420,
      viewportHeight: 540,
      portraitBounds,
      interactive,
    })).toEqual({ clickThrough: false, fadePortrait: false })
  })

  it('restores interaction when fade-on-hover is disabled', () => {
    expect(resolveStagePointerBehavior({
      enabled: false,
      x: 180,
      y: 200,
      viewportWidth: 420,
      viewportHeight: 540,
      portraitBounds,
      interactive: false,
    })).toEqual({ clickThrough: false, fadePortrait: false })
  })
})
