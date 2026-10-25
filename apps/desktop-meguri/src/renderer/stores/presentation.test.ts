import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { usePresentationStore } from './presentation'

describe('presentation cue recovery', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('restores a static expression without retaining a one-shot motion tag', () => {
    const presentation = usePresentationStore()
    presentation.applyCue({ expression_tag: 'happy', expression_intensity: 'high', motion_tag: 'wave' })
    presentation.applyStaticCue({ expression_tag: 'sad', expression_intensity: 'medium', motion_tag: 'wave' })

    expect(presentation.character.expressionTag).toBe('sad')
    expect(presentation.character.intensity).toBe('medium')
    expect(presentation.character.motionTag).toBe('')
  })

  it('accepts a one-shot motion only through the explicit action path', () => {
    const presentation = usePresentationStore()
    presentation.applyStaticCue({ expression_tag: 'neutral', motion_tag: 'wave' })
    expect(presentation.character.motionTag).toBe('')
    presentation.applyMotionCue('wave')
    expect(presentation.character.motionTag).toBe('wave')
  })
})
