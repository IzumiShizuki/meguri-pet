import { describe, expect, it } from 'vitest'

import { defaultCharacterState, presentationFromCue } from './character-presentation'

describe('presentationFromCue', () => {
  it('uses a canonical resolved sprite', () => {
    const state = presentationFromCue(defaultCharacterState, {
      expression_tag: 'happy',
      expression_intensity: 'high',
      outfit_code: '02',
      sprite_file: 'ce02202l.png',
      motion_tag: 'bounce',
    })
    expect(state.spriteUrl).toContain('ce02202l.png')
    expect(state.motionTag).toBe('bounce')
  })

  it('rejects unsafe sprite paths and derives a mapped fallback', () => {
    const state = presentationFromCue(defaultCharacterState, {
      expression_tag: 'worried',
      expression_intensity: 'medium',
      outfit_code: '03',
      sprite_file: '../unsafe.exe',
    })
    expect(state.spriteUrl).toContain('ce03016l.png')
  })

  it('retains the last valid state for an unknown cue', () => {
    const state = presentationFromCue(defaultCharacterState, { expression_tag: 'unknown' })
    expect(state.expressionTag).toBe('neutral')
    expect(state.spriteUrl).toContain('ce04001l.png')
  })
})
