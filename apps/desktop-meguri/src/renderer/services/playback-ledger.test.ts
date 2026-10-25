import { beforeEach, describe, expect, it, vi } from 'vitest'
import { claimPlaybackEffect, clearPlaybackLedger, playbackReceiptsForSession, reachOutDeliveryCount, releaseUnstartedPlaybackEffect, updatePlaybackReceipt } from './playback-ledger'

const storage = new Map<string, string>()

function installStorage(setItem = (key: string, value: string) => storage.set(key, String(value))) {
  Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem,
    removeItem: (key: string) => storage.delete(key),
  },
  })
}

describe('durable playback ledger', () => {
  beforeEach(() => {
    storage.clear()
    vi.restoreAllMocks()
    installStorage()
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined })
    clearPlaybackLedger()
  })

  it('persists a claim before allowing the same automatic effect after reload', async () => {
    const identity = { sessionId: 'session-a', turnId: 'turn-a', effectId: 'event-1', kind: 'speech' as const }
    expect(await claimPlaybackEffect(identity, 100)).toBe(true)
    expect(await claimPlaybackEffect(identity, 101)).toBe(false)
    expect(playbackReceiptsForSession('session-a')).toMatchObject([{ ...identity, state: 'claimed', claimedAt: 100 }])
  })

  it('isolates receipts by session and records completion or interruption metadata', async () => {
    const first = { sessionId: 'session-a', turnId: 'turn-a', effectId: 'event-1', kind: 'speech' as const }
    const otherSession = { ...first, sessionId: 'session-b' }
    expect(await claimPlaybackEffect(first)).toBe(true)
    expect(await claimPlaybackEffect(otherSession)).toBe(true)
    expect(await updatePlaybackReceipt(first, 'completed', 200)).toBe(true)
    expect(await updatePlaybackReceipt(otherSession, 'interrupted', 201)).toBe(true)
    expect(playbackReceiptsForSession('session-a')[0].state).toBe('completed')
    expect(playbackReceiptsForSession('session-b')[0].state).toBe('interrupted')
  })

  it('releases only a claim that has not started an effect', async () => {
    const identity = { sessionId: 'session-a', effectId: 'notice-1', kind: 'notice' as const }
    expect(await claimPlaybackEffect(identity)).toBe(true)
    expect(await releaseUnstartedPlaybackEffect(identity)).toBe(true)
    expect(await claimPlaybackEffect(identity)).toBe(true)
    expect(await updatePlaybackReceipt(identity, 'completed')).toBe(true)
    expect(await releaseUnstartedPlaybackEffect(identity)).toBe(false)
    expect(await claimPlaybackEffect(identity)).toBe(false)
  })

  it('fails closed when the claim cannot be saved', async () => {
    installStorage(() => { throw new Error('quota') })
    expect(await claimPlaybackEffect({ sessionId: 'session-a', effectId: 'event-1', kind: 'motion' })).toBe(false)
  })

  it('serializes concurrent claims with Web Locks when available', async () => {
    let previous = Promise.resolve()
    const request = vi.fn(async (_name: string, _options: unknown, action: () => Promise<unknown>) => {
      const wait = previous
      let release!: () => void
      previous = new Promise<void>(resolve => { release = resolve })
      await wait
      try { return await action() }
      finally { release() }
    })
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request } })
    const identity = { sessionId: 'session-a', effectId: 'same-event', kind: 'notice' as const }
    expect(await Promise.all([claimPlaybackEffect(identity), claimPlaybackEffect(identity)])).toEqual([true, false])
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('drops malformed and oversized stored records before writing a bounded metadata-only ledger', async () => {
    const oversized = Array.from({ length: 520 }, (_, index) => ({
      sessionId: 'session-a', effectId: `old-${index}`, kind: 'motion', state: 'claimed', claimedAt: index, updatedAt: index,
      speechText: 'must not be retained',
    }))
    oversized.push({ sessionId: '', effectId: 'invalid', kind: 'motion', state: 'claimed', claimedAt: 0, updatedAt: 0, speechText: 'invalid' } as never)
    storage.set('meguri.desktop.playback-ledger.v1', JSON.stringify(oversized))
    expect(await claimPlaybackEffect({ sessionId: 'session-a', effectId: 'new', kind: 'motion' })).toBe(true)
    const raw = storage.get('meguri.desktop.playback-ledger.v1') ?? ''
    const records = JSON.parse(raw) as Record<string, unknown>[]
    expect(records).toHaveLength(512)
    expect(raw).not.toContain('speechText')
    expect(records.every(record => Object.keys(record).every(key => ['sessionId', 'turnId', 'effectId', 'kind', 'state', 'claimedAt', 'updatedAt'].includes(key)))).toBe(true)
  })

  it('fails closed on malformed JSON rather than replaying an uncertain effect', async () => {
    storage.set('meguri.desktop.playback-ledger.v1', '{')
    expect(await claimPlaybackEffect({ sessionId: 'session-a', effectId: 'event-1', kind: 'speech' })).toBe(false)
  })

  it('bounds persisted receipt metadata', async () => {
    for (let index = 0; index < 530; index++)
      expect(await claimPlaybackEffect({ sessionId: 'session-a', effectId: `event-${index}`, kind: 'motion' })).toBe(true)
    const raw = storage.get('meguri.desktop.playback-ledger.v1')
    expect(JSON.parse(raw ?? '[]')).toHaveLength(512)
  })

  it('caps reach-outs across sessions and receipt pruning, resets at local midnight and keeps ordinary reminders available', async () => {
    const now = new Date(2026, 9, 22, 10).getTime()
    for (let index = 0; index < 3; index++)
      expect(await claimPlaybackEffect({ sessionId: `session-${index}`, effectId: `reachout:${index}`, kind: 'notice' }, now)).toBe(true)
    storage.set('meguri.desktop.playback-ledger.v1', '[]')
    expect(reachOutDeliveryCount(now)).toBe(3)
    expect(await claimPlaybackEffect({ sessionId: 'new-session', effectId: 'reachout:four', kind: 'notice' }, now)).toBe(false)
    expect(await claimPlaybackEffect({ sessionId: 'new-session', effectId: 'reminder:one', kind: 'notice' }, now)).toBe(true)
    const tomorrow = new Date(2026, 9, 23, 0).getTime()
    expect(reachOutDeliveryCount(tomorrow)).toBe(0)
    expect(await claimPlaybackEffect({ sessionId: 'new-session', effectId: 'reachout:tomorrow', kind: 'notice' }, tomorrow)).toBe(true)
  })

  it('does not duplicate a daily reservation and fails closed on a corrupt budget', async () => {
    const identity = { sessionId: 's', effectId: 'reachout:one', kind: 'notice' as const }
    expect(await claimPlaybackEffect(identity)).toBe(true)
    expect(await claimPlaybackEffect(identity)).toBe(false)
    expect(reachOutDeliveryCount()).toBe(1)
    storage.set('meguri.desktop.reach-out-budget.v1', '{')
    expect(await claimPlaybackEffect({ ...identity, effectId: 'reachout:two' })).toBe(false)
  })
})
