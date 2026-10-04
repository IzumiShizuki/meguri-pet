import { describe, expect, it } from 'vitest'

import { resolveStageBounds } from './window-bounds.mjs'

const primary = { x: 0, y: 0, width: 1920, height: 1040 }

describe('resolveStageBounds', () => {
  it('restores bounds on a negative-coordinate display', () => {
    const saved = { x: -420, y: 36, width: 400, height: 660 }
    expect(resolveStageBounds(saved, [{ x: -1920, y: 0, width: 1920, height: 1080 }, primary], primary)).toEqual(saved)
  })

  it('moves unreachable saved bounds onto the preferred display', () => {
    expect(resolveStageBounds({ x: 8000, y: 8000, width: 400, height: 660 }, [primary], primary)).toEqual({
      x: 1452,
      y: 28,
      width: 440,
      height: 720,
    })
  })

  it('rejects nonsensical sizes', () => {
    const resolved = resolveStageBounds({ x: 1, y: 2, width: 20, height: 20 }, [primary], primary)
    expect(resolved.width).toBe(440)
    expect(resolved.height).toBe(720)
  })
})
