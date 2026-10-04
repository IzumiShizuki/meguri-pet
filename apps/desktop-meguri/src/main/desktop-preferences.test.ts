import { describe, expect, it } from 'vitest'

import {
  defaultDesktopPreferences,
  legacyPresentationPatch,
  mergeDesktopPreferences,
  normalizeDesktopPreferences,
} from './desktop-preferences.mjs'

describe('desktop preferences', () => {
  it('normalizes invalid persisted values to safe defaults', () => {
    expect(normalizeDesktopPreferences({
      appearance: 'neon',
      character: { outfitCode: '99' },
      speech: { volume: 8 },
      window: { fadeOnHover: 'yes' },
      shortcuts: { toggleVisibility: 'Delete' },
    })).toEqual({
      ...defaultDesktopPreferences,
      chat: { ...defaultDesktopPreferences.chat },
      character: { ...defaultDesktopPreferences.character },
      window: { ...defaultDesktopPreferences.window },
      speech: { ...defaultDesktopPreferences.speech, volume: 1 },
      shortcuts: { ...defaultDesktopPreferences.shortcuts },
    })
  })

  it('deep-merges a focused update without resetting unrelated preferences', () => {
    const current = normalizeDesktopPreferences({
      appearance: 'dark',
      chat: { visible: false, expanded: true },
      character: { outfitCode: '06', reducedMotion: true },
    })

    const next = mergeDesktopPreferences(current, { chat: { visible: true } })

    expect(next.chat).toEqual({ visible: true, expanded: true })
    expect(next.appearance).toBe('dark')
    expect(next.character).toEqual({ outfitCode: '06', reducedMotion: true })
    expect(next.window.fadeOnHover).toBe(true)
  })

  it('migrates only valid fields from the legacy renderer document', () => {
    expect(legacyPresentationPatch({
      chatVisible: false,
      chatExpanded: true,
      outfitCode: '06',
      reducedMotion: true,
      ignored: 'value',
    })).toEqual({
      chat: { visible: false, expanded: true },
      character: { outfitCode: '06', reducedMotion: true },
    })
    expect(legacyPresentationPatch({ outfitCode: '99' })).toEqual({})
  })
})
