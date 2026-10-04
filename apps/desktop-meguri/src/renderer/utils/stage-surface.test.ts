import { describe, expect, it } from 'vitest'

import { resolveSurfacePointerBehavior } from './stage-surface'

describe('surface pointer behavior', () => {
  it('keeps Galgame mode interactive even when ambient hover pass-through is enabled', () => {
    expect(resolveSurfacePointerBehavior('galgame', {
      enabled: true,
      x: 10,
      y: 10,
      viewportWidth: 420,
      viewportHeight: 620,
      interactive: false,
    })).toEqual({ clickThrough: false, fadePortrait: false })
  })

})
