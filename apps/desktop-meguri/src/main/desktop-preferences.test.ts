import { describe, expect, it } from 'vitest'

import {
  defaultDesktopPreferences,
  legacyPresentationPatch,
  mergeDesktopPreferences,
  normalizeDesktopPreferences,
} from './desktop-preferences.mjs'

describe('desktop preferences', () => {
  it('keeps training off until explicitly enabled with current chat consent and disables it on withdrawal', () => {
    expect(normalizeDesktopPreferences({ training: { enabled: true } }).training.enabled).toBe(false)
    const enabled = normalizeDesktopPreferences({ training: { enabled: true }, privacy: { acceptedPolicyVersion: '2026-10-05', remoteChatAllowed: true } })
    expect(enabled.training.enabled).toBe(true)
    expect(mergeDesktopPreferences(enabled, { chat: { expanded: true } }).training.enabled).toBe(true)
    expect(mergeDesktopPreferences(enabled, { privacy: { remoteChatAllowed: false } }).training.enabled).toBe(false)
  })
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

    expect(next.chat).toMatchObject({ visible: true, expanded: true })
    expect(next.appearance).toBe('dark')
    expect(next.character).toMatchObject({ outfitCode: '06', reducedMotion: true })
    expect(next.window.fadeOnHover).toBe(true)
  })

  it('defaults and validates companion preferences while preserving older documents', () => {
    const normalized = normalizeDesktopPreferences({
      appearance: 'dark',
      companion: {
        initiative: 'extreme',
        focus: 'guessing',
        expressionsEnabled: 'yes',
        motionEnabled: false,
        commitments: [{ id: 'bad id', text: 'ignored', dueAt: 10, createdAt: 1, status: 'pending' }],
      },
    })
    expect(normalized.companion).toEqual({
      ...defaultDesktopPreferences.companion,
      motionEnabled: false,
      commitments: [],
    })
    expect(normalized.appearance).toBe('dark')
    expect(normalizeDesktopPreferences({}).companion).toEqual(defaultDesktopPreferences.companion)
  })

  it('persists only validated local commitments when history saving is enabled', () => {
    const commitment = { id: 'local:one', text: '  准备材料  ', dueAt: 2_000, createdAt: 1_000, status: 'pending' }
    const normalized = normalizeDesktopPreferences({ companion: { commitments: [commitment] } })
    expect(normalized.companion.commitments).toEqual([{ ...commitment, text: '准备材料' }])
    const withdrawn = normalizeDesktopPreferences({ privacy: { saveHistory: false }, companion: { commitments: [commitment] } })
    expect(withdrawn.companion.commitments).toEqual([])
    expect(mergeDesktopPreferences(normalized, { privacy: { saveHistory: false } }).companion.commitments).toEqual([])
  })

  it('requires current policy acceptance and bounds resource/playback settings', () => {
    const normalized = normalizeDesktopPreferences({
      privacy: { acceptedPolicyVersion: 'old-policy', remoteChatAllowed: true },
      chat: { wallpaperDataUrl: 'https://remote.example/image.png', wallpaperBlur: 500, bubbleOpacity: 0, colorPalette: 'malicious' },
      speech: { speed: 100 },
      memory: { summaryEveryTurns: 1 },
    })
    expect(normalized.privacy.remoteChatAllowed).toBe(false)
    expect(normalized.privacy.acceptedPolicyVersion).toBe('')
    expect(normalized.chat.wallpaperDataUrl).toBe('')
    expect(normalized.chat.wallpaperBlur).toBe(100)
    expect(normalized.chat.bubbleOpacity).toBe(0.2)
    expect(normalized.speech.speed).toBe(2)
    expect(normalized.memory.summaryEveryTurns).toBe(3)
    const accepted = mergeDesktopPreferences(normalized, { privacy: { acceptedPolicyVersion: '2026-10-05', remoteChatAllowed: true } })
    expect(accepted.privacy.remoteChatAllowed).toBe(true)
    const withdrawn = mergeDesktopPreferences(accepted, { privacy: { remoteChatAllowed: false } })
    expect(withdrawn.privacy.remoteChatAllowed).toBe(false)
    expect(withdrawn.privacy.acceptedPolicyVersion).toBe('2026-10-05')
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
