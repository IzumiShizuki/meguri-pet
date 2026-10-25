import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useCompanionDirectorStore } from './companion-director'
import { useDesktopPreferencesStore } from './desktop-preferences'
import type { CompanionSessionMessage, CompanionSessionTransport } from '../services/companion-session-sync'
import type { CompanionCommitment, CompanionDeliveryDecision } from '../../shared/companion-contract'

const notice = (id: string) => ({ id, kind: 'reminder' as const, text: `提醒 ${id}`, createdAt: Date.now(), expiresAt: Number.MAX_SAFE_INTEGER })

describe('companion director', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('drains focused reminders one at a time and rechecks queue capacity without dropping existing items', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.companion.initiative = 'low'
    const director = useCompanionDirectorStore()
    const received: string[] = []
    director.onDelivery(async ({ notice: delivered }) => {
      received.push(delivered.id)
      await director.recordDelivered(delivered.id, 2_000 + received.length)
    })
    await director.setRuntimeContext({ focus: 'focused' })
    expect(director.enqueueNotice(notice('reminder:one')).action).toBe('defer')
    expect(director.enqueueNotice(notice('reminder:two')).action).toBe('defer')

    await director.setRuntimeContext({ focus: 'available' })
    expect(received).toEqual(['reminder:one'])
    expect(director.deferred.map(item => item.id)).toEqual(['reminder:two'])
    await director.flushDeferred()
    expect(received).toEqual(['reminder:one', 'reminder:two'])
  })

  it('awaits each delivery listener before draining another eligible notice', async () => {
    const director = useCompanionDirectorStore()
    const received: string[] = []
    let releaseFirst!: () => void
    director.onDelivery(async ({ notice: delivered }) => {
      received.push(delivered.id)
      if (delivered.id === 'reminder:first')
        await new Promise<void>(resolve => { releaseFirst = resolve })
      await director.recordDelivered(delivered.id, 5_000 + received.length)
    })
    await director.setRuntimeContext({ focus: 'focused' })
    director.enqueueNotice(notice('reminder:first'))
    director.enqueueNotice(notice('reminder:second'))
    const draining = director.setRuntimeContext({ focus: 'available' })
    const overlappingDrain = director.flushDeferred()
    await vi.waitFor(() => expect(received).toEqual(['reminder:first']))
    releaseFirst()
    await Promise.all([draining, overlappingDrain])
    await vi.waitFor(() => expect(received).toHaveLength(2))
    expect(received).toEqual(['reminder:first', 'reminder:second'])
    expect(received.filter(id => id === 'reminder:first')).toHaveLength(1)
  })

  it('emits an eligible due commitment immediately and records its follow-up once', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.saveHistory = false
    const director = useCompanionDirectorStore()
    const commitment = await director.addCommitment('准备材料', 2_000, 1_000)
    expect(commitment).toBeDefined()
    const received: string[] = []
    director.onDelivery(async ({ notice: delivered }) => {
      received.push(delivered.id)
      await director.recordDelivered(delivered.id, 2_100)
    })

    await director.offerDueCommitments(2_000)
    await director.flushDeferred(2_200)
    expect(received).toEqual([`commitment:${commitment!.id}`])
    expect(director.commitments[0].followUpDeliveredAt).toBe(2_100)
  })

  it('removes a deferred follow-up when a commitment is cancelled and keeps unsaved content only in memory', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.saveHistory = false
    const director = useCompanionDirectorStore()
    const now = Date.now()
    const dueAt = now + 60_000
    const commitment = await director.addCommitment('给朋友回信', dueAt, now)
    expect(preferences.value.companion.commitments).toEqual([])
    expect(director.ephemeralCommitments).toHaveLength(1)
    await director.setRuntimeContext({ focus: 'focused' })
    await director.offerDueCommitments(dueAt)
    expect(director.deferred).toHaveLength(1)

    await director.cancelCommitment(commitment!.id)
    await director.offerDueCommitments(dueAt + 1_000)
    expect(director.deferred).toEqual([])
    expect(director.commitments[0].status).toBe('cancelled')
  })

  it('clears deferred and in-memory commitment text when history consent is withdrawn', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.update = async (patch) => {
      if (patch.companion)
        preferences.value.companion = { ...preferences.value.companion, ...patch.companion }
      return preferences.value
    }
    const director = useCompanionDirectorStore()
    director.initialize()
    const commitment = await director.addCommitment('私人的跟进内容', Date.now() + 60_000, Date.now())
    expect(commitment).toBeDefined()
    await director.setRuntimeContext({ focus: 'focused' })
    director.enqueueNotice({
      id: `commitment:${commitment!.id}`,
      kind: 'commitment_followup',
      text: `之前你记下了：「${commitment!.text}」`,
      createdAt: commitment!.dueAt,
      expiresAt: commitment!.dueAt + 86_400_000,
      commitmentId: commitment!.id,
    })
    expect(director.deferred).toHaveLength(1)

    preferences.value.companion.commitments = []
    preferences.value.privacy.saveHistory = false
    expect(director.deferred).toEqual([])
    expect(director.ephemeralCommitments).toEqual([])
  })

  it('publishes ephemeral commitment updates through the initialized renderer transport', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.saveHistory = false
    const messages: CompanionSessionMessage[] = []
    let listener: ((message: unknown) => void) | undefined
    let closed = false
    const transport: CompanionSessionTransport = {
      postMessage(message) { messages.push(structuredClone(message)) },
      onMessage(callback) {
        listener = callback
        return () => { listener = undefined }
      },
      close() { closed = true },
    }
    const director = useCompanionDirectorStore()
    director.initialize(transport)
    const created = await director.addCommitment('只有内存里的承诺', Date.now() + 60_000)

    expect(created).toBeDefined()
    expect(messages.map(message => message.type)).toContain('snapshot_request')
    expect(messages.some(message => message.type === 'upsert' && message.commitment?.id === created!.id)).toBe(true)
    expect(preferences.value.companion.commitments).toEqual([])
    director.dispose()
    expect(closed).toBe(true)
  })

  it('keeps per-store runtime state across Pinia devtools action proxies', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.saveHistory = false
    preferences.value.companion.initiative = 'low'
    const messages: CompanionSessionMessage[] = []
    let closed = false
    const transport: CompanionSessionTransport = {
      postMessage(message) { messages.push(structuredClone(message)) },
      onMessage() { return () => {} },
      close() { closed = true },
    }
    const director = useCompanionDirectorStore()
    const invokeWithPiniaProxy = (action: string, ...args: unknown[]) => {
      const trackedStore = new Proxy(director, {
        get(target, key, receiver) { return Reflect.get(target, key, receiver) },
        set(target, key, value, receiver) { return Reflect.set(target, key, value, receiver) },
      })
      return Reflect.apply((director as any)[action], trackedStore, args)
    }
    const received: string[] = []
    invokeWithPiniaProxy('initialize', transport)
    invokeWithPiniaProxy('onDelivery', ({ notice: delivered }: { notice: { id: string } }) => {
      received.push(delivered.id)
    })
    await invokeWithPiniaProxy('setRuntimeContext', { focus: 'focused' })
    const decision = invokeWithPiniaProxy('enqueueNotice', notice('proxy:notice')) as CompanionDeliveryDecision
    expect(decision.action).toBe('defer')
    await invokeWithPiniaProxy('setRuntimeContext', { focus: 'available' })
    const commitment = await invokeWithPiniaProxy('addCommitment', '跨 action proxy 承诺', Date.now() + 60_000) as CompanionCommitment

    expect(received).toEqual(['proxy:notice'])
    expect(messages.some(message => message.type === 'upsert' && message.commitment?.id === commitment.id)).toBe(true)

    vi.useFakeTimers()
    invokeWithPiniaProxy('startScheduler')
    expect(vi.getTimerCount()).toBe(1)
    invokeWithPiniaProxy('stopScheduler')
    expect(vi.getTimerCount()).toBe(0)
    invokeWithPiniaProxy('dispose')
    expect(closed).toBe(true)
    vi.useRealTimers()
  })

  it('reverses presentation settings without restoring an older commitment lifecycle', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.update = async (patch) => {
      if (patch.companion)
        preferences.value.companion = { ...preferences.value.companion, ...patch.companion }
      return preferences.value
    }
    await preferences.updateCompanion({ initiative: 'high', motionEnabled: false })
    preferences.value.companion.commitments.push({
      id: 'local:changed', text: 'new commitment', dueAt: 2_000, createdAt: 1_000, status: 'completed',
    })

    await preferences.undoCompanionPreferences()
    expect(preferences.value.companion).toMatchObject({ initiative: 'normal', focus: 'available', motionEnabled: true })
    expect(preferences.value.companion.commitments).toHaveLength(1)
    expect(preferences.value.companion.commitments[0].status).toBe('completed')
  })
})
