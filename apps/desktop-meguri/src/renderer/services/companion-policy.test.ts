import { describe, expect, it } from 'vitest'

import type { CompanionNoticeInput, CompanionRuntimeContext } from '../../shared/companion-contract'
import { decideCompanionNotice, getCompanionLimits } from './companion-policy'

const available: CompanionRuntimeContext = { focus: 'available', activeTurn: false, speaking: false, surface: 'ambient', userActiveUntil: Number.MAX_SAFE_INTEGER }
const news: CompanionNoticeInput = { id: 'news:1', kind: 'news', text: '有一条消息', createdAt: 1_000, expiresAt: 100_000 }

function decide(overrides: Record<string, unknown> = {}) {
  return decideCompanionNotice({
    initiative: 'normal',
    context: available,
    notice: news,
    now: 2_000,
    lastDeliveredAt: 0,
    pendingCount: 0,
    speechEnabled: true,
    volume: 0.8,
    surfaceEnabled: true,
    expressionsEnabled: true,
    motionEnabled: true,
    ...overrides,
  } as Parameters<typeof decideCompanionNotice>[0])
}

describe('companion attention policy', () => {
  it('defers unsolicited notices at the exact native expiry and fails closed without a sample', () => {
    for (const userActiveUntil of [undefined, 0, 2_000, Number.NaN]) {
      for (const kind of ['news', 'reach_out'] as const)
        expect(decide({ notice: { ...news, kind }, context: { ...available, userActiveUntil } }))
          .toMatchObject({ action: 'defer', reason: 'user_inactive', allowText: false, allowSpeech: false })
    }
    expect(decide({ context: { ...available, userActiveUntil: 2_001 } }).action).toBe('deliver')
  })

  it('retains explicitly requested reminders and commitments while the owner is away', () => {
    for (const kind of ['reminder', 'commitment_followup'] as const)
      expect(decide({ notice: { ...news, kind }, context: { ...available, userActiveUntil: 0 } }).action).toBe('deliver')
  })

  it('defers while focused or while a turn/playback is active', () => {
    expect(decide({ context: { ...available, focus: 'focused' } })).toMatchObject({ action: 'defer', reason: 'focus', allowText: false, allowMotion: false })
    expect(decide({ context: { ...available, activeTurn: true } })).toMatchObject({ action: 'defer', reason: 'active_turn' })
    expect(decide({ context: { ...available, speaking: true } })).toMatchObject({ action: 'defer', reason: 'active_turn' })
  })

  it('keeps text available when speech is muted or the current surface is disabled', () => {
    expect(decide({ speechEnabled: false })).toMatchObject({ action: 'deliver', allowText: true, allowSpeech: false })
    expect(decide({ volume: 0 })).toMatchObject({ action: 'deliver', allowText: true, allowSpeech: false })
    expect(decide({ surfaceEnabled: false })).toMatchObject({ action: 'deliver', allowText: true, allowSpeech: false })
  })

  it('uses initiative to bound queue size and cooldown, and off suppresses only unsolicited news', () => {
    expect(getCompanionLimits('low').maxDeferred).toBeLessThan(getCompanionLimits('high').maxDeferred)
    expect(decide({ initiative: 'off' })).toMatchObject({ action: 'drop', reason: 'initiative_off' })
    const commitment: CompanionNoticeInput = { ...news, id: 'commitment:one', kind: 'commitment_followup' }
    expect(decide({ initiative: 'off', notice: commitment })).toMatchObject({ action: 'deliver', allowText: true, allowSpeech: false, allowMotion: false })
    expect(decide({ lastDeliveredAt: 1_500 })).toMatchObject({ action: 'defer', reason: 'cooldown', retryAt: 5_401_500 })
  })

  it('expires stale notices and drops when the initiative queue is full', () => {
    expect(decide({ now: 100_000 })).toMatchObject({ action: 'drop', reason: 'expired' })
    expect(decide({ initiative: 'low', context: { ...available, focus: 'focused' }, pendingCount: getCompanionLimits('low').maxDeferred })).toMatchObject({ action: 'drop', reason: 'queue_full' })
  })

  it('allows expression and motion only when the user preferences allow them', () => {
    expect(decide({ expressionsEnabled: false })).toMatchObject({ action: 'deliver', allowMotion: false })
    expect(decide({ motionEnabled: false })).toMatchObject({ action: 'deliver', allowMotion: false })
    expect(decide({ initiative: 'high' })).toMatchObject({ action: 'deliver', allowText: true, allowSpeech: true, allowMotion: true })
  })

  it('suppresses reach-outs when off or in a voice call, and respects speech mute', () => {
    const notice: CompanionNoticeInput = { ...news, kind: 'reach_out' }
    expect(decide({ notice, initiative: 'off' })).toMatchObject({ action: 'drop', reason: 'initiative_off' })
    expect(decide({ notice, context: { ...available, voiceActive: true } })).toMatchObject({ action: 'defer', reason: 'active_turn' })
    expect(decide({ notice, speechEnabled: false })).toMatchObject({ action: 'deliver', allowText: true, allowSpeech: false })
  })
})
