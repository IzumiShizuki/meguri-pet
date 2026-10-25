import { describe, expect, it } from 'vitest'
import { conversationPalette } from './conversation-palette'
import { contrastRatio } from './color-contrast'

describe('conversation palette contrast', () => {
  it('keeps primary and secondary text readable in every preset and color mode', () => {
    for (const palette of ['system', 'amber', 'rose', 'mint', 'blue', 'violet'] as const) {
      for (const dark of [false, true]) {
        const colors = conversationPalette(palette, dark, '#0078d4')
        for (const background of [colors.surface, colors.bubble, colors.userBubble]) {
          expect(contrastRatio(colors.text, background)).toBeGreaterThanOrEqual(4.5)
          expect(contrastRatio(colors.muted, background)).toBeGreaterThanOrEqual(4.5)
        }
        expect(contrastRatio(colors.accent, colors.surface)).toBeGreaterThanOrEqual(4.5)
      }
    }
  })
  it('ignores invalid system color strings', () => {
    expect(conversationPalette('system', false, 'url(https://evil.test)')).toEqual(conversationPalette('system', false))
  })
})
